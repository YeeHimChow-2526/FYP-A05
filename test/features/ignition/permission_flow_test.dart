// t1253：經真 MethodChannel 派發嘅權限流程整合測試（ignition 畫面 ↔ overlay／trip 通道）。
// 假原生按 Android 語義運作：對話框可拒絕；第二次拒絕後 shouldShowRequestPermissionRationale=false。
import 'package:copilot/app/trip_runtime.dart';
import 'package:copilot/core/platform/overlay_channel.dart';
import 'package:copilot/core/platform/trip_permissions.dart';
import 'package:copilot/core/voice/zh_hk_templates.dart';
import 'package:copilot/features/ignition/ignition_screen.dart';
import 'package:copilot/platform/trip_platform_channel.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

class FakeAndroid {
  FakeAndroid({this.sdk = 34});
  final int sdk;
  final Set<String> granted = {};
  final Set<String> asked = {};
  final Map<String, int> denials = {};
  final Map<String, List<bool>> answers = {};
  bool locationServicesOn = true;
  bool tts = true, recognizer = true, simulated = true;
  final List<String> requested = [];
  int settingsOpened = 0;

  Map<String, Object?> status(String k) {
    String state;
    if (k == 'notifications' && sdk < 33) {
      state = 'notApplicable';
    } else if (granted.contains(k)) {
      state = 'granted';
    } else {
      state = 'denied';
    }
    final rationale = (denials[k] ?? 0) == 1;
    final can = state == 'denied' && (!asked.contains(k) || rationale);
    return {'state': state, 'canRequest': can, 'askedBefore': asked.contains(k)};
  }

