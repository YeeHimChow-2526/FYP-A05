import 'dart:async';

import 'package:copilot/core/net/api_client.dart';
import 'package:copilot/core/net/fake_api_transport.dart';
import 'package:copilot/core/net/http_result.dart';
import 'package:copilot/core/trip/api_client_sync_api.dart';
import 'package:copilot/core/trip/prefs_port.dart';
import 'package:copilot/core/trip/sync_api.dart';
import 'package:copilot/core/trip/today_trip_store.dart';
import 'package:copilot/core/trip/today_trip_sync.dart';
import 'package:flutter_test/flutter_test.dart';

class _Net implements NetworkStatusProvider {
  _Net(this.online);
  bool online;
  @override
  bool isOnline() => online;
}

class _RecordingApi implements SyncApi {
  final syncPosts = <String>[];
  final tripPosts = <String>[];
  final order = <String>[];
  Completer<CallOutcome>? hold;

  @override
  Future<CallOutcome> syncDriverConfirmed(String sessionId, int timestampSec) async {
    syncPosts.add(sessionId);
    order.add('sync');
    final gate = hold;
    if (gate != null) return gate.future;
    return const CallOutcome(
      succeeded: true,
      httpStatus: 200,
      spokenCues: [],
      attempts: 1,
      waitIntervalsMs: [],
      shouldTurnGrey: false,
      shouldTurnReady: false,
    );
  }

  @override
  Future<CallOutcome> submitTripReport({
    required String sessionId,
    required String carparkId,
    required String ratingTag,
    required String perkStatusTag,
    required String perkNotes,
    required int tripTimestampSec,
  }) async {
    tripPosts.add(sessionId);
    order.add('trip');
    return const CallOutcome(
      succeeded: true,
      httpStatus: 200,
      spokenCues: [],
      attempts: 1,
      waitIntervalsMs: [],
      shouldTurnGrey: false,
      shouldTurnReady: false,
    );
  }
}

TodayTripSync _sync(_RecordingApi api, {bool online = true, int now = 2000}) {
  final store = TodayTripStore(MemoryPrefsPort(), hktDateOf: (_) => '2026-10-26', nowMs: () => now);
  store.upsertCandidate('s1', 'cp1', '甲場', now);
  return TodayTripSync(store, api, _Net(online), nowMs: () => now);
}

void main() {
  test('online confirm posts via recording api', () async {
    final api = _RecordingApi();
    final sync = _sync(api);
    final out = await sync.confirm('s1');
    expect(out.ok, isTrue);
    expect(out.syncPosted, isTrue);
    expect(api.syncPosts, ['s1']);
    expect(sync.store.find('s1')!.confirmSyncPending, isFalse);
  });

  test('offline confirm stays pending', () async {
    final api = _RecordingApi();
    final sync = _sync(api, online: false);
    final out = await sync.confirm('s1');
    expect(out.reason, 'OFFLINE');
    expect(api.syncPosts, isEmpty);
    expect(sync.store.find('s1')!.confirmSyncPending, isTrue);
  });

  test('flush sends pending confirm', () async {
    final api = _RecordingApi();
    final sync = _sync(api, online: false);
    await sync.confirm('s1');
    (sync.network as _Net).online = true;
    await sync.flushPendingConfirms();
    expect(api.syncPosts, ['s1']);
    expect(sync.store.find('s1')!.confirmSyncPending, isFalse);
  });

  test('report requires confirmed then posts after confirm', () async {
    final api = _RecordingApi();
    final sync = _sync(api);
    final blocked = await sync.submitReport('{"session_id":"s1","rating_tag":"GOOD","perk_status_tag":"NONE","perk_notes":""}');
    expect(blocked.reason, 'NOT_CONFIRMED');
    expect(api.tripPosts, isEmpty);
    await sync.confirm('s1');
    final out = await sync.submitReport('{"session_id":"s1","rating_tag":"GOOD","perk_status_tag":"NONE","perk_notes":"","trip_timestamp":2}');
    expect(out.posted, isTrue);
    expect(sync.store.find('s1')!.feedbackSubmitted, isTrue);
  });

  test('report flushes pending before trip', () async {
    final api = _RecordingApi();
    final sync = _sync(api, online: false, now: 3000);
    await sync.confirm('s1');
    (sync.network as _Net).online = true;
    final out = await sync.submitReport('{"session_id":"s1","rating_tag":"GOOD","perk_status_tag":"NONE","perk_notes":""}');
    expect(out.posted, isTrue);
    expect(api.order.indexOf('sync'), lessThan(api.order.indexOf('trip')));
  });

  test('second confirm is idempotent', () async {
    final api = _RecordingApi();
    final sync = _sync(api);
    await sync.confirm('s1');
    final again = await sync.confirm('s1');
    expect(again.reason, 'ALREADY_CONFIRMED');
    expect(api.syncPosts, hasLength(1));
  });

  test('in-flight confirm and flush post once', () async {
    final api = _RecordingApi()..hold = Completer<CallOutcome>();
    final sync = _sync(api);
    final first = sync.confirm('s1');
    await Future<void>.delayed(Duration.zero);
    await sync.flushPendingConfirms();
    api.hold!.complete(const CallOutcome(
      succeeded: true,
      httpStatus: 200,
      spokenCues: [],
      attempts: 1,
      waitIntervalsMs: [],
      shouldTurnGrey: false,
      shouldTurnReady: false,
    ));
    await first;
    expect(api.syncPosts, hasLength(1));
  });

  test('ApiClientSyncApi calls Dart ApiClient', () async {
    final transport = FakeApiTransport([
      const HttpResult(statusCode: 200, bodyJson: '{}'),
      const HttpResult(statusCode: 200, bodyJson: '{}'),
    ]);
    final client = ApiClient(transport, sleeper: (_) async {});
    final api = ApiClientSyncApi(client);
    final store = TodayTripStore(MemoryPrefsPort(), hktDateOf: (_) => '2026-10-26', nowMs: () => 2000);
    store.upsertCandidate('s1', 'cp1', '甲場', 2000);
    final sync = TodayTripSync(store, api, _Net(true), nowMs: () => 2000);
    await sync.confirm('s1');
    await sync.submitReport('{"session_id":"s1","rating_tag":"GOOD","perk_status_tag":"NONE","perk_notes":""}');
    expect(transport.calls.map((call) => call.path).toList(), [ApiClient.SYNC, ApiClient.TRIP]);
  });
}
