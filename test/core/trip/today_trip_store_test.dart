import 'package:copilot/core/constants/native_config.dart';
import 'package:copilot/core/trip/prefs_port.dart';
import 'package:copilot/core/trip/today_trip_store.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('step34_01 upsert persists newest first', () {
    final prefs = MemoryPrefsPort();
    final store = TodayTripStore(prefs, hktDateOf: (_) => '2026-10-26', nowMs: () => 1000);
    store.upsertCandidate('s1', 'cp1', '甲場', 1000);
    store.upsertCandidate('s2', 'cp2', '乙場', 1000);
    final list = store.list(1000);
    expect(list.map((row) => row.sessionId).toList(), ['s2', 's1']);
    expect(store.asBridgeMaps(1000).first['carparkName'], '乙場');
    expect(store.asBridgeMaps(1000).first['status'], 'CANDIDATE');
  });

  test('step34_02 purge on hkt rollover', () {
    final prefs = MemoryPrefsPort();
    var day = '2026-10-26';
    final store = TodayTripStore(prefs, hktDateOf: (_) => day, nowMs: () => 1000);
    store.upsertCandidate('s1', 'cp1', '甲場', 1000);
    expect(store.list(1000), hasLength(1));
    day = '2026-10-27';
    expect(store.list(2000), isEmpty);
    expect(prefs.getString(NativeConfig.TODAY_TRIPS_JSON_KEY), '[]');
  });

  test('step34_03 clearAll drops pending', () {
    final store = TodayTripStore(MemoryPrefsPort(), hktDateOf: (_) => '2026-10-26', nowMs: () => 1000);
    store.upsertCandidate('s1', 'cp1', '甲場', 1000);
    store.markConfirmed('s1', syncPending: true, atMs: 1000);
    expect(store.pendingSyncSessionIds(), ['s1']);
    store.clearAll();
    expect(store.pendingSyncSessionIds(), isEmpty);
    expect(store.find('s1'), isNull);
  });

  test('confirmed upsert only refreshes name and id', () {
    final store = TodayTripStore(MemoryPrefsPort(), hktDateOf: (_) => '2026-10-26', nowMs: () => 1000);
    store.upsertCandidate('s1', 'cp1', '甲場', 1000);
    store.markConfirmed('s1', syncPending: true, atMs: 1000);
    store.upsertCandidate('s1', 'cp2', '乙場', 9000);
    final row = store.find('s1')!;
    expect(row.status, TodayTripStore.statusConfirmed);
    expect(row.confirmSyncPending, isTrue);
    expect(row.carparkId, 'cp2');
    expect(row.carparkName, '乙場');
    expect(row.parkedAtEpochMs, 1000);
  });

  test('default hkt date uses utc+8', () {
    final utc = DateTime.utc(2026, 10, 25, 16).millisecondsSinceEpoch;
    expect(TodayTripStore.defaultHktDateOf(utc), '2026-10-26');
  });
}
