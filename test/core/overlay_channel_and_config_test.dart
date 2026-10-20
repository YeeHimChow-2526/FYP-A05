import 'package:copilot/core/constants/app_config.dart';
import 'package:copilot/core/platform/overlay_channel.dart';
import 'package:copilot/core/preferences/preference_manager.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  final List<MethodCall> calls = [];

  setUp(() {
    calls.clear();
    OverlayChannel.resetForTest();
    OverlayChannel.attach();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(OverlayChannel.channel, (call) async {
      calls.add(call);
      return null;
    });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(OverlayChannel.channel, null);
    OverlayChannel.resetForTest();
  });

  test('AppConfig 畫面參數存在且型別正確', () {
    expect(AppConfig.permissionTutorialPages, isA<int>());
    expect(AppConfig.permissionTutorialPages, 6);
    expect(AppConfig.todayTripsResetTimeHkt, isA<String>());
    expect(AppConfig.todayTripsResetTimeHkt, '00:00');
  });

  test('OverlayChannel 十個 Flutter → Native 方法名稱與參數', () async {
    await OverlayChannel.startBubbleService();
    await OverlayChannel.getPermissionStatus();
    await OverlayChannel.getVoiceCapability();
    await OverlayChannel.startVoiceCapture(enableHotword: true);
    await OverlayChannel.stopVoiceCapture();
    await OverlayChannel.setPreferences(
      preference: 'SMART_HYBRID',
      hotwordEnabled: false,
    );
    await OverlayChannel.getTripState();
    await OverlayChannel.getTodayTrips();
    await OverlayChannel.confirmParkedCandidate('session-1');
    await OverlayChannel.submitTripReport('{"rating":1}');

    expect(calls.map((call) => call.method).toList(), [
      'startBubbleService',
      'getPermissionStatus',
      'getVoiceCapability',
      'startVoiceCapture',
      'stopVoiceCapture',
      'setPreferences',
      'getTripState',
      'getTodayTrips',
      'confirmParkedCandidate',
      'submitTripReport',
    ]);
    expect(calls[0].arguments, isNull);
    expect(calls[1].arguments, isNull);
    expect(calls[2].arguments, isNull);
    expect(calls[3].arguments, {'enableHotword': true});
    expect(calls[4].arguments, isNull);
    expect(calls[5].arguments, {
      'preference': 'SMART_HYBRID',
      'hotwordEnabled': false,
    });
    expect(calls[6].arguments, isNull);
    expect(calls[7].arguments, isNull);
    expect(calls[8].arguments, {'sessionId': 'session-1'});
    expect(calls[9].arguments, {'payloadJson': '{"rating":1}'});
  });

  test('OverlayChannel 四個 Native → Flutter 只讀事件觸發監聽器', () async {
    String? state;
    String? dataJson;
    var todayTripsCount = 0;
    String? endedReason;
    bool? micActive;
    String? micReason;

    OverlayChannel.tripStateListeners.add((nextState, nextDataJson) {
      state = nextState;
      dataJson = nextDataJson;
    });
    OverlayChannel.todayTripsListeners.add(() {
      todayTripsCount += 1;
    });
    OverlayChannel.tripEndedListeners.add((reason) {
      endedReason = reason;
    });
    OverlayChannel.micSessionListeners.add((isActive, reason) {
      micActive = isActive;
      micReason = reason;
    });

    await _emit('onTripStateChanged', {
      'state': 'CRUISING',
      'dataJson': '{"pip":"green"}',
    });
    await _emit('onTodayTripsChanged', null);
    await _emit('onTripEnded', {'reason': 'PARKED_CONFIRMED'});
    await _emit('onMicSessionChanged', {
      'isActive': false,
      'reason': 'MIC_FAILED',
    });

    expect(state, 'CRUISING');
    expect(dataJson, '{"pip":"green"}');
    expect(todayTripsCount, 1);
    expect(endedReason, 'PARKED_CONFIRMED');
    expect(micActive, isFalse);
    expect(micReason, 'MIC_FAILED');
  });

  test('PreferenceManager 切換三段偏好與熱詞時呼叫 setPreferences', () async {
    final manager = PreferenceManager();
    expect(manager.preference, DriverPreference.smartHybrid);
    expect(manager.preference.code, 'SMART_HYBRID');
    expect(manager.hotwordEnabled, isTrue);

    await manager.setPreference(DriverPreference.indoorOnly);
    await manager.setPreference(DriverPreference.smartHybrid);
    await manager.setPreference(DriverPreference.metersFocus);
    await manager.setHotwordEnabled(false);

    expect(calls.map((call) => call.method).toList(), [
      'setPreferences',
      'setPreferences',
      'setPreferences',
      'setPreferences',
    ]);
    expect(calls[0].arguments, {
      'preference': 'INDOOR_ONLY',
      'hotwordEnabled': true,
    });
    expect(calls[1].arguments, {
      'preference': 'SMART_HYBRID',
      'hotwordEnabled': true,
    });
    expect(calls[2].arguments, {
      'preference': 'METERS_FOCUS',
      'hotwordEnabled': true,
    });
    expect(calls[3].arguments, {
      'preference': 'METERS_FOCUS',
      'hotwordEnabled': false,
    });
  });
}

Future<void> _emit(String method, Object? arguments) {
  final codec = const StandardMethodCodec();
  return TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
      .handlePlatformMessage(
    OverlayChannel.name,
    codec.encodeMethodCall(MethodCall(method, arguments)),
    (_) {},
  );
}
