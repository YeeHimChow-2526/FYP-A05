import 'package:copilot/core/platform/channel_prefs_port.dart';
import 'package:copilot/core/net/api_client.dart';
import 'package:copilot/core/net/api_transport.dart';
import 'package:copilot/core/net/http_result.dart';
import 'package:copilot/core/net/recommend_response.dart';
import 'package:copilot/core/net/recommendation_card.dart';
import 'package:copilot/core/trip/prefs_port.dart';
import 'package:copilot/core/trip/sync_api.dart';
import 'package:copilot/core/trip/today_trip_store.dart';
import 'package:copilot/core/trip/today_trip_sync.dart';
import 'package:copilot/core/trip/trip_controller.dart';
import 'package:copilot/core/trip/trip_types.dart';
import 'package:copilot/core/trip/trip_ports.dart';
import 'package:copilot/core/voice/voice_capability.dart';
import 'package:copilot/core/voice/zh_hk_templates.dart';
import 'package:copilot/platform/trip_platform_channel.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

class _NoSync implements SyncApi {
  @override
  Future<CallOutcome> syncDriverConfirmed(String sessionId, int timestampSec, {ParkedEventIdentity identity = ParkedEventIdentity.legacy}) async => const CallOutcome(
        succeeded: false,
        httpStatus: 500,
        spokenCues: [],
        attempts: 1,
        waitIntervalsMs: [],
        shouldTurnGrey: false,
        shouldTurnReady: false,
      );

  @override
  Future<CallOutcome> submitTripReport({
    required String sessionId,
    required String carparkId,
    required String ratingTag,
    required String perkStatusTag,
    required String perkNotes,
    required int tripTimestampSec,
  }) async => const CallOutcome(
        succeeded: false,
        httpStatus: 500,
        spokenCues: [],
        attempts: 1,
        waitIntervalsMs: [],
        shouldTurnGrey: false,
        shouldTurnReady: false,
      );
}

