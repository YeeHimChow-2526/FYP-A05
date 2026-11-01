import 'package:copilot/core/net/api_client.dart';
import 'package:copilot/core/net/api_transport.dart';
import 'package:copilot/core/net/http_result.dart';
import 'package:copilot/core/trip/prefs_port.dart';
import 'package:copilot/core/trip/sync_api.dart';
import 'package:copilot/core/trip/today_trip_store.dart';
import 'package:copilot/core/trip/today_trip_sync.dart';
import 'package:copilot/core/trip/trip_controller.dart';
import 'package:copilot/core/trip/trip_types.dart';
import 'package:copilot/platform/trip_platform_channel.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

class _NoSync implements SyncApi {
  @override
  Future<CallOutcome> syncDriverConfirmed(String sessionId, int timestampSec) async => const CallOutcome(
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
}

class _NoTransport implements ApiTransport {
  @override
  Future<HttpResult> post(String path, String bodyJson) async => const HttpResult(statusCode: 200);
}
