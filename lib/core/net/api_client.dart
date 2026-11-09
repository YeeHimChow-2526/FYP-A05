// ignore_for_file: constant_identifier_names

import 'dart:convert';

import 'package:flutter/foundation.dart';

import '../constants/app_config.dart';
import '../constants/native_config.dart';
import '../voice/zh_hk_templates.dart';
import 'api_responses.dart';
import 'server_issue.dart';
import 'api_transport.dart';
import 'http_result.dart';
import 'recommend_response.dart';

/// 備註上限係 UTF-16 code unit。截斷如果會切開 emoji 的高位代理，就退一格。
String truncatePerkNotes(String value, int maxChars) {
  if (maxChars <= 0) return '';
  var end = value.length <= maxChars ? value.length : maxChars;
  if (end > 0) {
    final unit = value.codeUnitAt(end - 1);
    if (unit >= 0xD800 && unit <= 0xDBFF) end -= 1;
  }
  return value.substring(0, end);
}

/// 雲端 TripReportRequest 只收這三個頂層欄。枚舉不合法就回 null，呼叫方不得送出。
Map<String, Object?>? tripReportWirePayload({
  required String sessionId,
  required String ratingTag,
  required String perkStatusTag,
  required String perkNotes,
  required int tripTimestampSec,
}) {
  if (!AppConfig.tripRatingTags.contains(ratingTag)) return null;
  if (!AppConfig.tripPerkStatusTags.contains(perkStatusTag)) return null;
  return {
    'session_id': sessionId,
    'trip_timestamp': tripTimestampSec,
    'feedback': {
      'rating_tag': ratingTag,
      'perk_status_tag': perkStatusTag,
      'perk_notes': truncatePerkNotes(perkNotes, NativeConfig.TODAY_TRIP_NOTES_MAX_CHARS),
    },
  };
}

enum QueryTriggerMode { backgroundPrefetch, driverActive, b2Resolution }

extension QueryTriggerModeWire on QueryTriggerMode {
  String get wireName => switch (this) {
        QueryTriggerMode.backgroundPrefetch => 'BACKGROUND_PREFETCH',
        QueryTriggerMode.driverActive => 'DRIVER_ACTIVE',
        QueryTriggerMode.b2Resolution => 'B2_RESOLUTION',
      };

  static QueryTriggerMode fromWire(String raw) => switch (raw) {
        'BACKGROUND_PREFETCH' => QueryTriggerMode.backgroundPrefetch,
        'DRIVER_ACTIVE' => QueryTriggerMode.driverActive,
        'B2_RESOLUTION' => QueryTriggerMode.b2Resolution,
        _ => throw ArgumentError('Unknown QueryTriggerMode: $raw'),
      };
}

typedef NetworkEffects = void Function({required bool grey, required bool ready});

class RecommendRequest {
  const RecommendRequest({
    required this.sessionId,
    required this.originLat,
    required this.originLng,
    required this.destLat,
    required this.destLng,
    required this.destName,
    required this.preference,
    this.expandScarcity = false,
    this.speechLocale = NativeConfig.SPEECH_LOCALE_DEFAULT,
    this.destinationEpoch,
    this.sessionStartedAtSec,
  });

  final String sessionId;
  final double originLat;
  final double originLng;
  final double destLat;
  final double destLng;
  final String destName;
  final String preference;
  final bool expandScarcity;
  final String speechLocale;

  /// 合約 v2：本機目的地 epoch（伺服器只會向前推，舊 epoch 回 409 STALE_EPOCH）。
  final int? destinationEpoch;

  /// 合約 v2：呢個 session id 喺本機開始嘅 Unix 秒。伺服器只會為「近期開始」嘅 id 開新 session（防 TTL 後復活）。
  final int? sessionStartedAtSec;
}

/// 泊車確認事件身份（建立嗰刻鎖低）。v2：伺服器要求精確 lock_revision（null＝當時冇鎖）＋destination_epoch。
class ParkedEventIdentity {
  const ParkedEventIdentity({required this.contractVersion, this.lockRevision, this.destinationEpoch});

