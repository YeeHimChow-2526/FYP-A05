// r6：GoogleNavEngine 生命週期（用 adapter 假 SDK／view；唔代表真 Navigation SDK 行為已驗證）。
import 'package:flutter/foundation.dart';
import 'package:flutter/painting.dart';
import 'package:copilot/core/nav/google_nav_engine.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_navigation_flutter/google_navigation_flutter.dart';

class FakeSdk extends NavSdk {
  bool locationGranted = false;
  bool initialized = false;
  NavigationRouteStatus status = NavigationRouteStatus.statusOk;
  final calls = <String>[];
  @override
  Future<bool> areTermsAccepted() async => true;
  @override
  Future<bool> isInitialized() async => initialized;
  @override
  Future<void> initialize() async {
    calls.add('initialize');
    if (!locationGranted) {
      throw Exception(
        'PlatformException(locationPermissionMissing) key=AIzaSyFAKEFAKEFAKEFAKE123',
      );
    }
    initialized = true;
  }

  @override
  Future<void> setUserLocation(double lat, double lng) async =>
      calls.add('loc');
  @override
  Future<void> removeUserLocation() async => calls.add('unsetLoc');
  @override
  Future<NavigationRouteStatus> setDestination(
    String title,
    double lat,
    double lng,
  ) async {
    calls.add('dest:$title');
    return status;
  }

  @override
  Future<void> startGuidance() async => calls.add('start');
  @override
  Future<void> stopGuidance() async => calls.add('stop');
}

class FakeView implements NavViewOps {
  FakeView(this.sdk);
  final FakeSdk sdk;
  final calls = <String>[];
  @override
  Future<void> configureChrome() async => calls.add('chrome');
  @override
  Future<void> setPadding(EdgeInsets padding) async =>
      calls.add('padding:${padding.bottom.toStringAsFixed(0)}');
  @override
  Future<void> setMyLocationEnabled(bool enabled) async {
    if (!sdk.locationGranted) {
      throw Exception('SecurityException: my location requires permission');
    }
    calls.add('myLocation');
  }

  @override
  Future<void> setNavigationUIEnabled(bool enabled) async =>
      calls.add('navUi:$enabled');
  @override
  Future<void> showRouteOverview() async => calls.add('overview');
}

