import 'dart:async';
import 'dart:convert';

import 'package:copilot/core/constants/native_config.dart';
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
  Future<CallOutcome> syncDriverConfirmed(String sessionId, int timestampSec, {ParkedEventIdentity identity = ParkedEventIdentity.legacy}) async {
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

class _StatusApi extends _RecordingApi {
  _StatusApi({this.syncStatus = 200});

  int syncStatus;
  int tripStatus = 200;

  CallOutcome _outcome(int status) => CallOutcome(
        succeeded: status >= 200 && status <= 299,
        httpStatus: status,
        spokenCues: const [],
        attempts: 1,
        waitIntervalsMs: const [],
        shouldTurnGrey: false,
        shouldTurnReady: false,
        bodyJson: '{"status":"HTTP_$status"}',
      );

  @override
  Future<CallOutcome> syncDriverConfirmed(String sessionId, int timestampSec, {ParkedEventIdentity identity = ParkedEventIdentity.legacy}) async {
    syncPosts.add(sessionId);
    order.add('sync');
    return _outcome(syncStatus);
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
    return _outcome(tripStatus);
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
    final body = jsonDecode(transport.calls.last.body) as Map<String, Object?>;
    expect(body.keys.toSet(), {'session_id', 'trip_timestamp', 'feedback'});
  });

  test('404 confirm rejection does not post a trip report', () async {
    final api = _StatusApi(syncStatus: 404);
    final sync = _sync(api);
    await sync.confirm('s1');
    final out = await sync.submitReport('{"session_id":"s1","rating_tag":"BAD","perk_status_tag":"NONE","perk_notes":""}');
    expect(out.reason, 'CONFIRM_SYNC_REJECTED');
    expect(api.tripPosts, isEmpty);
    expect(sync.store.find('s1')!.feedbackSubmitted, isFalse);
  });

  test('explicit confirm rejection does not post a trip report', () async {
    final api = _StatusApi(syncStatus: 409);
    final sync = _sync(api);
    await sync.confirm('s1');
    final out = await sync.submitReport('{"session_id":"s1","rating_tag":"GOOD","perk_status_tag":"NONE","perk_notes":""}');
    expect(out.reason, 'CONFIRM_SYNC_REJECTED');
    expect(out.posted, isFalse);
    expect(api.tripPosts, isEmpty);
    expect(sync.store.find('s1')!.feedbackSubmitted, isFalse);
    expect(sync.store.find('s1')!.confirmSyncSent, isFalse);
  });

  test('rejected confirm still blocks after the store is reopened', () async {
    final prefs = MemoryPrefsPort();
    final firstStore = TodayTripStore(prefs, hktDateOf: (_) => '2026-10-26', nowMs: () => 2000);
    firstStore.upsertCandidate('s1', 'cp1', '甲場', 2000);
    final first = TodayTripSync(firstStore, _StatusApi(syncStatus: 422), _Net(true), nowMs: () => 2000);
    await first.confirm('s1');
    final reopened = TodayTripStore(prefs, hktDateOf: (_) => '2026-10-26', nowMs: () => 2000);
    final api = _RecordingApi();
    final sync = TodayTripSync(reopened, api, _Net(true), nowMs: () => 2000);
    final out = await sync.submitReport('{"session_id":"s1","rating_tag":"GOOD","perk_status_tag":"VALID","perk_notes":""}');
    expect(reopened.find('s1')!.syncRejected, isNotNull);
    expect(out.reason, 'CONFIRM_SYNC_REJECTED');
    expect(api.tripPosts, isEmpty);
  });

  test('old record without confirmSyncSent is not treated as synced', () async {
    final prefs = MemoryPrefsPort();
    prefs.putString(
      NativeConfig.TODAY_TRIPS_JSON_KEY,
      '[{"sessionId":"s1","carparkId":"cp1","carparkName":"甲場","parkedAtEpochMs":2000,"status":"CONFIRMED","confirmSyncPending":false,"feedbackSubmitted":false}]',
    );
    prefs.putString(NativeConfig.TODAY_TRIPS_HKT_DATE_KEY, '2026-10-26');
    final store = TodayTripStore(prefs, hktDateOf: (_) => '2026-10-26', nowMs: () => 2000);
    final api = _RecordingApi();
    final out = await TodayTripSync(store, api, _Net(true), nowMs: () => 2000).submitReport(
      '{"session_id":"s1","rating_tag":"OK","perk_status_tag":"INVALID","perk_notes":""}',
    );
    expect(store.find('s1')!.confirmSyncSent, isFalse);
    expect(out.reason, 'CONFIRM_SYNC_UNKNOWN');
    expect(api.tripPosts, isEmpty);
  });

  test('pending confirm is not posted while still offline', () async {
    final api = _RecordingApi();
    final sync = _sync(api, online: false);
    await sync.confirm('s1');
    final out = await sync.submitReport('{"session_id":"s1","rating_tag":"GOOD","perk_status_tag":"NONE","perk_notes":""}');
    expect(out.reason, 'CONFIRM_SYNC_PENDING');
    expect(api.tripPosts, isEmpty);
    expect(api.syncPosts, isEmpty);
  });

  test('http failure does not mark feedback submitted and a later success can post once', () async {
    final api = _StatusApi();
    final sync = _sync(api);
    await sync.confirm('s1');
    api.tripStatus = 500;
    final failed = await sync.submitReport('{"session_id":"s1","rating_tag":"GOOD","perk_status_tag":"NONE","perk_notes":""}');
    expect(failed.reason, 'NETWORK_OR_HTTP');
    expect(sync.store.find('s1')!.feedbackSubmitted, isFalse);
    api.tripStatus = 200;
    final again = await sync.submitReport('{"session_id":"s1","rating_tag":"GOOD","perk_status_tag":"NONE","perk_notes":""}');
    expect(again.posted, isTrue);
    final duplicate = await sync.submitReport('{"session_id":"s1","rating_tag":"GOOD","perk_status_tag":"NONE","perk_notes":""}');
    expect(duplicate.reason, 'ALREADY_SUBMITTED');
    expect(api.tripPosts, hasLength(2));
  });

  test('midnight rollover drops the local list before any trip report', () async {
    final prefs = MemoryPrefsPort();
    var day = '2026-11-09';
    final store = TodayTripStore(prefs, hktDateOf: (_) => day, nowMs: () => 1);
    store.upsertCandidate('s1', 'cp1', '甲場', 1);
    final api = _RecordingApi();
    final sync = TodayTripSync(store, api, _Net(true), nowMs: () => 1);
    await sync.confirm('s1');
    day = '2026-11-10';
    final out = await sync.submitReport('{"session_id":"s1","rating_tag":"GOOD","perk_status_tag":"NONE","perk_notes":""}');
    expect(out.reason, 'NOT_FOUND');
    expect(api.tripPosts, isEmpty);
    expect(TodayTripStore.defaultHktDateOf(DateTime.utc(2026, 11, 9, 15, 59, 59).millisecondsSinceEpoch), '2026-11-09');
    expect(TodayTripStore.defaultHktDateOf(DateTime.utc(2026, 11, 9, 16, 0, 0).millisecondsSinceEpoch), '2026-11-10');
  });
}
