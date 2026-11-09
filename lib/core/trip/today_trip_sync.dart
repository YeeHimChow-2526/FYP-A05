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
    final blocked = await _blockUntilConfirmSynced(sessionId);
    if (blocked != null) return blocked;
    final fresh = store.find(sessionId)!;
    final carparkId = parsed.carparkId.trim().isEmpty ? fresh.carparkId : parsed.carparkId;
    final tripTs = parsed.tripTimestampSec > 0 ? parsed.tripTimestampSec : fresh.parkedAtEpochMs ~/ 1000;
    final notes = truncatePerkNotes(parsed.perkNotes, NativeConfig.TODAY_TRIP_NOTES_MAX_CHARS);
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
    if (outcome != null && outcome.attempts == 0 && outcome.httpStatus == null) {
      return const ReportOutcome(ok: false, posted: false, reason: 'INVALID_FEEDBACK');
    }
    final reason = switch (outcome?.httpStatus) {
      409 => 'CONFLICT',
      404 => 'NOT_FOUND_REMOTE',
      _ => 'NETWORK_OR_HTTP',
    };
    return ReportOutcome(ok: false, posted: false, reason: reason);
  }

  /// 成功先係 confirmSyncSent。pending=false 可以係拒絕或舊資料未知，兩種都唔送。
  Future<ReportOutcome?> _blockUntilConfirmSynced(String sessionId) async {
    final current = store.find(sessionId);
    if (current == null) return const ReportOutcome(ok: false, posted: false, reason: 'NOT_FOUND');
    if (current.syncRejected != null) {
      return const ReportOutcome(ok: false, posted: false, reason: 'CONFIRM_SYNC_REJECTED');
    }
    if (current.confirmSyncPending) {
      if (network.isOnline()) await _syncOne(sessionId);
      final after = store.find(sessionId);
      if (after == null) return const ReportOutcome(ok: false, posted: false, reason: 'NOT_FOUND');
      if (after.syncRejected != null) {
        return const ReportOutcome(ok: false, posted: false, reason: 'CONFIRM_SYNC_REJECTED');
      }
      if (after.confirmSyncPending || !after.confirmSyncSent) {
        return const ReportOutcome(ok: false, posted: false, reason: 'CONFIRM_SYNC_PENDING');
      }
      return null;
    }
    if (!current.confirmSyncSent) {
      return const ReportOutcome(ok: false, posted: false, reason: 'CONFIRM_SYNC_UNKNOWN');
    }
    return null;
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
        // 重播用建立嗰刻存低嘅身份，原封不動。
        outcome = await api.syncDriverConfirmed(
          sessionId,
          ts,
          identity: ParkedEventIdentity(
            contractVersion: record.contractVersion,
            lockRevision: record.lockRevision,
            destinationEpoch: record.destinationEpoch,
          ),
        );
      } catch (_) {
        outcome = null;
      }
      if (outcome != null && _isHttp2xx(outcome)) {
        store.markSyncSent(sessionId);
        return true;
      }
      final code = outcome?.httpStatus;
      if (outcome != null && (code == 409 || code == 404 || code == 422)) {
        // 伺服器明確拒絕（STALE_REVISION／STALE_EPOCH／REVISION_REQUIRED／SESSION_CLOSED…）：重試都唔會變，停。
        final status = outcome.bodyFields['status'];
        store.markSyncRejected(sessionId, status is String ? status : 'HTTP_$code');
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

}

String feedbackFailureText(String? reason) => switch (reason) {
      'CONFIRM_SYNC_REJECTED' => '確認被拒，未提交反饋',
      'CONFIRM_SYNC_PENDING' => '確認仍在同步，未提交反饋',
      'CONFIRM_SYNC_UNKNOWN' => '未能核對確認結果，未提交反饋',
      'CONFLICT' => '伺服器未接受，未提交',
      'NOT_FOUND_REMOTE' => '找不到行程，未提交',
      'INVALID_FEEDBACK' => '反饋格式不正確，未提交',
      'NOT_CONFIRMED' => '未確認泊妥，未提交',
      'NETWORK_OR_HTTP' => '未能確認提交結果',
      _ => '提交失敗，未送出',
    };

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
