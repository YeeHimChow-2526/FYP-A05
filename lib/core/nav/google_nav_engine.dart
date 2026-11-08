import 'package:flutter/material.dart';
import 'package:google_navigation_flutter/google_navigation_flutter.dart';

import 'nav_engine.dart';

/// google_navigation_flutter 靜態 API 嘅薄包裝（測試可換）。
class NavSdk {
  const NavSdk();
  Future<bool> areTermsAccepted() => GoogleMapsNavigator.areTermsAccepted();
  Future<bool> showTerms() =>
      GoogleMapsNavigator.showTermsAndConditionsDialog('泊車副駕', '泊車副駕');
  Future<bool> isInitialized() => GoogleMapsNavigator.isInitialized();
  Future<void> initialize() => GoogleMapsNavigator.initializeNavigationSession(
    taskRemovedBehavior: TaskRemovedBehavior.quitService,
    notificationOptions: const NavigationNotificationOptions(
      notificationId: GoogleNavEngine.navigationNotificationId,
      defaultMessage: '導航進行中',
      resumeAppOnTap: true,
    ),
  );
  Future<void> setUserLocation(double lat, double lng) => GoogleMapsNavigator
      .simulator
      .setUserLocation(LatLng(latitude: lat, longitude: lng));
  Future<void> removeUserLocation() =>
      GoogleMapsNavigator.simulator.removeUserLocation();
  Future<NavigationRouteStatus> setDestination(
    String title,
    double lat,
    double lng,
  ) => GoogleMapsNavigator.setDestinations(
    Destinations(
      waypoints: [
        NavigationWaypoint.withLatLngTarget(
          title: title,
          target: LatLng(latitude: lat, longitude: lng),
        ),
      ],
      displayOptions: NavigationDisplayOptions(showDestinationMarkers: true),
    ),
  );
  Future<void> startGuidance() => GoogleMapsNavigator.startGuidance();
  Future<void> stopGuidance() async {
    await GoogleMapsNavigator.stopGuidance();
    await GoogleMapsNavigator.clearDestinations();
  }

  Future<void> setAudio(bool enabled) => GoogleMapsNavigator.setAudioGuidance(
    NavigationAudioGuidanceSettings(
      guidanceType: enabled
          ? NavigationAudioGuidanceType.alertsAndGuidance
          : NavigationAudioGuidanceType.silent,
    ),
  );
}

/// 地圖 view controller 用到嘅操作（測試可換）。
abstract class NavViewOps {
  Future<void> setMyLocationEnabled(bool enabled);
  Future<void> setNavigationUIEnabled(bool enabled);
  Future<void> configureChrome();
  Future<void> setPadding(EdgeInsets padding);
  Future<void> showRouteOverview();
}

class _ControllerOps implements NavViewOps {
  _ControllerOps(this.c);
  final GoogleNavigationViewController c;
  @override
  Future<void> setMyLocationEnabled(bool enabled) =>
      c.setMyLocationEnabled(enabled);
  @override
  Future<void> setNavigationUIEnabled(bool enabled) =>
      c.setNavigationUIEnabled(enabled);
  @override
  Future<void> configureChrome() async {
    await c.setReportIncidentButtonEnabled(false);
    await c.setRecenterButtonEnabled(true);
    await c.setNavigationHeaderEnabled(true);
    await c.setNavigationFooterEnabled(true);
  }

  @override
  Future<void> setPadding(EdgeInsets padding) => c.setPadding(padding);

  @override
  Future<void> showRouteOverview() => c.showRouteOverview();
}

/// 去掉疑似 key／token 再寫 logcat。
String sanitizeNavError(Object error) {
  final text = error.toString().replaceAll(
    RegExp(r'AIza[0-9A-Za-z_\-]{10,}'),
    'AIza<redacted>',
  );
  return text.length > 300 ? '${text.substring(0, 300)}…' : text;
}

