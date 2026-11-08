import 'dart:io';

import 'package:copilot/core/net/api_client.dart';
import 'package:copilot/core/net/api_responses.dart';
import 'package:copilot/core/net/channel_fixture_transport.dart';
import 'package:copilot/core/trip/trip_ports.dart';
import 'package:copilot/core/voice/zh_hk_templates.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';

import '../trip/trip_controller_test.dart';

String _mock(String name) => File('android/app/src/debug/assets/mock/$name').readAsStringSync();

ChannelFixtureTransport _channel(Map<String, Object?> payload) {
  return ChannelFixtureTransport((path, body) async => payload);
}

void main() {
  final logs = <String>[];

  setUp(() {
    logs.clear();
    final previous = debugPrint;
    debugPrint = (String? message, {int? wrapWidth}) {
      if (message != null) logs.add(message);
    };
    addTearDown(() => debugPrint = previous);
  });

  test('fixture 200 with cards parses through the existing rules', () async {
    final body = _mock('recommend_success_langham.json');
    final transport = _channel({'statusCode': 200, 'body': body});
    final result = await transport.post(ApiClient.RECOMMEND, '{}');
    expect(result.networkError, isFalse);
    expect(result.recommendResponse!.cards.single.carparkId, 'CP_LANGHAM');
    expect(result.recommendResponse!.cards.single.isViable, isTrue);
    expect(result.spokenCue, '前方首選朗豪坊停車場，一百八十米，剩十二個位，首小時三十二蚊。');
  });

  test('fixture empty cards stay a successful empty result', () async {
    final transport = _channel({'statusCode': 200, 'body': '{"status":"SUCCESS","cards":[]}'});
    final client = ApiClient(transport, sleeper: (_) async {});
    final outcome = await client.recommendAndRoute(
      _request(),
      QueryTriggerMode.backgroundPrefetch,
    );
    expect(outcome.succeeded, isTrue);
    expect(outcome.response!.cards, isEmpty);
    expect(logs, ['HKP_RECOMMEND trigger=BACKGROUND_PREFETCH parse=empty cards=0']);
  });

  test('malformed fixture is not a successful zero-card result', () async {
    final transport = _channel({'statusCode': 200, 'body': '{"status":1}'});
    final client = ApiClient(transport, sleeper: (_) async {});
    final outcome = await client.recommendAndRoute(_request(), QueryTriggerMode.driverActive);
    expect(ApiResponses.parseRecommend('{"status":1}'), isNull);
    expect(outcome.succeeded, isFalse);
    expect(outcome.response, isNull);
    expect(outcome.shouldTurnGrey, isTrue);
    expect(outcome.spokenCues, contains(ZhHkTemplates.NETWORK_FAILED));
    expect(logs.where((line) => line.contains('parse=invalid cards=0')), isNotEmpty);
    expect(logs.where((line) => line.contains('parse=empty')), isEmpty);
    expect(logs.where((line) => line.contains('parse=ok')), isEmpty);
  });

  test('channel timeout is left unparsed', () async {
    final transport = _channel({'statusCode': 0, 'body': '', 'timedOut': true});
    final result = await transport.post(ApiClient.RECOMMEND, '{}');
    expect(result.timedOut, isTrue);
    expect(result.recommendResponse, isNull);
  });

  test('1 km prefetch and P both receive the parsed fixture card', () async {
    final transport = _channel({'statusCode': 200, 'body': _mock('recommend_success_langham.json')});
    final platform = FakePlatform();
    final controller = await boot(transport, platform, ScriptDelay());
    controller.tsm.applyVoiceCapability(speakingMode, true);
    controller.lockDestinationAt('朗豪坊', destLat, destLng);
    controller.onFix(LocFix(
      lat: destLat + delta1000 * 0.4,
      lng: destLng,
      accuracyM: 10,
      speedKmh: 20,
      timestampMs: 1000,
    ));
    await Future<void>.delayed(const Duration(milliseconds: 1));
    expect(controller.cards.single.carparkId, 'CP_LANGHAM');
    expect(platform.spoken, isEmpty);
    await controller.requestRecommendation();
    expect(controller.cards.single.carparkId, 'CP_LANGHAM');
    expect(platform.spoken, ['前方首選朗豪坊停車場，一百八十米，剩十二個位，首小時三十二蚊。']);
    expect(logs, contains('HKP_RECOMMEND trigger=BACKGROUND_PREFETCH parse=ok cards=1'));
    expect(logs, contains('HKP_RECOMMEND trigger=DRIVER_ACTIVE parse=ok cards=1'));
  });
}

RecommendRequest _request() {
  return const RecommendRequest(
    sessionId: 'sess_test',
    originLat: 22.3,
    originLng: 114.1,
    destLat: 22.318,
    destLng: 114.1686,
    destName: '朗豪坊（DEMO）',
    preference: 'SMART_HYBRID',
    expandScarcity: false,
  );
}
