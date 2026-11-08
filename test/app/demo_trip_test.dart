import 'package:copilot/app/app_mode.dart';
import 'package:copilot/app/demo_trip.dart';
import 'package:copilot/app/trip_runtime.dart';
import 'package:copilot/core/nav/fake_nav_engine.dart';
import 'package:copilot/core/nav/google_nav_engine.dart';
import 'package:copilot/core/net/api_client.dart';
import 'package:copilot/core/trip/trip_ports.dart';
import 'package:copilot/core/trip/trip_types.dart';
import 'package:flutter_test/flutter_test.dart';

import '../core/trip/trip_controller_test.dart';

Future<(dynamic, DemoTripPlayer, HoldTransport, FakeNavEngine)> _demo() async {
  final t = HoldTransport()..next = ok([card('CP_LANGHAM', '朗豪坊停車場')]);
  final nav = FakeNavEngine();
  final c = await boot(t, FakePlatform(), ScriptDelay(), navEngine: nav);
  final p = DemoTripPlayer(
    c,
    interval: const Duration(hours: 1),
  ); // timer 唔會自己行；測試手動 tick
  return (c, p, t, nav);
}

void main() {
  group('r9 DEMO trip', () {
    test('D1 start locks fixed destination, starts at fixed origin, ignores device GPS', () async {
      final (c, p, _, nav) = await _demo();
      p.start();
      expect(c.tsm.destinationName, DemoTripScenario.destinationName);
      expect(nav.userLat, DemoTripScenario.waypoints.first.$1);
      expect(c.deviceFixesEnabled, isFalse);
      c.onDeviceFix(
        const LocFix(lat: 1, lng: 1, accuracyM: 5, speedKmh: 0, timestampMs: 9),
      );
      expect(nav.userLat, DemoTripScenario.waypoints.first.$1);
      p.stop();
    });

    test(
      'D2 replay moves monotonically to destination and holds there',
      () async {
        final (c, p, _, nav) = await _demo();
        p.start();
        double last = nav.userLat!;
        for (var i = 0; i < p.length + 5; i++) {
          p.tick();
          await Future<void>.delayed(Duration.zero);
          expect(nav.userLat!, greaterThanOrEqualTo(last));
          last = nav.userLat!;
        }
        expect(nav.userLat, closeTo(DemoTripScenario.destLat, 1e-9));
        expect(p.length, greaterThan(100)); // ~15 m 一步，約 2.4 km
        p.stop();
      },
    );

    test('D3 no auto-confirm: full replay never commits; recommend only after 1 km', () async {
      final (_, p, t, _) = await _demo();
      p.start();
      await Future<void>.delayed(Duration.zero);
      expect(
        t.calls,
        isNot(contains(ApiClient.RECOMMEND)),
        reason: '起點 >1 km：唔推薦',
      );
      for (var i = 0; i < p.length + 2; i++) {
        p.tick();
        await Future<void>.delayed(Duration.zero);
      }
      expect(t.calls, contains(ApiClient.RECOMMEND));
      expect(t.calls, isNot(contains(ApiClient.COMMIT)));
    });

    test('D4 stop resets; restart replays from same origin', () async {
      final (c, p, _, nav) = await _demo();
      p.start();
      for (var i = 0; i < 20; i++) {
        p.tick();
      }
      p.stop();
      expect(p.running, isFalse);
      expect(p.index, 0);
      p.start();
      expect(nav.userLat, DemoTripScenario.waypoints.first.$1);
      p.stop();
    });

    test('D5 trip end stops playback and restores device GPS input', () async {
      final (c, p, _, _) = await _demo();
      TripRuntime.syncDemoPlayer(c, p);
      expect(p.running, isTrue);
      await c.abortTripByUser();
      TripRuntime.syncDemoPlayer(c, p);
      expect(p.running, isFalse);
      expect(c.deviceFixesEnabled, isTrue);
    });

    test('D7 FGS start sends the demo destination, replays into distance and recommend, then can start again', () async {
      final t = HoldTransport()..next = ok([card('CP_LANGHAM', '朗豪坊停車場')]);
      final nav = FakeNavEngine();
      final c = await boot(
        t,
        FakePlatform(),
        ScriptDelay(),
        navEngine: nav,
        startTrip: false,
      );
      final p = DemoTripPlayer(c, interval: const Duration(hours: 1));
      final sub = TripRuntime.listenForDemoPlayback(c, p);
      addTearDown(() async => sub.cancel());
      await c.start(StartupEntry.a2);
      await Future<void>.delayed(Duration.zero);
      expect(p.running, isTrue, reason: '開波之後重播計時器要真係啟動');
      expect(c.deviceFixesEnabled, isFalse);
      expect(nav.activeTitle, DemoTripScenario.destinationName);
      expect(nav.activeLat, DemoTripScenario.destLat);
      expect(nav.activeLng, DemoTripScenario.destLng);
      expect(nav.calls, contains('setDestinations'));
      expect(nav.calls, contains('startGuidance'));
      final originDistance = c.tracker.distanceToDestinationM();
      expect(originDistance, isNotNull);
      expect(originDistance!, greaterThan(1000));
      expect(c.snapshot.lastOriginLat, DemoTripScenario.waypoints.first.$1);
      expect(t.calls, isNot(contains(ApiClient.RECOMMEND)));
      for (var i = 0; i < p.length + 2; i++) {
        p.tick();
        await Future<void>.delayed(Duration.zero);
      }
      expect(
        t.calls,
        contains(ApiClient.RECOMMEND),
        reason: '重播位置要進入距離判斷，入到 1 km 先推薦',
      );
      expect(c.snapshot.readinessLatched, isTrue);
      await c.abortTripByUser();
      await Future<void>.delayed(Duration.zero);
      expect(p.running, isFalse);
      expect(c.deviceFixesEnabled, isTrue);
      final guidanceBefore = nav.calls
          .where((call) => call == 'startGuidance')
          .length;
      await c.start(StartupEntry.a2);
      await Future<void>.delayed(Duration.zero);
      expect(p.running, isTrue);
      expect(
        nav.calls.where((call) => call == 'startGuidance').length,
        guidanceBefore + 1,
      );
      expect(nav.activeTitle, DemoTripScenario.destinationName);
      expect(nav.userLat, DemoTripScenario.waypoints.first.$1);
      await sub.cancel();
      p.stop();
    });

    test('D8 stored DEMO survives an app restart; release build does not restore it', () {
      expect(
        TripRuntime.restoredMode('DEMO', demoAllowed: true),
        AppRunMode.demo,
      );
      expect(
        TripRuntime.restoredMode('NORMAL', demoAllowed: true),
        AppRunMode.normal,
      );
      expect(
        TripRuntime.restoredMode(null, demoAllowed: true),
        AppRunMode.normal,
      );
      expect(
        TripRuntime.restoredMode('DEMO', demoAllowed: false),
        AppRunMode.normal,
      );
    });

    test('D6 engine choice: normal real-nav = real GPS; demo = SDK simulator; no real-nav build = fake', () {
      AppModeInfo info(AppRunMode m, bool real) => AppModeInfo(
        mode: m,
        liveApi: false,
        realNavigation: real,
        demoAllowed: true,
      );
      final normal = TripRuntime.engineFor(
        info(AppRunMode.normal, true),
        simLocationConfig: false,
      );
      final demo = TripRuntime.engineFor(
        info(AppRunMode.demo, true),
        simLocationConfig: false,
      );
      expect((normal as GoogleNavEngine).simulateUserLocation, isFalse);
      expect((demo as GoogleNavEngine).simulateUserLocation, isTrue);
      expect(
        TripRuntime.engineFor(
          info(AppRunMode.demo, false),
          simLocationConfig: false,
        ),
        isA<FakeNavEngine>(),
      );
    });
  });
}
