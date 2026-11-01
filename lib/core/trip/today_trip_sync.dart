import 'dart:convert';

import '../constants/native_config.dart';
import '../net/api_client.dart';
import 'sync_api.dart';
import 'today_trip_store.dart';

class TodayTripSync {
  TodayTripSync(this.store, this.api, this.network, {int Function()? nowMs}) : nowMs = nowMs ?? _systemNow;

  final TodayTripStore store;
  final SyncApi api;
  final NetworkStatusProvider network;
  final int Function() nowMs;
  final Set<String> _inFlightSessionIds = <String>{};

  static int _systemNow() => DateTime.now().millisecondsSinceEpoch;

  Future<ConfirmOutcome> confirm(String sessionId) async {
    store.purgeIfNewHktDay(nowMs());
    if (sessionId.trim().isEmpty) {
      return const ConfirmOutcome(ok: false, syncPosted: false, reason: 'BLANK_SESSION');
    }
    final existing = store.find(sessionId);
    if (existing == null) {
      return const ConfirmOutcome(ok: false, syncPosted: false, reason: 'NOT_FOUND');
    }
    if (existing.status == TodayTripStore.statusConfirmed && !existing.confirmSyncPending) {
      return const ConfirmOutcome(ok: true, syncPosted: false, reason: 'ALREADY_CONFIRMED');
    }
    store.markConfirmed(sessionId, syncPending: true, atMs: nowMs());
    if (!network.isOnline()) {
      return const ConfirmOutcome(ok: true, syncPosted: false, reason: 'OFFLINE');
    }
    final posted = await _syncOne(sessionId);
    return ConfirmOutcome(ok: true, syncPosted: posted);
  }

  Future<void> flushPendingConfirms() async {
    store.purgeIfNewHktDay(nowMs());
    if (!network.isOnline()) return;
    for (final id in store.pendingSyncSessionIds()) {
      await _syncOne(id);
    }
  }

  Future<ReportOutcome> submitReport(String payloadJson) async {
    store.purgeIfNewHktDay(nowMs());
    final parsed = parseTripReportPayload(payloadJson);
    if (parsed == null) return const ReportOutcome(ok: false, posted: false, reason: 'BAD_JSON');
    final sessionId = parsed.sessionId;
    if (sessionId.trim().isEmpty) {
      return const ReportOutcome(ok: false, posted: false, reason: 'MISSING_SESSION');
    }
    final record = store.find(sessionId);
    if (record == null) return const ReportOutcome(ok: false, posted: false, reason: 'NOT_FOUND');
    if (record.feedbackSubmitted) {
      return const ReportOutcome(ok: true, posted: false, reason: 'ALREADY_SUBMITTED');
    }
    if (record.status != TodayTripStore.statusConfirmed) {
      return const ReportOutcome(ok: false, posted: false, reason: 'NOT_CONFIRMED');
    }
    if (record.confirmSyncPending) {
      if (network.isOnline()) await _syncOne(sessionId);
      final after = store.find(sessionId);
      if (after == null || after.confirmSyncPending) {
        return const ReportOutcome(ok: false, posted: false, reason: 'CONFIRM_SYNC_PENDING');
      }
    }
    final fresh = store.find(sessionId)!;
    final carparkId = parsed.carparkId.trim().isEmpty ? fresh.carparkId : parsed.carparkId;
    final tripTs = parsed.tripTimestampSec > 0 ? parsed.tripTimestampSec : fresh.parkedAtEpochMs ~/ 1000;
    final notes = _take(parsed.perkNotes, NativeConfig.TODAY_TRIP_NOTES_MAX_CHARS);
    CallOutcome? outcome;
    try {
      outcome = await api.submitTripReport(
        sessionId: sessionId,
        carparkId: carparkId,
        ratingTag: parsed.ratingTag,
        perkStatusTag: parsed.perkStatusTag,
        perkNotes: notes,
        tripTimestampSec: tripTs,
      );
    } catch (_) {
      outcome = null;
    }
    if (outcome != null && _isHttp2xx(outcome)) {
      store.markFeedbackSubmitted(sessionId);
      return const ReportOutcome(ok: true, posted: true);
    }
    final reason = switch (outcome?.httpStatus) {
      409 => 'CONFLICT',
      404 => 'NOT_FOUND_REMOTE',
      _ => 'NETWORK_OR_HTTP',
    };
    return ReportOutcome(ok: false, posted: false, reason: reason);
  }

  Future<bool> _syncOne(String sessionId) async {
    if (!network.isOnline()) return false;
    if (!_inFlightSessionIds.add(sessionId)) return false;
    try {
      final record = store.find(sessionId);
      if (record == null || !record.confirmSyncPending) return false;
      final ts = record.parkedAtEpochMs > 0 ? record.parkedAtEpochMs ~/ 1000 : nowMs() ~/ 1000;
      CallOutcome? outcome;
      try {
        outcome = await api.syncDriverConfirmed(sessionId, ts);
      } catch (_) {
        outcome = null;
      }
      if (outcome != null && _isHttp2xx(outcome)) {
        store.markSyncSent(sessionId);
        return true;
      }
      return false;
    } finally {
      _inFlightSessionIds.remove(sessionId);
    }
  }

  bool _isHttp2xx(CallOutcome outcome) {
    final code = outcome.httpStatus;
    if (code != null) return code >= 200 && code <= 299;
    return outcome.succeeded;
  }

  static String _take(String value, int max) => value.length <= max ? value : value.substring(0, max);
}

class ParsedReport {
  const ParsedReport({
    required this.sessionId,
    required this.carparkId,
    required this.ratingTag,
    required this.perkStatusTag,
    required this.perkNotes,
    required this.tripTimestampSec,
  });

  final String sessionId;
  final String carparkId;
  final String ratingTag;
  final String perkStatusTag;
  final String perkNotes;
  final int tripTimestampSec;
}

ParsedReport? parseTripReportPayload(String payloadJson) {
  final raw = payloadJson.trim();
  if (raw.isEmpty || (!raw.startsWith('{') && !raw.startsWith('['))) return null;
  try {
    final decoded = jsonDecode(raw);
    if (decoded is! Map) {
      return const ParsedReport(sessionId: '', carparkId: '', ratingTag: '', perkStatusTag: '', perkNotes: '', tripTimestampSec: 0);
    }
    String str(String a, String b) {
      final value = decoded[a] ?? decoded[b];
      return value is String ? value : '';
    }

    int readInt(String a, String b) {
      final value = decoded[a] ?? decoded[b];
      if (value is int) return value;
      if (value is num) return value.toInt();
      return 0;
    }

    return ParsedReport(
      sessionId: str('session_id', 'sessionId'),
      carparkId: str('carpark_id', 'carparkId'),
      ratingTag: str('rating_tag', 'ratingTag'),
      perkStatusTag: str('perk_status_tag', 'perkStatusTag'),
      perkNotes: str('perk_notes', 'perkNotes'),
      tripTimestampSec: readInt('trip_timestamp', 'tripTimestamp'),
    );
  } catch (_) {
    return null;
  }
}
