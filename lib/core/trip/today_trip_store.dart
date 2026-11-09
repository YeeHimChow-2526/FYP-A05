import 'dart:convert';

import '../constants/native_config.dart';
import 'prefs_port.dart';
import 'today_trip_record.dart';

typedef TripListener = void Function();

class TodayTripStore {
  TodayTripStore(
    this.prefs, {
    String Function(int epochMs)? hktDateOf,
    int Function()? nowMs,
    this.listener,
  })  : hktDateOf = hktDateOf ?? defaultHktDateOf,
        nowMs = nowMs ?? _systemNow;

  final PrefsPort prefs;
  final String Function(int epochMs) hktDateOf;
  final int Function() nowMs;
  final TripListener? listener;
  final List<TripListener> listeners = [];

  void addListener(TripListener listener) => listeners.add(listener);

  void reloadFromPrefs() => _notify();

  void removeListener(TripListener listener) => listeners.remove(listener);

  static const String statusCandidate = TodayTripStoreStatus.candidate;
  static const String statusConfirmed = TodayTripStoreStatus.confirmed;

  static int _systemNow() => DateTime.now().millisecondsSinceEpoch;

  static String defaultHktDateOf(int epochMs) {
    final hkt = DateTime.fromMillisecondsSinceEpoch(epochMs, isUtc: true).add(const Duration(hours: 8));
    final y = hkt.year.toString().padLeft(4, '0');
    final m = hkt.month.toString().padLeft(2, '0');
    final d = hkt.day.toString().padLeft(2, '0');
    return '$y-$m-$d';
  }

  List<TodayTripRecord> list(int nowMs) {
    purgeIfNewHktDay(nowMs);
    return _load();
  }

  List<Map<String, Object?>> asBridgeMaps(int nowMs) => list(nowMs).map((row) => row.toJson()).toList();

  void upsertCandidate(
    String sessionId,
    String carparkId,
    String carparkName,
    int parkedAtEpochMs, {
    int? lockRevision,
    int? destinationEpoch,
  }) {
    purgeIfNewHktDay(parkedAtEpochMs);
    final records = _load();
    final idx = records.indexWhere((row) => row.sessionId == sessionId);
    if (idx >= 0) {
      final existing = records[idx];
      if (existing.status == statusConfirmed) {
        records[idx] = existing.copyWith(
          carparkId: carparkId.trim().isEmpty ? existing.carparkId : carparkId,
          carparkName: carparkName.trim().isEmpty ? existing.carparkName : carparkName,
        );
      } else {
        final updated = TodayTripRecord(
          sessionId: sessionId,
          carparkId: carparkId,
          carparkName: carparkName,
          parkedAtEpochMs: parkedAtEpochMs,
          status: statusCandidate,
          confirmSyncPending: false,
          feedbackSubmitted: false,
          contractVersion: 2,
          lockRevision: lockRevision,
          destinationEpoch: destinationEpoch,
        );
        records.removeAt(idx);
        records.insert(0, updated);
      }
    } else {
      records.insert(
        0,
        TodayTripRecord(
          sessionId: sessionId,
          carparkId: carparkId,
          carparkName: carparkName,
          parkedAtEpochMs: parkedAtEpochMs,
          status: statusCandidate,
          confirmSyncPending: false,
          feedbackSubmitted: false,
          contractVersion: 2,
          lockRevision: lockRevision,
          destinationEpoch: destinationEpoch,
        ),
      );
    }
    _save(records, hktDateOf(parkedAtEpochMs));
    _notify();
  }

  bool markConfirmed(String sessionId, {required bool syncPending, int? atMs}) {
    purgeIfNewHktDay(atMs ?? nowMs());
    final records = _load();
    final idx = records.indexWhere((row) => row.sessionId == sessionId);
    if (idx < 0) return false;
    records[idx] = syncPending
        ? records[idx].copyWith(
            status: statusConfirmed,
            confirmSyncPending: true,
            confirmSyncSent: false,
            clearSyncRejected: true,
          )
        : records[idx].copyWith(status: statusConfirmed, confirmSyncPending: false);
    _save(records, prefs.getString(NativeConfig.TODAY_TRIPS_HKT_DATE_KEY));
    _notify();
    return true;
  }

  /// 伺服器明確拒絕（4xx typed）→ 停止重試、保留原因。
  void markSyncRejected(String sessionId, String code) {
    final records = _load();
    final idx = records.indexWhere((row) => row.sessionId == sessionId);
    if (idx < 0) return;
    records[idx] = records[idx].copyWith(
      confirmSyncPending: false,
      confirmSyncSent: false,
      syncRejected: code,
    );
    _save(records, prefs.getString(NativeConfig.TODAY_TRIPS_HKT_DATE_KEY));
    _notify();
  }

  void markSyncSent(String sessionId) {
    final records = _load();
    final idx = records.indexWhere((row) => row.sessionId == sessionId);
    if (idx < 0) return;
    records[idx] = records[idx].copyWith(
      confirmSyncPending: false,
      confirmSyncSent: true,
      clearSyncRejected: true,
    );
    _save(records, prefs.getString(NativeConfig.TODAY_TRIPS_HKT_DATE_KEY));
    _notify();
  }

  bool markFeedbackSubmitted(String sessionId) {
    final records = _load();
    final idx = records.indexWhere((row) => row.sessionId == sessionId);
    if (idx < 0) return false;
    records[idx] = records[idx].copyWith(feedbackSubmitted: true);
    _save(records, prefs.getString(NativeConfig.TODAY_TRIPS_HKT_DATE_KEY));
    _notify();
    return true;
  }

  List<String> pendingSyncSessionIds() => _load().where((row) => row.confirmSyncPending).map((row) => row.sessionId).toList();

  TodayTripRecord? find(String sessionId) {
    for (final row in _load()) {
      if (row.sessionId == sessionId) return row;
    }
    return null;
  }

  void clearAll() {
    prefs.putString(NativeConfig.TODAY_TRIPS_JSON_KEY, '[]');
    prefs.remove(NativeConfig.TODAY_TRIPS_HKT_DATE_KEY);
    _notify();
  }

  void purgeIfNewHktDay(int nowMs) {
    final today = hktDateOf(nowMs);
    final stored = prefs.getString(NativeConfig.TODAY_TRIPS_HKT_DATE_KEY);
    if (stored == null) {
      prefs.putString(NativeConfig.TODAY_TRIPS_HKT_DATE_KEY, today);
      return;
    }
    if (stored != today) {
      prefs.putString(NativeConfig.TODAY_TRIPS_JSON_KEY, '[]');
      prefs.putString(NativeConfig.TODAY_TRIPS_HKT_DATE_KEY, today);
      _notify();
    }
  }

  List<TodayTripRecord> _load() {
    final raw = prefs.getString(NativeConfig.TODAY_TRIPS_JSON_KEY);
    if (raw == null || raw.trim().isEmpty) return [];
    try {
      final decoded = jsonDecode(raw);
      if (decoded is! List) return [];
      return decoded.map(TodayTripRecord.fromJson).whereType<TodayTripRecord>().toList();
    } catch (_) {
      return [];
    }
  }

  void _save(List<TodayTripRecord> records, String? hktDate) {
    prefs.putString(NativeConfig.TODAY_TRIPS_JSON_KEY, jsonEncode(records.map((row) => row.toJson()).toList()));
    if (hktDate != null) prefs.putString(NativeConfig.TODAY_TRIPS_HKT_DATE_KEY, hktDate);
  }

  void _notify() {
    listener?.call();
    for (final item in List<TripListener>.of(listeners)) {
      item();
    }
  }
}
