// ignore_for_file: constant_identifier_names

import 'dart:convert';

import '../constants/native_config.dart';
import '../voice/zh_hk_templates.dart';
import 'api_responses.dart';
import 'api_transport.dart';
import 'http_result.dart';
import 'recommend_response.dart';

enum QueryTriggerMode { backgroundPrefetch, driverActive, b2Resolution }

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
      },
      mode,
      graded: true,
      ready: true,
    );
  }

  Future<CallOutcome> commitSession(String sessionId, String targetCarparkId) {
    return _dispatch(
      COMMIT,
      {'session_id': sessionId, 'target_carpark_id': targetCarparkId},
      null,
      graded: true,
      ready: false,
      conflictCue: true,
    );
  }

  Future<CallOutcome> releaseSession(String sessionId, String targetCarparkId, {bool isOnline = true}) {
    if (!isOnline) {
      return Future.value(_outcome(succeeded: false, httpStatus: null, attempts: 0));
    }
    return _dispatch(
      RELEASE,
      {'session_id': sessionId, 'target_carpark_id': targetCarparkId, 'reason': 'PARKED_CONFIRMED'},
      null,
      graded: false,
      ready: false,
    );
  }

  Future<CallOutcome> syncDriverConfirmed(String sessionId, int timestampSec) {
    return _dispatch(
      SYNC,
      {'session_id': sessionId, 'event_type': 'DRIVER_CONFIRMED', 'timestamp': timestampSec},
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
    final payload = <String, Object?>{
      'carpark_id': carparkId,
      'rating_tag': ratingTag,
      'perk_status_tag': perkStatusTag,
      'perk_notes': perkNotes,
      'session_id': sessionId,
      'trip_timestamp': tripTimestampSec,
    };
    if (photoUrl != null) payload['photo_url'] = photoUrl;
    return _dispatch(TRIP, payload, null, graded: true, ready: false, silentConflict: true);
  }

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
    if (_isConflict(retry, conflictCue, silentConflict)) {
      return _conflictOutcome(retry, conflictCue, cues, waits, total, turnedGrey, turnedReady);
    }
    if (_isOk(retry)) {
      if (ready) turnedReady = true;
      return _success(retry, cues, waits, total, turnedGrey, turnedReady);
    }
    return _fail(retry, mode, cues, waits, total, turnedGrey);
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
