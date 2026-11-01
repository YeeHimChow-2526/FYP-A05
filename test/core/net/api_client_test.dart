import 'package:copilot/core/constants/native_config.dart';
import 'package:copilot/core/net/api_client.dart';
import 'package:copilot/core/net/fake_api_transport.dart';
import 'package:copilot/core/net/http_result.dart';
import 'package:copilot/core/net/recommend_response.dart';
import 'package:copilot/core/net/recommendation_card.dart';
import 'package:copilot/core/voice/zh_hk_templates.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  RecommendRequest sample() => const RecommendRequest(
        sessionId: 'sess',
        originLat: 22.3,
        originLng: 114.1,
        destLat: 22.31,
        destLng: 114.17,
        destName: '朗豪坊',
        preference: 'SMART_HYBRID',
      );

  HttpResult ok(RecommendResponse response) => HttpResult(statusCode: 200, recommendResponse: response);

  RecommendResponse meter(String sourceStatus, int vacancies, bool isStale, String spokenCue) {
    return RecommendResponse(
      status: 'SUCCESS',
      speechLocale: NativeConfig.SPEECH_LOCALE_DEFAULT,
      sourceStatus: sourceStatus,
      dataAgeSeconds: 90,
      cards: [
        RecommendationCard(
          carparkId: 'M1',
          name: '錶',
          type: 'METER',
          vacancies: vacancies,
          distanceMeters: 40,
          feeSummary: '',
          spokenCue: spokenCue,
          isStale: isStale,
        ),
        const RecommendationCard(
          carparkId: 'CP1',
          name: '室內',
          type: 'INDOOR',
          vacancies: 4,
          distanceMeters: 80,
          feeSummary: '',
          spokenCue: '室內剩四個位',
          isStale: false,
        ),
      ],
    );
  }

  Future<void> Function(int) noSleep(List<int> waits) {
    return (ms) async {
      waits.add(ms);
    };
  }

  test('recommend success sanitizes degraded meter and marks ready', () async {
    final transport = FakeApiTransport([ok(meter('degraded', 6, false, '咪錶剩6個位'))]);
    final flags = <({bool grey, bool ready})>[];
    final client = ApiClient(transport, sleeper: noSleep([]), onNetworkEffects: ({required grey, required ready}) {
      flags.add((grey: grey, ready: ready));
    });
    final outcome = await client.recommendAndRoute(sample());
    expect(transport.calls.single.path, ApiClient.RECOMMEND);
    expect(transport.calls.single.body, contains('"speech_locale":"zh-HK"'));
    expect(outcome.succeeded, isTrue);
    expect(outcome.shouldTurnReady, isTrue);
    expect(outcome.shouldTurnGrey, isFalse);
    expect(outcome.response!.dataAgeSeconds, 90);
    expect(outcome.response!.cards.singleWhere((card) => card.type == 'METER').vacancies, -1);
    expect(outcome.response!.cards.singleWhere((card) => card.type == 'METER').spokenCue, ZhHkTemplates.METER_STALE_SPOKEN);
    expect(outcome.response!.cards.singleWhere((card) => card.type == 'INDOOR').vacancies, 4);
    expect(flags.single.ready, isTrue);
  });

  test('background timeout is silent grey; driver and b2 speak', () async {
    final waits = <int>[];
    final transport = FakeApiTransport(List.filled(NativeConfig.API_MAX_ATTEMPTS, const HttpResult(timedOut: true, statusCode: 0)));
    final client = ApiClient(transport, sleeper: noSleep(waits));
    final background = await client.recommendAndRoute(sample());
    expect(background.succeeded, isFalse);
    expect(background.shouldTurnGrey, isTrue);
    expect(background.shouldTurnReady, isFalse);
    expect(background.attempts, NativeConfig.API_MAX_ATTEMPTS);
    expect(background.waitIntervalsMs, List.filled(NativeConfig.API_MAX_ATTEMPTS - 1, NativeConfig.API_RETRY_INTERVAL_MS));
    expect(background.spokenCues, isEmpty);
    expect(waits, background.waitIntervalsMs);

    final driverTransport = FakeApiTransport(List.filled(NativeConfig.API_MAX_ATTEMPTS, const HttpResult(timedOut: true, statusCode: 0)));
    final driver = await ApiClient(driverTransport, sleeper: noSleep([])).recommendAndRoute(sample(), QueryTriggerMode.driverActive);
    expect(driver.spokenCues, [ZhHkTemplates.NETWORK_SLOW, ZhHkTemplates.NETWORK_FAILED]);
    expect(driver.shouldTurnGrey, isTrue);

    final b2Transport = FakeApiTransport(List.filled(NativeConfig.API_MAX_ATTEMPTS, const HttpResult(networkError: true, statusCode: 0)));
    final b2 = await ApiClient(b2Transport, sleeper: noSleep([])).recommendAndRoute(sample(), QueryTriggerMode.b2Resolution);
    expect(b2.spokenCues, [ZhHkTemplates.NETWORK_FAILED]);
    expect(b2.shouldTurnGrey, isTrue);
    expect(b2.attempts, NativeConfig.API_MAX_ATTEMPTS);
  });

  test('429 is separate from graded retries', () async {
    final noHeader = FakeApiTransport([
      const HttpResult(statusCode: 429),
      ok(meter('ok', 3, false, '咪錶剩3個位')),
    ]);
    final first = await ApiClient(noHeader, sleeper: noSleep([])).recommendAndRoute(sample(), QueryTriggerMode.driverActive);
    expect(first.attempts, 2);
    expect(first.waitIntervalsMs, [NativeConfig.RATE_LIMIT_RETRY_DELAY_MS]);
    expect(first.shouldTurnReady, isTrue);
    expect(first.shouldTurnGrey, isFalse);
    expect(first.spokenCues, isEmpty);

    final capped = FakeApiTransport([
      const HttpResult(statusCode: 429, retryAfterSeconds: 30),
      const HttpResult(statusCode: 429, retryAfterSeconds: 1),
    ]);
    final failed = await ApiClient(capped, sleeper: noSleep([])).recommendAndRoute(sample(), QueryTriggerMode.driverActive);
    expect(failed.attempts, 2);
    expect(failed.waitIntervalsMs, [NativeConfig.RATE_LIMIT_MAX_WAIT_MS]);
    expect(failed.shouldTurnGrey, isTrue);
    expect(failed.shouldTurnReady, isFalse);
    expect(failed.spokenCues, [ZhHkTemplates.NETWORK_FAILED]);
  });

  test('timeout then 429 recovers or fails once', () async {
    final recoveredTransport = FakeApiTransport([
      const HttpResult(timedOut: true, statusCode: 0),
      const HttpResult(statusCode: 429),
      ok(meter('ok', 2, false, '咪錶剩2個位')),
    ]);
    final recovered = await ApiClient(recoveredTransport, sleeper: noSleep([])).recommendAndRoute(sample(), QueryTriggerMode.driverActive);
    expect(recovered.attempts, 3);
    expect(recovered.waitIntervalsMs, [NativeConfig.API_RETRY_INTERVAL_MS, NativeConfig.RATE_LIMIT_RETRY_DELAY_MS]);
    expect(recovered.shouldTurnReady, isTrue);
    expect(recovered.shouldTurnGrey, isTrue);
    expect(recovered.spokenCues, [ZhHkTemplates.NETWORK_SLOW]);

    final failedTransport = FakeApiTransport([
      const HttpResult(timedOut: true, statusCode: 0),
      const HttpResult(statusCode: 429, headers: {'Retry-After': '30'}),
      const HttpResult(statusCode: 429),
    ]);
    final failed = await ApiClient(failedTransport, sleeper: noSleep([])).recommendAndRoute(sample(), QueryTriggerMode.driverActive);
    expect(failed.attempts, 3);
    expect(failed.waitIntervalsMs, [NativeConfig.API_RETRY_INTERVAL_MS, NativeConfig.RATE_LIMIT_MAX_WAIT_MS]);
    expect(failed.shouldTurnGrey, isTrue);
    expect(failed.spokenCues.where((cue) => cue == ZhHkTemplates.NETWORK_FAILED), hasLength(1));
  });

  test('commit conflict, offline release, and silent trip conflict', () async {
    final transport = FakeApiTransport([
      const HttpResult(statusCode: 409, spokenCue: '該區已滿'),
      const HttpResult(statusCode: 409, spokenCue: '未確認'),
    ]);
    final client = ApiClient(transport, sleeper: noSleep([]));
    final commit = await client.commitSession('sess', 'CP1');
    expect(commit.succeeded, isFalse);
    expect(commit.httpStatus, 409);
    expect(commit.actionSpokenCue, '該區已滿');
    expect(commit.spokenCues, isEmpty);
    expect(commit.shouldTurnGrey, isFalse);
    expect(transport.calls.single.path, ApiClient.COMMIT);

    final release = await client.releaseSession('sess', 'CP1', isOnline: false);
    expect(release.succeeded, isFalse);
    expect(release.attempts, 0);
    expect(release.httpStatus, isNull);
    expect(transport.calls, hasLength(1));

    final trip = await client.submitTripReport(sessionId: 'sess', carparkId: 'CP1', ratingTag: 'GOOD', perkStatusTag: 'VALID', tripTimestampSec: 1);
    expect(trip.spokenCues, isEmpty);
    expect(trip.actionSpokenCue, isNull);
    expect(transport.calls.last.path, ApiClient.TRIP);
    expect(transport.calls.last.body.contains('photo_url'), isFalse);
  });
}