  static const legacy = ParkedEventIdentity(contractVersion: 1);

  final int contractVersion;
  final int? lockRevision;
  final int? destinationEpoch;
}

class CallOutcome {
  const CallOutcome({
    required this.succeeded,
    required this.httpStatus,
    required this.spokenCues,
    required this.attempts,
    required this.waitIntervalsMs,
    required this.shouldTurnGrey,
    required this.shouldTurnReady,
    this.response,
    this.actionSpokenCue,
    this.bodyJson,
  });

  final bool succeeded;
  final int? httpStatus;
  final List<String> spokenCues;
  final int attempts;
  final List<int> waitIntervalsMs;
  final RecommendResponse? response;
  final String? actionSpokenCue;
  final bool shouldTurnGrey;
  final bool shouldTurnReady;

  /// 原始回應 body（合約 v2 欄位：status、lock_revision、destination_epoch…）。
  final String? bodyJson;

  /// typed 伺服器問題（CAPACITY_*／STALE_*／SESSION_*／CONTENTION／舊後端）；冇就 null。
  ServerIssue? get serverIssue => succeeded ? null : ServerIssue.from(httpStatus, bodyJson);

  Map<String, Object?> get bodyFields {
    final raw = bodyJson;
    if (raw == null || raw.isEmpty) return const {};
    try {
      final decoded = jsonDecode(raw);
      return decoded is Map ? Map<String, Object?>.from(decoded) : const {};
    } catch (_) {
      return const {};
    }
  }
}

class ApiClient {
  ApiClient(this.transport, {Future<void> Function(int ms)? sleeper, this.onNetworkEffects})
      : sleeper = sleeper ?? _defaultSleep;

  final ApiTransport transport;
  final Future<void> Function(int ms) sleeper;
  final NetworkEffects? onNetworkEffects;

  static const String HEADER_API_KEY = 'X-Internal-API-Key';
  static const String INTERNAL_API_KEY = '<INTERNAL_API_KEY>';
  static const String HEADER_CONTENT_TYPE = 'Content-Type';
  static const String JSON_CONTENT_TYPE = 'application/json';
  static const String RECOMMEND = kRecommendPath;
  static const String COMMIT = '/v1/session/commit';
  static const String RELEASE = '/v1/session/release';
  static const String SYNC = '/v1/sync/outbox';
  static const String CROWD = '/v1/feedback/crowd-report';
  static const String TRIP = '/v1/feedback/trip-report';
  static const List<String> ENDPOINTS = [RECOMMEND, COMMIT, RELEASE, SYNC, CROWD, TRIP];

  static const int _httpOk = 200;
  static const int _httpOkMax = 299;
  static const int _httpConflict = 409;
  static const int _httpTooMany = 429;
  static const int _httpServerError = 500;

  static Future<void> _defaultSleep(int ms) {
    if (ms <= 0) return Future<void>.value();
    return Future<void>.delayed(Duration(milliseconds: ms));
  }

  Map<String, String> defaultHeaders() => {
        HEADER_API_KEY: INTERNAL_API_KEY,
        HEADER_CONTENT_TYPE: JSON_CONTENT_TYPE,
      };

  Future<CallOutcome> recommendAndRoute(RecommendRequest request, [QueryTriggerMode mode = QueryTriggerMode.backgroundPrefetch]) {
    return _dispatch(
      RECOMMEND,
      {
        'session_id': request.sessionId,
        'origin_lat': request.originLat,
        'origin_lng': request.originLng,
        'dest_lat': request.destLat,
        'dest_lng': request.destLng,
        'dest_name': request.destName,
        'preference': request.preference,
        'expand_scarcity': request.expandScarcity,
        'speech_locale': request.speechLocale,
        'destination_epoch': ?request.destinationEpoch,
        'session_started_at': ?request.sessionStartedAtSec,
      },
      mode,
      graded: true,
      ready: true,
    );
  }

