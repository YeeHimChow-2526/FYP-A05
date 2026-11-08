import 'dart:async';

import 'demo_trip.dart';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'app_mode.dart';

import '../core/nav/fake_nav_engine.dart';
import '../core/nav/google_nav_engine.dart';
import '../core/nav/nav_engine.dart';
import '../core/net/api_client.dart';
import '../core/net/channel_fixture_transport.dart';
import '../core/net/http_api_transport.dart';
import '../core/platform/channel_prefs_port.dart';
import '../core/preferences/preference_manager.dart';
import '../core/poi/local_poi_trie.dart';
import '../core/poi/poi_name_resolver.dart';
import '../core/poi/stt_typo.dart';
import '../core/trip/api_client_sync_api.dart';
import '../core/trip/today_trip_store.dart';
import '../core/trip/today_trip_sync.dart';
import '../core/trip/trip_controller.dart';
import '../platform/trip_platform_channel.dart';

class TripRuntime {
  TripRuntime._();

  static final TripRuntime instance = TripRuntime._();

  TripController? controller;
  TodayTripStore? store;
  TodayTripSync? sync;
  ChannelTripPlatform? platform;
  NavEngine? navEngine;
  Future<void>? _attachFuture;

  Future<void> attach() {
    final current = _attachFuture;
    if (current != null) return current;
    final future = _attach();
    _attachFuture = future;
    return future.catchError((Object error, StackTrace stack) {
      if (identical(_attachFuture, future)) _attachFuture = null;
      Error.throwWithStackTrace(error, stack);
    });
  }

  static const String modePrefKey = 'hkp_app_mode';
  static const String preferencePrefKey = 'hkp_driver_preference';

  /// 測試用：模擬 release build（DEMO 被禁）。
  @visibleForTesting
  bool? releaseOverride;

  AppRunMode _mode = AppRunMode.normal;
  bool _configUseRealApi = false;
  bool _configUseRealNav = false;
  bool _configUseSimLocation = false;
  String _baseUrl = '';
  String _apiKey = '';
  ChannelPrefsPort? _prefs;
  PoiNameResolver? _resolver;

  /// 每次重建 controller（切模式）+1，畫面據此重新訂閱。
  final ValueNotifier<int> generation = ValueNotifier<int>(0);

  String _preferenceCode = DriverPreference.defaultPreference.code;

  /// 偏好嘅唯一來源：持久化喺 prefs，重建 controller 時沿用；只影響下一次 recommend，唔觸發重建。
  String get preferenceCode => _preferenceCode;

  void setPreferenceCode(String code) {
    final valid = DriverPreference.fromWire(code).code;
    _preferenceCode = valid;
    _prefs?.putString(preferencePrefKey, valid);
    controller?.preferenceCode = valid;
  }

  bool get demoAllowed => demoModeAllowed(releaseOverride: releaseOverride);

  AppModeInfo get modeInfo {
    final demo = _mode == AppRunMode.demo;
    return AppModeInfo(
      mode: _mode,
      liveApi: !demo && _configUseRealApi,
      // r9：DEMO 都用真 Google 地圖／導航（build 有開真導航時），只係定位同數據係模擬。
      realNavigation: _configUseRealNav,
      demoAllowed: demoAllowed,
    );
  }

  /// 有行程（session／FGS 啟動中）唔准切；release 唔准 DEMO。成功先重建 transport／nav／controller。
  Future<bool> setMode(AppRunMode next) async {
    await attach();
    if (next == _mode) return true;
    if (next == AppRunMode.demo && !demoAllowed) return false;
    final current = controller;
    if (current != null &&
        (current.sessionId != null ||
            current.tsm.fgsStartAccepted ||
            current.tsm.fgsRunning)) {
      return false;
    }
    _mode = next;
    _prefs?.putString(modePrefKey, next.wire);
    _demoSub?.cancel();
    demoPlayer?.stop();
    current?.dispose();
    _buildController();
    generation.value += 1;
    return true;
  }

  Future<void> _attach() async {
    final tripPlatform = ChannelTripPlatform(
      MethodChannel(ChannelTripPlatform.name),
    );
    final config = await tripPlatform.transportConfig();
    final prefs = ChannelPrefsPort(
      onWrite: (key, value) {
        if (value == null) {
          tripPlatform.prefsRemove(key);
        } else {
          tripPlatform.prefsPut(key, value);
        }
      },
    );
    final tripStore = TodayTripStore(
      prefs,
      nowMs: () => DateTime.now().millisecondsSinceEpoch,
    );
    final seed = await tripPlatform.prefsLoad();
    prefs.hydrate(seed);
    tripStore.reloadFromPrefs();
    final rawBase = config['baseUrl'];
    _baseUrl = rawBase is String ? rawBase : '';
    final rawKey = config['apiKey'];
    _apiKey = rawKey is String ? rawKey : '';
    _configUseRealApi = config['useRealApi'] == true;
    _configUseRealNav = config['useRealNavigation'] == true;
    _configUseSimLocation = config['useSimLocation'] == true;
    _mode = restoredMode(
      prefs.getString(modePrefKey),
      demoAllowed: demoAllowed,
    );
    final storedPreference = prefs.getString(preferencePrefKey);
    if (storedPreference != null) {
      try {
        _preferenceCode = DriverPreference.fromWire(storedPreference).code;
      } on ArgumentError {
        _preferenceCode = DriverPreference.defaultPreference.code;
      }
    }
    try {
      final raw = await rootBundle.loadString('assets/poi/hk_poi_master.json');
      _resolver = PoiNameResolver(
        trie: LocalPoiTrie.fromJsonString(raw),
        typo: SttTypoCorpus(const {}),
      );
    } catch (_) {
      _resolver = null;
    }
    _prefs = prefs;
    platform = tripPlatform;
    store = tripStore;
    _buildController();
  }