/// t1266（r6）：google_navigation_flutter 0.11.2 Android 只喺 view 建立嗰刻（session 已 INITIALIZED＋AUTOMATIC）
/// 先開導航 UI（GoogleMapsNavigationView.kt init）。我哋嘅地圖喺 App 啟動就建立，嗰陣通常未初始化（未批位置）
/// → 導航 UI 一直關（冇藍線、冇頂部指示）。修正：session 初始化成功後對現有 view 明確 setNavigationUIEnabled(true)；
/// view 後建立就喺 onViewCreated 開。setMyLocationEnabled 失敗（未批位置）唔會中斷其他設定，批咗再試。
class GoogleNavEngine extends NavEngine {
  GoogleNavEngine({
    NavSdk sdk = const NavSdk(),
    this.simulateUserLocation = false,
  }) : _sdk = sdk; // ignore: prefer_initializing_formals

  /// r8：true（模擬定位 build）先將 app fix 推入 SDK simulator；false（真 GPS）唔推，
  /// session ready 後叫一次 removeUserLocation（文件：unset simulated location）清走殘留覆蓋。
  final bool simulateUserLocation;
  bool _simCleared = false;

  static const int navigationNotificationId = 42;

  final NavSdk _sdk;
  bool _audioWanted = true;
  bool _audioSuspended = false;
  NavViewOps? _view;
  int _viewGeneration = 0;
  Future<bool>? _initializing;
  bool _ready = false;
  double? _pendingLat;
  double? _pendingLng;
  String? _lastRouteStatus;
  bool myLocationOn = false;
  bool navUiOn = false;
  EdgeInsets _mapPadding = EdgeInsets.zero;

  @override
  String? get lastRouteStatus => _lastRouteStatus;

  @override
  Widget buildNavView({Key? key}) {
    return SizedBox.expand(
      child: GoogleMapsNavigationView(
        key: key,
        initialCameraPosition: const CameraPosition(
          target: LatLng(latitude: 22.3193, longitude: 114.1694),
          zoom: 15,
        ),
        initialCompassEnabled: true,
        initialZoomControlsEnabled: false,
        initialMapToolbarEnabled: false,
        initialNavigationUIEnabledPreference:
            NavigationUIEnabledPreference.automatic,
        onViewCreated: (c) => attachView(_ControllerOps(c)),
      ),
    );
  }

  /// 新 view（或重建）：舊 view 嘅未完成操作會因 generation 唔同而跳過。
  @visibleForTesting
  Future<void> attachView(NavViewOps view) async {
    final gen = ++_viewGeneration;
    _view = view;
    myLocationOn = false;
    navUiOn = false;
    await _safe('configureChrome', view.configureChrome);
    if (gen != _viewGeneration) return;
    if (_mapPadding != EdgeInsets.zero) {
      await _safe('setPadding', () => view.setPadding(_mapPadding));
      if (gen != _viewGeneration) return;
    }
    await _applyViewState(gen);
    final lat = _pendingLat;
    final lng = _pendingLng;
    if (lat != null && lng != null && gen == _viewGeneration) {
      await syncUserLocation(lat, lng);
    }
  }

  Future<void> _applyViewState(int gen) async {
    final view = _view;
    if (view == null || gen != _viewGeneration) return;
    if (!myLocationOn) {
      myLocationOn = await _safe(
        'setMyLocationEnabled',
        () => view.setMyLocationEnabled(true),
      );
    }
    if (_ready && !navUiOn && gen == _viewGeneration) {
      navUiOn = await _safe(
        'setNavigationUIEnabled',
        () => view.setNavigationUIEnabled(true),
      );
    }
  }

  Future<bool> _safe(String what, Future<void> Function() op) async {
    try {
      await op();
      return true;
    } catch (error) {
      debugPrint('HKP_NAV $what failed: ${sanitizeNavError(error)}');
      return false;
    }
  }

  @override
  Future<void> onLocationPermissionGranted() async {
    await ensureInitialized();
    await _applyViewState(_viewGeneration);
  }