  Future<CallOutcome> commitSession(String sessionId, String targetCarparkId, {int? destinationEpoch, String? idempotencyKey}) {
    return _dispatch(
      COMMIT,
      {
        'session_id': sessionId,
        'target_carpark_id': targetCarparkId,
        'destination_epoch': ?destinationEpoch,
        if (destinationEpoch != null && idempotencyKey != null) 'idempotency_key': idempotencyKey,
      },
      null,
      graded: true,
      ready: false,
      conflictCue: true,
    );
  }

  static const String RELEASE_PARKED = 'PARKED_CONFIRMED';
  static const String RELEASE_USER_ABORTED = 'USER_ABORTED';
  static const String RELEASE_DESTINATION_CHANGED = 'DESTINATION_CHANGED';

  Future<CallOutcome> releaseSession(
    String sessionId,
    String targetCarparkId, {
    bool isOnline = true,
    String reason = RELEASE_PARKED,
    int? lockRevision,
    int? newDestinationEpoch,
  }) {
    if (!isOnline) {
      return Future.value(_outcome(succeeded: false, httpStatus: null, attempts: 0));
    }
    return _dispatch(
      RELEASE,
      {
        'session_id': sessionId,
        'target_carpark_id': targetCarparkId,
        'reason': reason,
        'lock_revision': ?lockRevision,
        'new_destination_epoch': ?newDestinationEpoch,
      },
      null,
      graded: false,
      ready: false,
    );
  }

  Future<CallOutcome> syncDriverConfirmed(String sessionId, int timestampSec, {ParkedEventIdentity identity = ParkedEventIdentity.legacy}) {
    return _dispatch(
      SYNC,
      {
        'session_id': sessionId,
        'event_type': 'DRIVER_CONFIRMED',
        'timestamp': timestampSec,
        if (identity.contractVersion >= 2) 'contract_version': identity.contractVersion,
        if (identity.contractVersion >= 2) 'destination_epoch': identity.destinationEpoch ?? 0,
        'lock_revision': ?identity.lockRevision,
      },
      null,
      graded: true,
      ready: false,
    );
  }

  Future<CallOutcome> submitCrowdReport(String sessionId, String carparkId, String reportType, int clientTimestampSec) {
    return _dispatch(
      CROWD,
      {
        'session_id': sessionId,
        'carpark_id': carparkId,
        'report_type': reportType,
        'client_timestamp': clientTimestampSec,
      },
      null,
      graded: true,
      ready: false,
    );
  }

  Future<CallOutcome> submitTripReport({
    required String sessionId,
    required String carparkId,
    required String ratingTag,
    required String perkStatusTag,
    String perkNotes = '',
    required int tripTimestampSec,
    String? photoUrl,
  }) {
    // carparkId 只留喺呼叫簽名。雲端 trip-report 唔核對車場，所以唔放入 body。
    // photoUrl 本版冇呼叫方傳入。有值就拒絕送出，避免靜默丟掉未定義欄位。
    if (photoUrl != null) return Future.value(_localReject());
    final payload = tripReportWirePayload(
      sessionId: sessionId,
      ratingTag: ratingTag,
      perkStatusTag: perkStatusTag,
      perkNotes: perkNotes,
      tripTimestampSec: tripTimestampSec,
    );
    if (payload == null) return Future.value(_localReject());
    return _dispatch(TRIP, payload, null, graded: true, ready: false, silentConflict: true);
  }

  CallOutcome _localReject() => const CallOutcome(
        succeeded: false,
        httpStatus: null,
        spokenCues: [],
        attempts: 0,
        waitIntervalsMs: [],
        shouldTurnGrey: false,
        shouldTurnReady: false,
      );

