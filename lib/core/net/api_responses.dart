import 'dart:convert';

import '../constants/native_config.dart';
import 'http_result.dart';
import 'recommend_response.dart';
import 'recommendation_card.dart';

const String kRecommendPath = '/v1/recommend-and-route';

class ApiResponses {
  static RecommendResponse? parseRecommend(String body) {
    final root = _objectOrNull(body);
    if (root == null) return null;
    final status = root['status'];
    if (status is! String || status.trim().isEmpty) return null;
    final cardsRaw = root['cards'];
    if (cardsRaw != null && cardsRaw is! List) return null;
    final cardItems = cardsRaw is List ? cardsRaw : const <Object?>[];
    if (cardItems.any((item) => item is! Map)) return null;
    final topCue = _nonBlankString(root['spoken_cue']);
    final cards = cardItems.map((item) {
      final card = Map<String, Object?>.from(item as Map);
      final gateLat = _coordOrNull(card['gate_lat'] ?? card['lat']);
      final gateLng = _coordOrNull(card['gate_lng'] ?? card['lng']);
      final hasGate = gateLat != null && gateLng != null;
      final vacancies = _intOr(card['vacancies'], -1);
      final isStale = card['is_stale'] == true;
      final rawAvailability = card['availability'];
      final typed =
          rawAvailability is String &&
              CardAvailability.viable.contains(rawAvailability) ||
          rawAvailability == CardAvailability.lastKnownZero ||
          rawAvailability == CardAvailability.noAvailability;
      // 未知／缺欄 availability：保守推斷，唔會靜靜當有位。
      final availability = typed
          ? rawAvailability as String
          : CardAvailability.deriveLegacy(
              vacancies: vacancies,
              isStale: isStale,
            );
      final rawCommit = card['commit_allowed'];
      final commitAllowed =
          (rawCommit is bool ? rawCommit : true) &&
          CardAvailability.committable.contains(availability);
      return RecommendationCard(
        carparkId: _stringOr(card['carpark_id'], ''),
        name: _stringOr(card['name'], ''),
        type: _stringOr(card['type'], ''),
        vacancies: vacancies,
        distanceMeters: _intOr(card['distance_meters'], 0),
        feeSummary: _stringOr(card['fee_summary'], ''),
        spokenCue: _nonBlankString(card['spoken_cue']) ?? topCue ?? '',
        isStale: isStale,
        gateLat: hasGate ? gateLat : null,
        gateLng: hasGate ? gateLng : null,
        availability: availability,
        commitAllowed: commitAllowed,
        isFallback: card['is_fallback'] == true,
        legacyContract: !typed || rawCommit is! bool,
      );
    }).toList();
    return RecommendResponse(
      status: status,
      speechLocale: _stringOr(
        root['speech_locale'],
        NativeConfig.SPEECH_LOCALE_DEFAULT,
      ),
      speechLocaleFallback: root['speech_locale_fallback'] == true,
      sourceStatus: _stringOr(root['source_status'], 'ok'),
      sourceSnapshotTs: _stringOr(root['source_snapshot_ts'], ''),
      processedAt: _stringOr(root['processed_at'], ''),
      serverNow: _stringOr(root['server_now'], ''),
      dataAgeSeconds: _intOr(root['data_age_seconds'], 0),
      cards: cards,
      awaitVoiceReply: root['await_voice_reply'] == true,
      spokenCue: topCue,
    );
  }

  static HttpResult buildResult(
    String path,
    int statusCode,
    String body,
    Map<String, String> headers,
  ) {
    if (path == kRecommendPath && statusCode >= 200 && statusCode <= 299) {
      final parsed = parseRecommend(body);
      if (parsed == null) {
        return HttpResult(
          statusCode: 0,
          bodyJson: body,
          headers: headers,
          networkError: true,
        );
      }
      final topCue = _nonBlankString(_objectOrNull(body)?['spoken_cue']);
      // 讀實際首選（第一張 viable），唔係 raw cards[0]；全部係參考卡 → 用頂層提示（擴大範圍）。
      final lead = parsed.cards.where((card) => card.isViable).firstOrNull;
      final firstCue = lead?.spokenCue;
      final spokenCue = (firstCue != null && firstCue.trim().isNotEmpty)
          ? firstCue
          : topCue;
      return HttpResult(
        statusCode: statusCode,
        bodyJson: body,
        headers: headers,
        recommendResponse: parsed,
        spokenCue: spokenCue,
      );
    }
    if (statusCode == 409) {
      return HttpResult(
        statusCode: statusCode,
        bodyJson: body,
        headers: headers,
        spokenCue: _nonBlankString(_objectOrNull(body)?['spoken_cue']),
      );
    }
    if (statusCode == 429) {
      return HttpResult(
        statusCode: statusCode,
        bodyJson: body,
        headers: headers,
        retryAfterSeconds:
            _headerRetrySeconds(headers) ?? _bodyRetrySeconds(body),
      );
    }
    return HttpResult(statusCode: statusCode, bodyJson: body, headers: headers);
  }

  static Map<String, Object?>? _objectOrNull(String text) {
    try {
      final decoded = jsonDecode(text);
      if (decoded is Map) return Map<String, Object?>.from(decoded);
      return null;
    } catch (_) {
      return null;
    }
  }

  static int? _headerRetrySeconds(Map<String, String> headers) {
    String? raw;
    for (final entry in headers.entries) {
      if (entry.key.toLowerCase() == 'retry-after') {
        raw = entry.value;
        break;
      }
    }
    if (raw == null) return null;
    return int.tryParse(raw.trim());
  }

  static int? _bodyRetrySeconds(String body) {
    final value = _objectOrNull(body)?['retry_after_seconds'];
    if (value is int) return value;
    if (value is double && value.isFinite && value % 1 == 0) return value.toInt();
    return null;
  }

  static String? _nonBlankString(Object? value) {
    if (value is! String || value.trim().isEmpty) return null;
    return value;
  }

  static String _stringOr(Object? value, String fallback) =>
      value is String ? value : fallback;

  static double? _coordOrNull(Object? value) {
    if (value is! num || !value.isFinite) return null;
    return value.toDouble();
  }

  static int _intOr(Object? value, int fallback) {
    if (value is int) {
      if (value < -2147483648 || value > 2147483647) return fallback;
      return value;
    }
    if (value is double && value.isFinite && value % 1 == 0) {
      final asInt = value.toInt();
      if (asInt < -2147483648 || asInt > 2147483647) return fallback;
      return asInt;
    }
    return fallback;
  }
}
