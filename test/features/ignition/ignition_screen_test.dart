import 'package:copilot/app/app_mode.dart';
import 'package:copilot/app/trip_runtime.dart';
import 'package:copilot/core/trip/trip_types.dart';
import 'package:copilot/features/ignition/ignition_screen.dart';
import 'package:copilot/platform/trip_platform_channel.dart';
import 'package:flutter/material.dart';
import 'package:copilot/features/settings/tutorial_screen.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

Future<void> _attach(WidgetTester tester) {
  return tester.runAsync(() => TripRuntime.instance.attach());
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('F-T1 pre-trip shows HK map and bottom bar', (tester) async {
    final channel = MethodChannel(ChannelTripPlatform.name);
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (call) async => null);
    await _attach(tester);
    await tester.pumpWidget(const MaterialApp(home: IgnitionScreen()));
    await tester.pumpAndSettle(const Duration(milliseconds: 50));
    expect(find.byKey(const Key('home_map_backdrop')), findsOneWidget);
    expect(find.byKey(const Key('fake_nav_view')), findsOneWidget);
    expect(find.textContaining('22.3193, 114.1694'), findsWidgets);
    expect(find.byKey(const Key('pre_trip_bottom_bar')), findsOneWidget);
    expect(find.byKey(const Key('chrome_park')), findsNothing);
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
  });

  testWidgets('C19 onFgsStopped returns pre-trip bar and keeps map', (tester) async {
    final channel = MethodChannel(ChannelTripPlatform.name);
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'startTripFgs') return true;
      return null;
    });
    await _attach(tester);
    await tester.pumpWidget(const MaterialApp(home: IgnitionScreen()));
    await tester.pumpAndSettle(const Duration(milliseconds: 50));
    expect(find.byKey(const Key('pre_trip_bottom_bar')), findsOneWidget);
    expect(find.byKey(const Key('fake_nav_view')), findsOneWidget);

    await tester.tap(find.widgetWithText(FilledButton, '出發'));
    await tester.pump();
    for (var i = 0; i < 4; i++) {
      // t1253：開程前多咗權限／語音能力查詢（原生通道），每個 hop 要真事件循環。
      await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 50)));
      await tester.pump();
    }
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('chrome_exit')), findsOneWidget);
    expect(find.byKey(const Key('pre_trip_bottom_bar')), findsNothing);
    expect(find.byKey(const Key('fake_nav_view')), findsOneWidget);

    const codec = StandardMethodCodec();
    await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
      ChannelTripPlatform.name,
      codec.encodeMethodCall(const MethodCall('onFgsStopped', {'reason': 'TEST'})),
      (_) {},
    );
    await tester.pump();
    expect(find.byKey(const Key('pre_trip_bottom_bar')), findsOneWidget);
    expect(find.byKey(const Key('fake_nav_view')), findsOneWidget);
    expect(find.byKey(const Key('chrome_exit')), findsNothing);

    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
  });

  group('Phase2 tabs / DEMO / 現場即泊', () {
    late List<MethodCall> calls;
    late MethodChannel channel;

    Future<void> pumpHome(WidgetTester tester) async {
      calls = [];
      channel = MethodChannel(ChannelTripPlatform.name);
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call);
        if (call.method == 'startTripFgs') return true;
        return null;
      });
      await _attach(tester);
      await tester.pumpWidget(const MaterialApp(home: IgnitionScreen()));
      await tester.pumpAndSettle(const Duration(milliseconds: 50));
    }

    Future<void> endTrip(WidgetTester tester) async {
      const codec = StandardMethodCodec();
      await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
        ChannelTripPlatform.name,
        codec.encodeMethodCall(const MethodCall('onFgsStopped', {'reason': 'TEST'})),
        (_) {},
      );
      await tester.pump();
    }

    tearDown(() {
      TripRuntime.instance.releaseOverride = null;
    });

    testWidgets('P2-T1 four tabs, map is default with 出發 + 現場即泊 and a mode marker', (tester) async {
      await pumpHome(tester);
      for (final label in ['偏好', '地圖', '今日行程', '設定']) {
        expect(find.descendant(of: find.byKey(const Key('pre_trip_bottom_bar')), matching: find.text(label)), findsOneWidget);
      }
      final bar = tester.widget<NavigationBar>(find.byKey(const Key('pre_trip_bottom_bar')));
      expect(bar.selectedIndex, 1);
      expect(find.byKey(const Key('map_depart')), findsOneWidget);
      expect(find.byKey(const Key('map_on_site')), findsOneWidget);
      expect(find.byKey(const Key('map_mode_marker')), findsOneWidget);
      expect(find.text('正常｜示範數據（fixture）｜模擬導航'), findsOneWidget);
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
    });

    testWidgets('P2-T2 tabs switch pages; preference tab drives controller.preferenceCode', (tester) async {
      await pumpHome(tester);
      await tester.tap(find.byKey(const Key('nav_tab_today')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('tab_today')), findsOneWidget);
      expect(find.byKey(const Key('map_depart')), findsNothing);
      await tester.tap(find.byKey(const Key('nav_tab_settings')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('tab_settings')), findsOneWidget);
      expect(find.byKey(const Key('settings_tutorial')), findsOneWidget);
      expect(find.byKey(const Key('settings_permissions')), findsOneWidget);
      await tester.tap(find.byKey(const Key('nav_tab_preferences')));
      await tester.pumpAndSettle();
      await tester.runAsync(() async {
        await tester.tap(find.byKey(const Key('pref_INDOOR_ONLY')));
        await Future<void>.delayed(const Duration(milliseconds: 20));
      });
      await tester.pumpAndSettle();
      expect(TripRuntime.instance.controller!.preferenceCode, 'INDOOR_ONLY');
      await tester.runAsync(() async {
        await tester.tap(find.byKey(const Key('pref_SMART_HYBRID')));
        await Future<void>.delayed(const Duration(milliseconds: 20));
      });
      await tester.pumpAndSettle();
      expect(TripRuntime.instance.controller!.preferenceCode, 'SMART_HYBRID');
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
    });

    testWidgets('P2-PREF preference persists to prefs and survives controller rebuild', (tester) async {
      await pumpHome(tester);
      await tester.tap(find.byKey(const Key('nav_tab_preferences')));
      await tester.pumpAndSettle();
      await tester.runAsync(() async {
        await tester.tap(find.byKey(const Key('pref_METERS_FOCUS')));
        await Future<void>.delayed(const Duration(milliseconds: 20));
      });
      await tester.pumpAndSettle();
      expect(TripRuntime.instance.preferenceCode, 'METERS_FOCUS');
      expect(calls.any((c) => c.method == 'prefsPut' && '${c.arguments}'.contains('hkp_driver_preference') && '${c.arguments}'.contains('METERS_FOCUS')), isTrue);
      final before = TripRuntime.instance.controller;
      final switched = await tester.runAsync(() => TripRuntime.instance.setMode(AppRunMode.demo));
      if (switched == true) {
        expect(identical(TripRuntime.instance.controller, before), isFalse);
      }
      expect(TripRuntime.instance.controller!.preferenceCode, 'METERS_FOCUS');
      await tester.runAsync(() => TripRuntime.instance.setMode(AppRunMode.normal));
      expect(TripRuntime.instance.controller!.preferenceCode, 'METERS_FOCUS');
      TripRuntime.instance.setPreferenceCode('SMART_HYBRID');
      expect(() => TripRuntime.instance.setPreferenceCode('NEAREST'), throwsArgumentError);
      expect(TripRuntime.instance.preferenceCode, 'SMART_HYBRID');
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
    });

    testWidgets('P2-T3 tutorial shows current 4 pages without legacy claims', (tester) async {
      await pumpHome(tester);
      await tester.tap(find.byKey(const Key('nav_tab_settings')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('settings_tutorial')));
      await tester.pumpAndSettle();
      expect(find.text('新手教學 1/4'), findsOneWidget);
      for (final page in tutorialPages) {
        for (final legacy in ['模式 A', '模式 B', '熱詞', '零儲存', '懸浮窗', '通知存取權']) {
          expect(page.$2.contains(legacy) || page.$1.contains(legacy), isFalse, reason: legacy);
        }
      }
      expect(tutorialPages.any((p) => p.$2.contains('1 km')), isTrue);
      expect(tutorialPages.any((p) => p.$2.contains('確認已泊妥')), isTrue);
      expect(tutorialPages.any((p) => p.$2.contains('正常模式用即時數據')), isTrue);
      expect(tutorialPages.any((p) => p.$2.contains('粵語')), isTrue);
      expect(tutorialPages.any((p) => p.$2.contains('模擬導航')), isFalse);
      for (var i = 0; i < 3; i++) {
        await tester.tap(find.byKey(const Key('tutorial_next')));
        await tester.pumpAndSettle();
      }
      expect(find.text('新手教學 4/4'), findsOneWidget);
      await tester.tap(find.byKey(const Key('tutorial_next')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('tab_settings')), findsOneWidget);
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
    });

    testWidgets('P2-T4 DEMO switch rebuilds controller and shows DEMO marker; back to normal', (tester) async {
      await pumpHome(tester);
      final before = TripRuntime.instance.controller;
      await tester.tap(find.byKey(const Key('nav_tab_settings')));
      await tester.pumpAndSettle();
      await tester.runAsync(() async {
        await tester.tap(find.byKey(const Key('demo_mode_switch')));
        await Future<void>.delayed(const Duration(milliseconds: 20));
      });
      await tester.pumpAndSettle();
      expect(TripRuntime.instance.modeInfo.isDemo, isTrue);
      expect(TripRuntime.instance.demoPlayer, isNotNull);
      expect(identical(before, TripRuntime.instance.controller), isFalse);
      expect(find.text('DEMO｜示範數據（fixture）｜模擬導航'), findsOneWidget);
      expect(calls.where((c) => c.method == 'prefsPut' && (c.arguments as Map)['value'] == 'DEMO'), hasLength(1));
      await tester.runAsync(() => TripRuntime.instance.setMode(AppRunMode.normal));
      await tester.pumpAndSettle();
      expect(TripRuntime.instance.modeInfo.isDemo, isFalse);
      expect(TripRuntime.instance.demoPlayer, isNull);
      expect(TripRuntime.instance.controller!.deviceFixesEnabled, isTrue);
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
    });

    testWidgets('P2-T5 release build cannot enter DEMO (switch disabled, setMode refused)', (tester) async {
      await pumpHome(tester);
      TripRuntime.instance.releaseOverride = true;
      await tester.tap(find.byKey(const Key('nav_tab_settings')));
      await tester.pumpAndSettle();
      final sw = tester.widget<SwitchListTile>(find.byKey(const Key('demo_mode_switch')));
      expect(sw.onChanged, isNull);
      final ok = await tester.runAsync(() => TripRuntime.instance.setMode(AppRunMode.demo));
      expect(ok, isFalse);
      expect(TripRuntime.instance.modeInfo.isDemo, isFalse);
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
    });

    testWidgets('P2-T6 現場即泊 starts ON_SITE, hides tabs, accurate fix locks dest and recommends; mode locked in trip', (tester) async {
      await pumpHome(tester);
      await tester.tap(find.byKey(const Key('map_on_site')));
      await tester.pump();
      for (var i = 0; i < 4; i++) {
        await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 50)));
        await tester.pump();
      }
      await tester.pumpAndSettle();
      final start = calls.lastWhere((c) => c.method == 'startTripFgs');
      expect((start.arguments as Map)['entry'], 'ON_SITE');
      expect(find.byKey(const Key('pre_trip_bottom_bar')), findsNothing);
      expect(find.byKey(const Key('chrome_exit')), findsOneWidget);
      final controller = TripRuntime.instance.controller!;
      expect(controller.tsm.onSitePendingFix, isTrue);

      final refused = await tester.runAsync(() => TripRuntime.instance.setMode(AppRunMode.demo));
      expect(refused, isFalse);
      expect(identical(controller, TripRuntime.instance.controller), isTrue);

      const codec = StandardMethodCodec();
      await tester.runAsync(() async {
        await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
          ChannelTripPlatform.name,
          codec.encodeMethodCall(const MethodCall('onFix', {'lat': 22.30, 'lng': 114.17, 'accuracyM': 20.0, 'speedKmh': 0.0, 'timestampMs': 1})),
          (_) {},
        );
        await Future<void>.delayed(const Duration(milliseconds: 50));
      });
      await tester.pump();
      expect(controller.tsm.destLat, 22.30);
      expect(controller.tsm.destLng, 114.17);
      expect(calls.where((c) => c.method == 'fixturePost' && (c.arguments as Map)['path'].toString().contains('recommend')), hasLength(1));
      await endTrip(tester);
      expect(find.byKey(const Key('pre_trip_bottom_bar')), findsOneWidget);
      expect(controller.snapshot.sessionId, isNull);
      expect(StartupEntry.onSite.wireName, 'ON_SITE');
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
    });
  });
}