  Future<Object?> handle(MethodCall call) async {
    switch (call.method) {
      case 'getTripPermissions':
        return {
          for (final k in ['location', 'notifications', 'bluetooth', 'microphone']) k: status(k),
          'locationServicesOn': locationServicesOn,
        };
      case 'requestTripPermission':
        final k = (call.arguments as Map)['kind'] as String;
        if (status(k)['canRequest'] != true) return status(k);
        requested.add(k);
        asked.add(k);
        final queue = answers[k] ?? [];
        final yes = queue.isEmpty ? false : queue.removeAt(0);
        if (yes) {
          granted.add(k);
        } else {
          denials[k] = (denials[k] ?? 0) + 1;
        }
        return status(k);
      case 'requestRecordAudioPermission':
        requested.add('microphone');
        return granted.contains('microphone');
      case 'openAppSettings':
        settingsOpened++;
        return true;
      case 'getVoiceFacts':
        return {
          'ttsEngine': tts,
          'recognizer': recognizer,
          'recordAudio': granted.contains('microphone'),
          'simulatedInput': simulated,
        };
    }
    return null;
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late FakeAndroid android;
  late List<MethodCall> tripCalls;

  Future<void> pumpHome(WidgetTester tester) async {
    tripCalls = [];
    final messenger = tester.binding.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(const MethodChannel(ChannelTripPlatform.name), (call) async {
      tripCalls.add(call);
      if (call.method == 'startTripFgs') return true;
      return null;
    });
    messenger.setMockMethodCallHandler(OverlayChannel.channel, android.handle);
    await tester.runAsync(() => TripRuntime.instance.attach());
    await tester.pumpWidget(const MaterialApp(home: IgnitionScreen()));
    await tester.pumpAndSettle(const Duration(milliseconds: 50));
  }

  Future<void> tapAsync(WidgetTester tester, Key key) async {
    await tester.tap(find.byKey(key));
    await tester.pumpAndSettle();
  }

  Future<void> depart(WidgetTester tester, {bool expectExplain = true}) async {
    await tester.tap(find.byKey(const Key('map_depart')));
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 30)));
    await tester.pumpAndSettle();
    if (expectExplain) {
      expect(find.byKey(const Key('location_explain_dialog')), findsOneWidget);
      await tester.tap(find.byKey(const Key('location_explain_continue')));
    }
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 30)));
    await tester.pumpAndSettle();
  }

  bool started() => tripCalls.any((c) => c.method == 'startTripFgs');

  setUp(() => android = FakeAndroid());
  tearDown(() async {
    final c = TripRuntime.instance.controller;
    if (c != null && c.tripRunning) await c.abortTripByUser();
  });

  testWidgets('PF1 deny → banner, no FGS; retry → deny again → settings only; grant via settings → start', (tester) async {
    android.answers['location'] = [false, false];
    await pumpHome(tester);
    await depart(tester);
    expect(android.requested, ['location']);
    expect(started(), isFalse, reason: '未批唔准靜靜雞開服務');
    expect(find.byKey(const Key('permission_banner')), findsOneWidget);
    expect(find.textContaining('需要「精確位置」'), findsOneWidget);
    expect(find.byKey(const Key('permission_retry')), findsOneWidget);
    expect(find.byKey(const Key('map_depart')), findsOneWidget, reason: '冇全畫面阻擋');

    await tester.tap(find.byKey(const Key('permission_retry')));
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 30)));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('location_explain_continue')));
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 30)));
    await tester.pumpAndSettle();
    expect(android.requested, ['location', 'location']);
    expect(started(), isFalse);
    expect(find.byKey(const Key('permission_retry')), findsNothing, reason: '第二次拒絕後 Android 唔再彈，只畀系統設定');
    await tapAsync(tester, const Key('permission_open_settings'));
    expect(android.settingsOpened, 1);

    android.granted.add('location'); // 用戶喺系統設定批咗
    android.answers['notifications'] = [false];
    await depart(tester, expectExplain: false);
    expect(started(), isTrue);
    expect(find.byKey(const Key('permission_banner')), findsNothing);
  });

  testWidgets('PF2 grant location, deny notifications → trip still starts; no nag next time', (tester) async {
    android.answers['location'] = [true];
    android.answers['notifications'] = [false];
    await pumpHome(tester);
    await depart(tester);
    expect(android.requested, ['location', 'notifications']);
    expect(started(), isTrue);
    final again = await tester.runAsync(() => StartPermissionFlow(TripPermissionPort()).ensureForStart());
    expect(again, StartGateResult.allowed);
    expect(android.requested, ['location', 'notifications'], reason: '拒絕過通知唔再煩');
  });

  testWidgets('PF10 same widget: start → abort → start again', (tester) async {
    android.granted.add('location');
    await pumpHome(tester);
    await depart(tester, expectExplain: false);
    expect(tripCalls.where((c) => c.method == 'startTripFgs').length, 1);
    final c = TripRuntime.instance.controller!;
    // abortTripByUser 要真事件循環（原生通道＋計時器）；喺 fake-async 直接 await 會等唔到＝之前「卡死」係測試 harness，唔係產品問題。
    await tester.runAsync(() => c.abortTripByUser());
    expect(tripCalls.any((x) => x.method == 'stopTripFgs'), isTrue);
    await tester.pumpAndSettle();
    expect(c.tripRunning, isFalse);
    await depart(tester, expectExplain: false);
    expect(tripCalls.where((c) => c.method == 'startTripFgs').length, 2);
    expect(c.tripRunning, isTrue);
  });

  testWidgets('PF3 location services off blocks with its own message', (tester) async {
    android.granted.add('location');
    android.locationServicesOn = false;
    await pumpHome(tester);
    await depart(tester, expectExplain: false);
    expect(started(), isFalse);
    expect(find.textContaining('定位（GPS）未開'), findsOneWidget);
  });

  testWidgets('PF4 voice capability applied at start: debug simulated input → fullVoice', (tester) async {
    android.granted.add('location');
    await pumpHome(tester);
    await depart(tester, expectExplain: false);
    final c = TripRuntime.instance.controller!;
    expect(c.tsm.tripVoiceMode, TripVoiceMode.fullVoice);
    expect(c.tsm.voiceInputEnabled, isTrue);
    expect(tripCalls.where((x) => x.method == 'startListening'), isEmpty, reason: '有權限都唔會主動開咪');
  });

  testWidgets('PF5 release recognizer + mic denied → TTS prompts + manual (announceManual)', (tester) async {
    android
      ..granted.add('location')
      ..simulated = false;
    await pumpHome(tester);
    await depart(tester, expectExplain: false);
    final c = TripRuntime.instance.controller!;
    expect(c.tsm.tripVoiceMode, TripVoiceMode.announceManual);
    expect(c.tsm.voiceInputEnabled, isFalse);
  });

  testWidgets('PF6 user disables voice → resume never re-enables it', (tester) async {
    android.granted.add('location');
    await pumpHome(tester);
    await depart(tester, expectExplain: false);
    final c = TripRuntime.instance.controller!;
    expect(c.tsm.voiceInputEnabled, isTrue);
    // 行程中語音開關喺偏好分頁；直接經 screen 嘅同一條路徑：設 pref 再 resume。
    c.updateVoiceCapability(deriveVoiceCapability(
      const VoiceFacts(ttsEngine: true, recognizer: true, recordAudio: true, simulatedInput: true),
      userVoiceEnabled: false,
    ));
    expect(c.tsm.voiceInputEnabled, isFalse);
    expect(tripCalls.any((x) => x.method == 'stopListening'), isTrue);
    expect(c.tsm.tripVoiceMode, TripVoiceMode.announceManual);
  });

  testWidgets('PF7 location revoked while trip runs → resume tears down safely (FGS stopped, nav stopped)', (tester) async {
    android.granted.add('location');
    await pumpHome(tester);
    await depart(tester, expectExplain: false);
    final c = TripRuntime.instance.controller!;
    expect(c.tripRunning, isTrue);
    android.granted.remove('location');
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 30)));
    await tester.pumpAndSettle();
    expect(tripCalls.any((x) => x.method == 'stopTripFgs'), isTrue);
    expect(find.textContaining('已被收回'), findsOneWidget);
  });

  testWidgets('PF8 settings rows show real states and request on tap (bluetooth contextual)', (tester) async {
    android = FakeAndroid(sdk: 30);
    android.answers['bluetooth'] = [true];
    await pumpHome(tester);
    await tapAsync(tester, const Key('nav_tab_settings'));
    await tester.runAsync(() async {
      await tester.tap(find.byKey(const Key('settings_permissions_refresh')));
      await Future<void>.delayed(const Duration(milliseconds: 20));
    });
    await tester.pumpAndSettle();
    expect(find.textContaining('呢個 Android 版本唔使'), findsOneWidget, reason: 'sdk30 通知＝notApplicable');
    expect(find.byKey(const Key('perm_action_notifications')), findsNothing);
    await tester.runAsync(() async {
      await tester.tap(find.byKey(const Key('perm_action_bluetooth')));
      await Future<void>.delayed(const Duration(milliseconds: 30));
    });
    await tester.pumpAndSettle();
    expect(android.requested, ['bluetooth']);
    expect(find.byKey(const Key('perm_action_bluetooth')), findsNothing);
  });

  test('PF9 deriveVoiceCapability three honest modes', () {
    modeOf(bool tts, bool rec, bool mic, bool sim, bool user) => deriveVoiceCapability(
          VoiceFacts(ttsEngine: tts, recognizer: rec, recordAudio: mic, simulatedInput: sim),
          userVoiceEnabled: user,
        ).mode;
    expect(modeOf(false, true, true, false, true), TripVoiceMode.manualOnly);
    expect(modeOf(true, true, false, false, true), TripVoiceMode.announceManual);
    expect(modeOf(true, true, true, false, true), TripVoiceMode.fullVoice);
    expect(modeOf(true, false, false, true, true), TripVoiceMode.fullVoice);
    expect(modeOf(true, true, true, false, false), TripVoiceMode.announceManual);
  });
}
