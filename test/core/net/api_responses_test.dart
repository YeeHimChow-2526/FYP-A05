import 'dart:io';

import 'package:copilot/core/constants/native_config.dart';
import 'package:copilot/core/net/api_client.dart';
import 'package:copilot/core/net/api_responses.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  String readMock(String name) => File('android/app/src/debug/assets/mock/$name').readAsStringSync();

  test('docs api and mock recommend fixtures parse', () {
    final openApi = File('docs/api/recommend-and-route.response.200.json').readAsStringSync();
    final parsed = ApiResponses.parseRecommend(openApi)!;
    expect(parsed.status, 'SUCCESS');
    expect(parsed.cards.single.carparkId, 'CP_LANGHAM');
    expect(parsed.dataAgeSeconds, 90);

    final langham = ApiResponses.parseRecommend(readMock('recommend_success_langham.json'))!;
    expect(langham.speechLocale, NativeConfig.SPEECH_LOCALE_DEFAULT);
    expect(langham.cards.single.vacancies, 12);
    expect(langham.cards.single.spokenCue, '前方首選朗豪坊停車場，一百八十米，剩十二個位，首小時三十二蚊。');
    final stale = ApiResponses.parseRecommend(readMock('recommend_meter_stale.json'))!;
    expect(stale.cards.single.vacancies, -1);
    expect(stale.cards.single.isStale, isTrue);
    expect(ApiResponses.parseRecommend(readMock('recommend_source_degraded.json'))!.sourceStatus, 'degraded');
    expect(ApiResponses.parseRecommend(readMock('recommend_scarcity_await_reply.json'))!.awaitVoiceReply, isTrue);
  });

  test('defaults, cue fallback, and int rules', () {
    final parsed = ApiResponses.parseRecommend('{"status":"SUCCESS","cards":[{"carpark_id":"X"}]}')!;
    expect(parsed.speechLocale, NativeConfig.SPEECH_LOCALE_DEFAULT);
    expect(parsed.sourceStatus, 'ok');
    expect(parsed.cards.single.vacancies, -1);
    expect(parsed.cards.single.spokenCue, '');
    expect(ApiResponses.parseRecommend('{"status":"SUCCESS","await_voice_reply":"true","cards":[]}')!.awaitVoiceReply, isFalse);
    expect(ApiResponses.parseRecommend('{"status":"SUCCESS","cards":[{"vacancies":3.0}]}')!.cards.single.vacancies, 3);
    expect(ApiResponses.parseRecommend('{"status":"SUCCESS","cards":[{"vacancies":3.5}]}')!.cards.single.vacancies, -1);

    const body = '{"status":"SUCCESS","spoken_cue":"T","cards":[{"carpark_id":"C1","spoken_cue":""},{"carpark_id":"C2","spoken_cue":"C2"}]}';
    final cues = ApiResponses.parseRecommend(body)!;
    expect(cues.cards[0].spokenCue, 'T');
    expect(cues.cards[1].spokenCue, 'C2');
    expect(ApiResponses.buildResult(ApiClient.RECOMMEND, 200, body, const {}).spokenCue, 'T');
  });

  test('invalid bodies and status mapping', () {
    expect(ApiResponses.parseRecommend(''), isNull);
    expect(ApiResponses.parseRecommend('<html>'), isNull);
    expect(ApiResponses.parseRecommend('{}'), isNull);
    expect(ApiResponses.parseRecommend('{"status":1}'), isNull);
    expect(ApiResponses.parseRecommend('{"status":"SUCCESS","cards":{}}'), isNull);
    final html = '<html><body>Login</body></html>';
    final failed = ApiResponses.buildResult(ApiClient.RECOMMEND, 200, html, const {});
    expect(failed.statusCode, 0);
    expect(failed.networkError, isTrue);
    expect(failed.bodyJson, html);
    final locked = ApiResponses.buildResult(ApiClient.COMMIT, 409, readMock('commit_lock_full_409.json'), const {});
    expect(locked.spokenCue, '呢度滿咗');
    expect(ApiResponses.buildResult(ApiClient.RECOMMEND, 429, '{"retry_after_seconds":5}', const {'Retry-After': '7'}).retryAfterSeconds, 7);
    expect(ApiResponses.buildResult(ApiClient.RECOMMEND, 429, '{"retry_after_seconds":5}', const {'retry-after': '7'}).retryAfterSeconds, 7);
    expect(ApiResponses.buildResult(ApiClient.RECOMMEND, 429, '{"retry_after_seconds":-3}', const {}).retryAfterSeconds, -3);
    expect(ApiResponses.buildResult(ApiClient.RECOMMEND, 429, '{"retry_after_seconds":5}', const {'Retry-After': 'Wed, 21 Oct 2015 07:28:00 GMT'}).retryAfterSeconds, 5);
  });

  test('meter sanitize lives on the response', () {
    final degraded = ApiResponses.parseRecommend(readMock('recommend_source_degraded.json'))!;
    final meter = degraded.sanitizedCards().where((card) => card.type == 'METER');
    if (meter.isNotEmpty) {
      expect(meter.single.vacancies, -1);
    }
  });
}