class _Online implements NetworkStatusProvider {
  @override
  bool isOnline() => true;
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('startTripFgs sends entry wire name and onFix restores NaN speed', () async {
    final channel = MethodChannel(ChannelTripPlatform.name);
    final calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      if (call.method == 'startTripFgs') return true;
      return null;
    });
    final platform = ChannelTripPlatform(channel);
    final store = TodayTripStore(MemoryPrefsPort(), hktDateOf: (_) => '2026-10-26');
    final controller = TripController(
      platform: platform,
      api: ApiClient(_NoTransport(), sleeper: (_) async {}),
      store: store,
      sync: TodayTripSync(store, _NoSync(), _Online()),
      nowMs: () => 1,
      delay: (_) async {},
      sessionIdFactory: () => 'sess_bridge',
    );
    platform.bind(controller);
    final outcome = await controller.start(StartupEntry.a2);
    expect(outcome.accepted, isTrue);
    final start = calls.singleWhere((call) => call.method == 'startTripFgs');
    expect(start.arguments, {'entry': 'A_2'});
    const codec = StandardMethodCodec();
    await TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.handlePlatformMessage(
      ChannelTripPlatform.name,
      codec.encodeMethodCall(const MethodCall('onFix', {
        'lat': 22.3,
        'lng': 114.1,
        'accuracyM': 8,
        'speedKmh': null,
        'timestampMs': 10,
      })),
      (_) {},
    );
    expect(controller.tracker.lastDistanceM, isNull);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
  });

  test('hydrate blocks write-through and hotword start hands off to startTripFgs', () async {
    final writes = <String>[];
    final prefs = ChannelPrefsPort(onWrite: (key, value) => writes.add('$key:$value'));
    prefs.putString('today_trips_json', '[]');
    prefs.remove('today_trips_hkt_date');
    expect(writes, isEmpty);
    expect(prefs.getString('today_trips_json'), isNull);
    prefs.hydrate({'today_trips_json': '[]'});
    prefs.putString('today_trips_json', '[1]');
    expect(writes, ['today_trips_json:[1]']);

    final channel = MethodChannel(ChannelTripPlatform.name);
    final calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      if (call.method == 'startTripFgs') return true;
      return null;
    });
    final platform = ChannelTripPlatform(channel);
    final store = TodayTripStore(MemoryPrefsPort(), hktDateOf: (_) => '2026-10-26');
    final controller = TripController(
      platform: platform,
      api: ApiClient(_NoTransport(), sleeper: (_) async {}),
      store: store,
      sync: TodayTripSync(store, _NoSync(), _Online()),
      nowMs: () => 1,
      delay: (_) async {},
      sessionIdFactory: () => 'sess_hot',
    );
    platform.bind(controller);
    const codec = StandardMethodCodec();
    await TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.handlePlatformMessage(
      ChannelTripPlatform.name,
      codec.encodeMethodCall(const MethodCall('onHotwordStart', {'entry': 'A_1'})),
      (_) {},
    );
    expect(calls.where((call) => call.method == 'startTripFgs').single.arguments, {'entry': 'A_1'});
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
  });

  test('P3b-V5 native onVoiceUtterance(SHOW_DETAILS) routes through channel to card details (simulated message, not mic E2E)', () async {
    final channel = MethodChannel(ChannelTripPlatform.name);
    final calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      if (call.method == 'startTripFgs' || call.method == 'startListening') return true;
      return null;
    });
    final platform = ChannelTripPlatform(channel);
    final store = TodayTripStore(MemoryPrefsPort(), hktDateOf: (_) => '2026-10-26');
    final controller = TripController(
      platform: platform,
      api: ApiClient(_CardTransport(), sleeper: (_) async {}),
      store: store,
      sync: TodayTripSync(store, _NoSync(), _Online()),
      nowMs: () => 1,
      delay: (_) async {},
      sessionIdFactory: () => 'sess_voice',
    );
    platform.bind(controller);
    await controller.start(
      StartupEntry.a2,
      voice: const CapabilitySnapshot(stt: CapabilityStatus.available, tts: CapabilityStatus.available, mode: TripVoiceMode.fullVoice, voiceInputEnabled: true),
    );
    expect(calls.where((c) => c.method == 'startListening'), isEmpty, reason: '開程唔長開咪');
    controller.lockDestinationAt('A', 22.3193, 114.1694);
    controller.onFix(const LocFix(lat: 22.32, lng: 114.1694, accuracyM: 8, speedKmh: 30, timestampMs: 1));
    await controller.requestRecommendation();
    expect(controller.cards, isNotEmpty);
    expect(controller.snapshot.cardsOpen, isFalse);
    expect(calls.where((c) => c.method == 'startListening'), isEmpty, reason: 'TTS 未完結唔開咪');
    final speakCall = calls.lastWhere((c) => c.method == 'speak');
    final utteranceId = (speakCall.arguments as Map)['utteranceId'] as String;
    await TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.handlePlatformMessage(
      ChannelTripPlatform.name,
      const StandardMethodCodec().encodeMethodCall(MethodCall('onSpeechDone', {'utteranceId': utteranceId, 'played': true})),
      (_) {},
    );
    expect(calls.where((c) => c.method == 'startListening').single.arguments, {'hotword': false});
    const codec = StandardMethodCodec();
    await TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.handlePlatformMessage(
      ChannelTripPlatform.name,
      codec.encodeMethodCall(const MethodCall('onVoiceUtterance', {'text': '詳細啲', 'confidence': 0.9, 'code': 'SHOW_DETAILS', 'lowConfidence': false, 'blockedAsSelfHearing': false})),
      (_) {},
    );
    expect(controller.snapshot.cardsOpen, isTrue);
    await TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.handlePlatformMessage(
      ChannelTripPlatform.name,
      codec.encodeMethodCall(const MethodCall('onVoiceError', {'code': 'NO_RECOGNIZER', 'permanent': true})),
      (_) {},
    );
    expect(controller.snapshot.voiceInputBlocked, 'NO_RECOGNIZER');
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
  });
}

class _CardTransport implements ApiTransport {
  @override
  Future<HttpResult> post(String path, String bodyJson) async => const HttpResult(
        statusCode: 200,
        recommendResponse: RecommendResponse(status: 'SUCCESS', cards: [
          RecommendationCard(carparkId: 'CP', name: '場', type: 'M', vacancies: 5, distanceMeters: 10, feeSummary: '', spokenCue: '去CP', isStale: false),
        ]),
      );
}

class _NoTransport implements ApiTransport {
  @override
  Future<HttpResult> post(String path, String bodyJson) async => const HttpResult(statusCode: 200);
}
