import 'dart:convert';

import 'package:copilot/core/net/api_client.dart';
import 'package:copilot/core/net/api_transport.dart';
import 'package:copilot/core/net/http_result.dart';
import 'package:copilot/core/trip/api_client_sync_api.dart';
import 'package:copilot/core/trip/prefs_port.dart';
import 'package:copilot/core/trip/sync_api.dart';
import 'package:copilot/core/trip/today_trip_store.dart';
import 'package:copilot/core/trip/today_trip_sync.dart';
import 'package:flutter_test/flutter_test.dart';

// Phase 3d：泊車確認 outbox 身份。持久化用 PrefsPort（測試＝MemoryPrefsPort 模擬重開；
// 真機係 ChannelPrefsPort → SharedPreferences，未喺真機驗證）。
class _Net implements NetworkStatusProvider {
  bool online = true;
  @override
  bool isOnline() => online;
}

class _Transport implements ApiTransport {
  final bodies = <Map<String, Object?>>[];
  HttpResult next = const HttpResult(statusCode: 200, bodyJson: '{"status":"PARKED_CONFIRMED"}');
  @override
  Future<HttpResult> post(String path, String bodyJson) async {
    bodies.add(jsonDecode(bodyJson) as Map<String, Object?>);
    return next;
  }
}

TodayTripStore _store(PrefsPort prefs) => TodayTripStore(prefs, hktDateOf: (_) => '2026-11-08', nowMs: () => 5000);

void main() {
  test('O1 identity captured at creation survives restart and is replayed unchanged', () async {
    final prefs = MemoryPrefsPort();
    _store(prefs).upsertCandidate('s1', 'CP_A', '甲', 5000, lockRevision: 3, destinationEpoch: 2);
    final net = _Net()..online = false;
    final transport = _Transport();
    final first = TodayTripSync(_store(prefs), ApiClientSyncApi(ApiClient(transport, sleeper: (_) async {})), net, nowMs: () => 6000);
    expect((await first.confirm('s1')).reason, 'OFFLINE');
    // 「重開 App」：新 store 讀返同一 prefs
    final restarted = _store(prefs);
    final row = restarted.find('s1')!;
    expect((row.contractVersion, row.lockRevision, row.destinationEpoch, row.confirmSyncPending), (2, 3, 2, true));
    net.online = true;
    await TodayTripSync(restarted, ApiClientSyncApi(ApiClient(transport, sleeper: (_) async {})), net, nowMs: () => 9000).flushPendingConfirms();
    expect(transport.bodies.single, {
      'session_id': 's1',
      'event_type': 'DRIVER_CONFIRMED',
      'timestamp': 5,
      'contract_version': 2,
      'destination_epoch': 2,
      'lock_revision': 3,
    });
    expect(restarted.find('s1')!.confirmSyncPending, isFalse);
  });

  test('O2 typed 409 (STALE_REVISION) stops retries and records the reason', () async {
    final prefs = MemoryPrefsPort();
    final store = _store(prefs)..upsertCandidate('s1', 'CP_A', '甲', 5000, lockRevision: 1, destinationEpoch: 1);
    final transport = _Transport()..next = const HttpResult(statusCode: 409, bodyJson: '{"status":"STALE_REVISION"}');
    final sync = TodayTripSync(store, ApiClientSyncApi(ApiClient(transport, sleeper: (_) async {})), _Net(), nowMs: () => 6000);
    await sync.confirm('s1');
    expect(store.find('s1')!.syncRejected, 'STALE_REVISION');
    expect(store.find('s1')!.confirmSyncPending, isFalse);
    final posts = transport.bodies.length;
    await sync.flushPendingConfirms();
    expect(transport.bodies.length, posts, reason: '明確拒絕唔再重試');
  });

  test('O3 503 CONTENTION stays pending and retries with the same identity; duplicate confirm does not repost', () async {
    final store = _store(MemoryPrefsPort())..upsertCandidate('s1', 'CP_A', '甲', 5000, destinationEpoch: 4);
    final transport = _Transport()..next = const HttpResult(statusCode: 503, bodyJson: '{"status":"CONTENTION"}');
    final sync = TodayTripSync(store, ApiClientSyncApi(ApiClient(transport, sleeper: (_) async {})), _Net(), nowMs: () => 6000);
    await sync.confirm('s1');
    expect(store.find('s1')!.confirmSyncPending, isTrue);
    transport.next = const HttpResult(statusCode: 200, bodyJson: '{"status":"PARKED_CONFIRMED"}');
    await sync.flushPendingConfirms();
    expect(store.find('s1')!.confirmSyncPending, isFalse);
    final sent = transport.bodies.where((b) => b['session_id'] == 's1').toList();
    expect(sent.map((b) => (b['contract_version'], b['destination_epoch'], b.containsKey('lock_revision'))).toSet(), {(2, 4, false)},
        reason: '冇鎖嘅 v2 事件：唔送 lock_revision（伺服器見到有鎖會拒絕，唔會刪）');
    final count = transport.bodies.length;
    expect((await sync.confirm('s1')).reason, 'ALREADY_CONFIRMED');
    expect(transport.bodies.length, count);
  });

  test('O4 legacy stored record (old app JSON) replays as unversioned', () async {
    final prefs = MemoryPrefsPort();
    final store = _store(prefs)..upsertCandidate('s1', 'CP_A', '甲', 5000);
    final raw = prefs.getString('today_trips_json')!;
    final legacy = (jsonDecode(raw) as List).map((e) => (Map<String, Object?>.from(e as Map))..remove('contractVersion')..remove('destinationEpoch')).toList();
    prefs.putString('today_trips_json', jsonEncode(legacy));
    final transport = _Transport();
    await TodayTripSync(store, ApiClientSyncApi(ApiClient(transport, sleeper: (_) async {})), _Net(), nowMs: () => 6000).confirm('s1');
    expect(transport.bodies.single.containsKey('contract_version'), isFalse);
    expect(transport.bodies.single.containsKey('lock_revision'), isFalse);
  });
}