  Future<CallOutcome> _dispatch(
    String endpoint,
    Map<String, Object?> payload,
    QueryTriggerMode? mode, {
    required bool graded,
    required bool ready,
    bool conflictCue = false,
    bool silentConflict = false,
  }) async {
    final cues = <String>[];
    final waits = <int>[];
    var turnedGrey = false;
    var turnedReady = false;
    final body = jsonEncode(payload);
    var attempts = 0;
    while (true) {
      attempts += 1;
      final result = await transport.post(endpoint, body);
      if (endpoint == RECOMMEND) _logRecommend(mode, result);
      if (result.statusCode == _httpTooMany) {
        return _finishRateLimit(
          endpoint,
          body,
          mode,
          ready,
          conflictCue,
          silentConflict,
          cues,
          waits,
          attempts,
          result,
          turnedGrey,
          turnedReady,
        );
      }
      if (_isConflict(result, conflictCue, silentConflict)) {
        return _conflictOutcome(result, conflictCue, cues, waits, attempts, turnedGrey, turnedReady);
      }
      if (_isOk(result)) {
        if (ready) turnedReady = true;
        return _success(result, cues, waits, attempts, turnedGrey, turnedReady);
      }
      // typed 合約回應：唔當網絡失敗（唔變灰、唔講網絡斷）；只有 CONTENTION 喺 graded 上限內用同一 body 重試。
      final issue = ServerIssue.from(result.statusCode, result.bodyJson);
      if (issue != null && !(issue.retryable && graded && attempts < NativeConfig.API_MAX_ATTEMPTS)) {
        return _outcome(
          succeeded: false,
          httpStatus: result.statusCode,
          cues: cues,
          waits: waits,
          attempts: attempts,
          response: null,
          shouldTurnGrey: turnedGrey,
          shouldTurnReady: false,
          bodyJson: result.bodyJson,
        );
      }
      if (attempts == 1 && (result.timedOut || result.networkError)) {
        turnedGrey = true;
        if (mode == QueryTriggerMode.driverActive) cues.add(ZhHkTemplates.NETWORK_SLOW);
      }
      if (!(graded && attempts < NativeConfig.API_MAX_ATTEMPTS && _isGradedFailure(result))) {
        return _fail(result, mode, cues, waits, attempts, turnedGrey);
      }
      await sleeper(NativeConfig.API_RETRY_INTERVAL_MS);
      waits.add(NativeConfig.API_RETRY_INTERVAL_MS);
    }
  }

  Future<CallOutcome> _finishRateLimit(
    String endpoint,
    String body,
    QueryTriggerMode? mode,
    bool ready,
    bool conflictCue,
    bool silentConflict,
    List<String> cues,
    List<int> waits,
    int attempts,
    HttpResult limited,
    bool turnedGrey,
    bool turnedReady,
  ) async {
    final waitMs = _rateLimitWaitMs(limited.resolvedRetryAfterSeconds);
    await sleeper(waitMs);
    waits.add(waitMs);
    final total = attempts + 1;
    final retry = await transport.post(endpoint, body);
    if (endpoint == RECOMMEND) _logRecommend(mode, retry);
    if (_isConflict(retry, conflictCue, silentConflict)) {
      return _conflictOutcome(retry, conflictCue, cues, waits, total, turnedGrey, turnedReady);
    }
    if (_isOk(retry)) {
      if (ready) turnedReady = true;
      return _success(retry, cues, waits, total, turnedGrey, turnedReady);
    }
    return _fail(retry, mode, cues, waits, total, turnedGrey);
  }

  /// 一行診斷：觸發來源、解析結果、卡數。唔寫 body、URL 或 key。
  void _logRecommend(QueryTriggerMode? mode, HttpResult result) {
    final parsed = result.recommendResponse;
    final cards = parsed?.cards.length ?? 0;
    final parse = parsed != null
        ? (cards == 0 ? 'empty' : 'ok')
        : result.timedOut
            ? 'timeout'
            : result.networkError && result.bodyJson.isNotEmpty
                ? 'invalid'
                : result.networkError
                    ? 'network'
                    : 'http${result.statusCode}';
    debugPrint('HKP_RECOMMEND trigger=${mode?.wireName ?? 'none'} parse=$parse cards=$cards');
  }