void main() {
  test('R6-1 view created before grant: myLocation throw does not abort; after grant session init enables nav UI', () async {
    final sdk = FakeSdk();
    final engine = GoogleNavEngine(sdk: sdk);
    final view = FakeView(sdk);
    await engine.syncUserLocation(22.33, 114.17); // pending fix before view
    await engine.attachView(view);
    expect(view.calls, ['chrome'], reason: 'myLocation 拋錯都唔中斷；未初始化唔開 nav UI');
    expect(engine.myLocationOn, isFalse);
    expect(engine.navUiOn, isFalse);
    sdk.locationGranted = true;
    await engine.onLocationPermissionGranted();
    expect(
      view.calls,
      containsAllInOrder(['chrome', 'myLocation', 'navUi:true']),
    );
    expect(engine.navUiOn, isTrue);
    expect(sdk.initialized, isTrue);
    await engine.onLocationPermissionGranted();
    expect(
      view.calls.where((c) => c == 'navUi:true'),
      hasLength(1),
      reason: '唔重複',
    );
  });

  test(
    'R6-2 session initialized before view → view enables nav UI on creation',
    () async {
      final sdk = FakeSdk()..locationGranted = true;
      final engine = GoogleNavEngine(sdk: sdk);
      expect(await engine.ensureInitialized(), isTrue);
      final view = FakeView(sdk);
      await engine.attachView(view);
      expect(view.calls, ['chrome', 'myLocation', 'navUi:true']);
    },
  );

  test(
    'R6-3 route status logged/exposed; non-OK returns false without stop',
    () async {
      final sdk = FakeSdk()..locationGranted = true;
      final engine = GoogleNavEngine(sdk: sdk);
      sdk.status = NavigationRouteStatus.networkError;
      final logs = <String>[];
      final old = debugPrint;
      debugPrint = (String? m, {int? wrapWidth}) => logs.add(m ?? '');
      addTearDown(() => debugPrint = old);
      expect(
        await engine.startGuidanceTo(title: '朗豪坊', lat: 22.31, lng: 114.16),
        isFalse,
      );
      expect(engine.lastRouteStatus, 'networkError');
      expect(sdk.calls.contains('stop'), isFalse, reason: '失敗唔會停現有導航');
      expect(sdk.calls.contains('start'), isFalse);
      expect(
        logs.any((l) => l.contains('HKP_NAV route status=networkError')),
        isTrue,
      );
      sdk.status = NavigationRouteStatus.statusOk;
      expect(
        await engine.startGuidanceTo(title: '朗豪坊', lat: 22.31, lng: 114.16),
        isTrue,
      );
      expect(engine.lastRouteStatus, 'statusOk');
    },
  );

  test('R6-4 init failure logs sanitized error and retries later', () async {
    final sdk = FakeSdk();
    final engine = GoogleNavEngine(sdk: sdk);
    final logs = <String>[];
    final old = debugPrint;
    debugPrint = (String? m, {int? wrapWidth}) => logs.add(m ?? '');
    addTearDown(() => debugPrint = old);
    expect(await engine.ensureInitialized(), isFalse);
    expect(logs.join(), contains('AIza<redacted>'));
    expect(logs.join().contains('AIzaSyFAKE'), isFalse);
    sdk.locationGranted = true;
    expect(await engine.ensureInitialized(), isTrue);
    expect(sdk.calls.where((c) => c == 'initialize'), hasLength(2));
  });

  test('R6-5 replaced view: stale view gets no further calls', () async {
    final sdk = FakeSdk();
    final engine = GoogleNavEngine(sdk: sdk);
    final first = FakeView(sdk);
    await engine.attachView(first);
    final second = FakeView(sdk);
    await engine.attachView(second);
    sdk.locationGranted = true;
    await engine.onLocationPermissionGranted();
    expect(first.calls, ['chrome']);
    expect(second.calls, ['chrome', 'myLocation', 'navUi:true']);
  });

  test('R8-1 real GPS (simulateUserLocation=false): fixes never pushed to simulator; override cleared once', () async {
    final sdk = FakeSdk()..locationGranted = true;
    final engine = GoogleNavEngine(sdk: sdk);
    await engine.syncUserLocation(22.33, 114.17);
    await engine.syncUserLocation(22.34, 114.18);
    await engine.syncUserLocation(22.35, 114.19);
    expect(sdk.calls.where((c) => c == 'loc'), isEmpty);
    expect(sdk.calls.where((c) => c == 'unsetLoc'), hasLength(1));
  });

  test('R8-2 simulated build keeps pushing fixes to simulator', () async {
    final sdk = FakeSdk()..locationGranted = true;
    final engine = GoogleNavEngine(sdk: sdk, simulateUserLocation: true);
    await engine.syncUserLocation(22.33, 114.17);
    await engine.syncUserLocation(22.34, 114.18);
    expect(sdk.calls.where((c) => c == 'loc'), hasLength(2));
    expect(sdk.calls, isNot(contains('unsetLoc')));
  });

  test(
    'R9-1 map padding is kept until the view exists, then applied and cleared',
    () async {
      final sdk = FakeSdk()..locationGranted = true;
      final engine = GoogleNavEngine(sdk: sdk);
      await engine.setMapPadding(const EdgeInsets.only(bottom: 104));
      final view = FakeView(sdk);
      await engine.attachView(view);
      expect(view.calls, contains('padding:104'));
      await engine.setMapPadding(EdgeInsets.zero);
      expect(view.calls.last, 'padding:0');
    },
  );

  test('R8-3 real GPS: no simulator call before session initialized', () async {
    final sdk = FakeSdk(); // 未授權 → init 失敗
    final engine = GoogleNavEngine(sdk: sdk);
    await engine.syncUserLocation(22.33, 114.17);
    expect(sdk.calls, isNot(contains('loc')));
    expect(sdk.calls, isNot(contains('unsetLoc')));
  });
}