  @override
  Future<bool> ensureInitialized() {
    if (_ready) return Future<bool>.value(true);
    final current = _initializing;
    if (current != null) return current;
    final future = _initialize();
    _initializing = future;
    return future;
  }

  Future<bool> _initialize() async {
    try {
      if (!await _sdk.areTermsAccepted()) {
        final accepted = await _sdk.showTerms();
        if (!accepted) {
          debugPrint('HKP_NAV terms not accepted');
          _initializing = null;
          return false;
        }
      }
      if (!await _sdk.isInitialized()) {
        await _sdk.initialize();
      }
      _ready = true;
      debugPrint('HKP_NAV session initialized');
      await _applyViewState(_viewGeneration);
      return true;
    } catch (error) {
      debugPrint('HKP_NAV initialize failed: ${sanitizeNavError(error)}');
      _initializing = null;
      _ready = false;
      return false;
    }
  }

  @override
  Future<void> syncUserLocation(double lat, double lng) async {
    _pendingLat = lat;
    _pendingLng = lng;
    if (!await ensureInitialized()) return;
    if (!simulateUserLocation) {
      if (!_simCleared) {
        _simCleared = true;
        await _safe('removeUserLocation', _sdk.removeUserLocation);
      }
      return;
    }
    await _safe('setUserLocation', () => _sdk.setUserLocation(lat, lng));
  }

  @override
  Future<bool> startGuidanceTo({
    required String title,
    required double lat,
    required double lng,
  }) async {
    if (!await ensureInitialized()) {
      _lastRouteStatus = 'notInitialized';
      debugPrint('HKP_NAV route skipped: session not initialized');
      return false;
    }
    final target =
        'target="$title" lat=${lat.toStringAsFixed(4)} lng=${lng.toStringAsFixed(4)}';
    debugPrint('HKP_NAV route request $target');
    try {
      final status = await _sdk.setDestination(title, lat, lng);
      _lastRouteStatus = status.name;
      debugPrint('HKP_NAV route status=${status.name} $target');
      if (status != NavigationRouteStatus.statusOk) return false;
      await _sdk.startGuidance();
      await _applyViewState(_viewGeneration);
      return true;
    } catch (error) {
      _lastRouteStatus = 'exception';
      debugPrint('HKP_NAV route failed: ${sanitizeNavError(error)}');
      return false;
    }
  }

  @override
  Future<void> setTurnByTurnAudioEnabled(bool enabled) async {
    _audioWanted = enabled;
    if (_audioSuspended) return;
    await _pushTurnByTurnAudio(enabled);
  }

  @override
  Future<void> suspendTurnByTurnAudio() async {
    if (_audioSuspended || !_audioWanted) return;
    _audioSuspended = true;
    await _pushTurnByTurnAudio(false);
  }

  @override
  Future<void> resumeTurnByTurnAudio() async {
    if (!_audioSuspended) return;
    _audioSuspended = false;
    await _pushTurnByTurnAudio(_audioWanted);
  }

  Future<void> _pushTurnByTurnAudio(bool enabled) async {
    if (!await ensureInitialized()) {
      debugPrint('HKP_NAV audio guidance enabled=$enabled ok=false reason=not_ready');
      return;
    }
    final ok = await _safe('setAudioGuidance', () => _sdk.setAudio(enabled));
    debugPrint('HKP_NAV audio guidance enabled=$enabled ok=$ok');
  }

  @override
  Future<void> setMapPadding(EdgeInsets padding) async {
    if (_mapPadding == padding) return;
    _mapPadding = padding;
    final view = _view;
    if (view == null) return;
    await _safe('setPadding', () => view.setPadding(padding));
  }

  @override
  Future<void> showRouteOverview() async {
    final view = _view;
    if (view == null) return;
    await _safe('showRouteOverview', view.showRouteOverview);
  }

  @override
  Future<void> stopGuidance() async {
    try {
      if (await _sdk.isInitialized()) await _sdk.stopGuidance();
    } catch (error) {
      debugPrint('HKP_NAV stopGuidance failed: ${sanitizeNavError(error)}');
    }
  }
}