  void _buildController() {
    final tripPlatform = platform!;
    final tripStore = store!;
    final info = modeInfo;
    final engine = engineFor(info, simLocationConfig: _configUseSimLocation);
    final transport = info.liveApi
        ? HttpApiTransport(
            baseUrl: _baseUrl,
            apiKey: _apiKey,
            allowLocalCleartext: _baseUrl.startsWith('http://'),
          )
        : ChannelFixtureTransport(tripPlatform.fixturePost);
    final api = ApiClient(transport);
    final network = TripNetwork(true);
    final tripSync = TodayTripSync(
      tripStore,
      ApiClientSyncApi(api),
      network,
      nowMs: () => DateTime.now().millisecondsSinceEpoch,
    );
    final tripController = TripController(
      platform: tripPlatform,
      api: api,
      store: tripStore,
      sync: tripSync,
      nowMs: () => DateTime.now().millisecondsSinceEpoch,
      delay: (ms) => Future<void>.delayed(Duration(milliseconds: ms)),
      network: network,
      resolver: _resolver,
      navEngine: engine,
    );
    tripController.preferenceCode = _preferenceCode;
    tripController.isolateDemonstrationSupply = info.liveApi;
    navEngine = engine;
    tripPlatform.bind(tripController);
    _wireDemo(tripController, info.isDemo);
    controller = tripController;
    sync = tripSync;
  }

  /// r9：DEMO 固定行程重播（只喺 DEMO 模式）。
  DemoTripPlayer? demoPlayer;
  StreamSubscription<TripSnapshot>? _demoSub;
  Object _demoToken = Object();

  void _wireDemo(TripController c, bool demo) {
    _demoSub?.cancel();
    _demoSub = null;
    demoPlayer?.stop();
    demoPlayer = null;
    // 令已排程、但屬於上一個 controller 嘅回呼失效。
    _demoToken = Object();
    c.deviceFixesEnabled = true;
    if (!demo) return;
    final player = DemoTripPlayer(c);
    demoPlayer = player;
    final token = _demoToken;
    _demoSub = listenForDemoPlayback(
      c,
      player,
      stillCurrent: () => identical(_demoToken, token),
    );
  }

  /// FGS 開波嗰下 `changes` 係同步廣播。若果監聽器入面即刻 `lockDestinationAt`，
  /// 會喺同一個 `_emit` 再 `_emit`，廣播流直接拋 `Cannot fire new event`，
  /// 導航目的地同重播計時器都去唔到。等呢個事件派完先同步。
  @visibleForTesting
  static StreamSubscription<TripSnapshot> listenForDemoPlayback(
    TripController c,
    DemoTripPlayer player, {
    bool Function()? stillCurrent,
  }) {
    var scheduled = false;
    return c.changes.listen((_) {
      if (scheduled) return;
      scheduled = true;
      scheduleMicrotask(() {
        scheduled = false;
        if (stillCurrent != null && !stillCurrent()) return;
        syncDemoPlayer(c, player);
      });
    });
  }

  /// prefs 讀返嚟嘅模式。release 唔准 DEMO，退回正常。
  @visibleForTesting
  static AppRunMode restoredMode(String? stored, {required bool demoAllowed}) {
    final mode = AppRunMode.fromWire(stored);
    if (mode == AppRunMode.demo && !demoAllowed) return AppRunMode.normal;
    return mode;
  }

  /// 行程開始 → 由起點播；行程結束／取消 → 停播＋重設＋還原裝置 GPS 入口。
  static void syncDemoPlayer(TripController c, DemoTripPlayer player) {
    final inTrip = c.sessionId != null && c.tsm.fgsRunning;
    if (inTrip && !player.running) {
      player.start();
    } else if (!inTrip && player.running) {
      player.stop();
      c.deviceFixesEnabled = true;
    }
  }

  /// r9：導航引擎選擇。真導航 build：正常＝真 GPS（唔推 simulator）；DEMO＝真地圖＋simulator 重播。
  @visibleForTesting
  static NavEngine engineFor(
    AppModeInfo info, {
    required bool simLocationConfig,
  }) {
    if (!info.realNavigation) return FakeNavEngine();
    return GoogleNavEngine(
      simulateUserLocation: info.isDemo || simLocationConfig,
    );
  }
}