  CallOutcome _fail(HttpResult result, QueryTriggerMode? mode, List<String> cues, List<int> waits, int attempts, bool turnedGrey) {
    turnedGrey = true;
    if (mode == QueryTriggerMode.driverActive || mode == QueryTriggerMode.b2Resolution) {
      cues.add(ZhHkTemplates.NETWORK_FAILED);
    }
    return _outcome(
      succeeded: false,
      httpStatus: result.statusCode,
      cues: cues,
      waits: waits,
      attempts: attempts,
      response: _sanitize(result.recommendResponse),
      shouldTurnGrey: turnedGrey,
      shouldTurnReady: false,
      bodyJson: result.bodyJson,
    );
  }

  CallOutcome _success(HttpResult result, List<String> cues, List<int> waits, int attempts, bool turnedGrey, bool turnedReady) {
    return _outcome(
      succeeded: true,
      httpStatus: result.statusCode,
      cues: cues,
      waits: waits,
      attempts: attempts,
      response: _sanitize(result.recommendResponse),
      shouldTurnGrey: turnedGrey,
      shouldTurnReady: turnedReady,
      bodyJson: result.bodyJson,
    );
  }

  CallOutcome _conflictOutcome(
    HttpResult result,
    bool conflictCue,
    List<String> cues,
    List<int> waits,
    int attempts,
    bool turnedGrey,
    bool turnedReady,
  ) {
    return _outcome(
      succeeded: false,
      httpStatus: result.statusCode,
      cues: cues,
      waits: waits,
      attempts: attempts,
      actionSpokenCue: conflictCue ? result.spokenCue : null,
      shouldTurnGrey: turnedGrey,
      shouldTurnReady: turnedReady,
      bodyJson: result.bodyJson,
    );
  }

  CallOutcome _outcome({
    required bool succeeded,
    required int? httpStatus,
    List<String> cues = const [],
    List<int> waits = const [],
    required int attempts,
    RecommendResponse? response,
    String? actionSpokenCue,
    bool shouldTurnGrey = false,
    bool shouldTurnReady = false,
    String? bodyJson,
  }) {
    onNetworkEffects?.call(grey: shouldTurnGrey, ready: shouldTurnReady);
    return CallOutcome(
      succeeded: succeeded,
      httpStatus: httpStatus,
      spokenCues: List<String>.from(cues),
      attempts: attempts,
      waitIntervalsMs: List<int>.from(waits),
      response: response,
      actionSpokenCue: actionSpokenCue,
      shouldTurnGrey: shouldTurnGrey,
      shouldTurnReady: shouldTurnReady,
      bodyJson: bodyJson,
    );
  }

  int _rateLimitWaitMs(int? retryAfterSeconds) {
    if (retryAfterSeconds != null) {
      final seconds = retryAfterSeconds.clamp(NativeConfig.RATE_LIMIT_RETRY_AFTER_MIN_S, NativeConfig.RATE_LIMIT_RETRY_AFTER_MAX_S).toInt();
      return seconds * 1000;
    }
    return NativeConfig.RATE_LIMIT_RETRY_DELAY_MS;
  }

  RecommendResponse? _sanitize(RecommendResponse? response) {
    if (response == null) return null;
    return response.copyWithCards(response.sanitizedCards());
  }

  bool _isOk(HttpResult result) =>
      result.statusCode >= _httpOk && result.statusCode <= _httpOkMax && !result.timedOut && !result.networkError;

  bool _isGradedFailure(HttpResult result) => result.timedOut || result.networkError || result.statusCode >= _httpServerError;

  bool _isConflict(HttpResult result, bool conflictCue, bool silentConflict) =>
      result.statusCode == _httpConflict && (conflictCue || silentConflict);
}
