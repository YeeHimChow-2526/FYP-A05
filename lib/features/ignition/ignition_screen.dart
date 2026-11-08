import 'dart:async';

import 'package:copilot/app/app_mode.dart';
import 'package:copilot/app/trip_runtime.dart';
import 'package:copilot/core/constants/app_colors.dart';
import 'package:copilot/core/nav/nav_engine.dart';
import 'package:copilot/core/platform/overlay_channel.dart';
import 'package:copilot/core/platform/trip_permissions.dart';
import 'package:copilot/core/preferences/preference_manager.dart';
import 'package:copilot/core/trip/trip_controller.dart';
import 'package:copilot/core/trip/trip_types.dart';
import 'package:copilot/core/voice/voice_capability.dart';
import 'package:copilot/features/nav_shell/home_map_backdrop.dart';
import 'package:copilot/features/nav_shell/nav_shell_screen.dart';
import 'package:copilot/features/nav_shell/pre_trip_bottom_bar.dart';
import 'package:copilot/features/settings/tutorial_screen.dart';
import 'package:copilot/features/trip_history/today_trip_screen.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

class IgnitionScreen extends StatefulWidget {
  const IgnitionScreen({super.key});

  @override
  State<IgnitionScreen> createState() => _IgnitionScreenState();
}

class _IgnitionScreenState extends State<IgnitionScreen> with WidgetsBindingObserver {
  final PreferenceManager _preferences = PreferenceManager();
  String _nativeState = '';
  String _nativeDataJson = '';
  String? _permissionBanner;
  String? _permissionStatus;
  final TripPermissionPort _permPort = TripPermissionPort();
  PermissionSnapshot? _perms;
  StartGateResult? _blocked;
  bool _starting = false;

  /// 底部四分頁：0 偏好｜1 地圖（預設）｜2 今日行程｜3 設定（權限＋教學）。
  static const int tabPreferences = 0;
  static const int tabMap = 1;
  static const int tabToday = 2;
  static const int tabSettings = 3;
  int _tab = tabMap;
  TripController? _watched;
  StreamSubscription<TripSnapshot>? _tripChanges;

  NavEngine? get _navEngine =>
      TripRuntime.instance.navEngine ??
      TripRuntime.instance.controller?.navEngine;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    OverlayChannel.attach();
    OverlayChannel.tripStateListeners.add(_onTripStateChanged);
    TripRuntime.instance.generation.addListener(_onRuntimeRebuilt);
    unawaited(_ensureRuntime());
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _tripChanges?.cancel();
    OverlayChannel.tripStateListeners.remove(_onTripStateChanged);
    TripRuntime.instance.generation.removeListener(_onRuntimeRebuilt);
    super.dispose();
  }

  Future<void> _ensureRuntime() async {
    try {
      await TripRuntime.instance.attach();
      final engine = _navEngine;
      if (engine != null) {
        unawaited(engine.ensureInitialized());
      }
    } catch (_) {}
    _preferences.preference = DriverPreference.fromWire(TripRuntime.instance.preferenceCode);
    _watchTrip();
    if (mounted) setState(() {});
  }

  void _watchTrip() {
    final controller = TripRuntime.instance.controller;
    if (identical(controller, _watched)) return;
    _tripChanges?.cancel();
    _watched = controller;
    if (controller == null) return;
    _tripChanges = controller.changes.listen((_) {
      if (mounted) setState(() {});
    });
  }

  void _onRuntimeRebuilt() {
    _watchTrip();
    if (mounted) setState(() {});
  }

  void _onTripStateChanged(String state, String dataJson) {
    setState(() {
      _nativeState = state;
      _nativeDataJson = dataJson;
    });
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) unawaited(_onResumed());
  }

  /// t1253：返回 App 重新檢查。行程中精確位置被收回 → 安全結束（停 FGS、導航、釋放鎖位）。
  /// 語音能力同步重算；用戶關咗語音就唔會重開。
  Future<void> _onResumed() async {
    final snap = await _permPort.states();
    if (!mounted) return;
    setState(() => _perms = snap);
    if (!snap.nativeAvailable || snap[TripPermissionKind.location].granted) {
      unawaited(_navEngine?.onLocationPermissionGranted());
    }
    final controller = TripRuntime.instance.controller;
    if (controller == null || !controller.tripRunning) return;
    if (snap.nativeAvailable && !snap[TripPermissionKind.location].granted) {
      await controller.abortTripByUser();
      if (!mounted) return;
      setState(() {
        _blocked = StartGateResult.locationDenied;
        _permissionBanner = '精確位置權限已被收回，行程已安全結束。';
      });
      return;
    }
    controller.updateVoiceCapability(await _voiceCapability());
  }

  Future<CapabilitySnapshot> _voiceCapability() async =>
      deriveVoiceCapability(await _permPort.voiceFacts(), userVoiceEnabled: _preferences.hotwordEnabled);

  Future<bool> _explainLocation() async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        key: const Key('location_explain_dialog'),
        title: const Text('需要精確位置'),
        content: const Text('泊車副駕要用精確位置計你同目的地嘅距離，喺大約 1 km 前推介停車場。只會喺行程期間（App 開住或前景服務通知顯示時）使用，唔會背景追蹤。'),
        actions: [
          TextButton(key: const Key('location_explain_cancel'), onPressed: () => Navigator.of(ctx).pop(false), child: const Text('唔係而家')),
          FilledButton(key: const Key('location_explain_continue'), onPressed: () => Navigator.of(ctx).pop(true), child: const Text('繼續')),
        ],
      ),
    );
    return ok == true;
  }

  static String _blockedText(StartGateResult r) => switch (r) {
        StartGateResult.locationNeedsSettings => '未開到行程：需要「精確位置」。Android 唔會再彈對話框，請去系統設定 → 權限 → 位置，揀「使用 App 時允許」同開「使用精確位置」。',
        StartGateResult.locationServicesOff => '未開到行程：手機定位（GPS）未開。請喺快速設定開定位再試。',
        _ => '未開到行程：需要「精確位置」先可以喺目的地附近推介停車場。通知、藍牙、咪都係可選，唔使開都得。',
      };

  Future<void> _startFromGesture({bool onSite = false}) async {
    if (_starting) return;
    _starting = true;
    try {
      await TripRuntime.instance.attach();
      _watchTrip();
      final gate = await StartPermissionFlow(_permPort).ensureForStart(explainLocation: _explainLocation);
      if (!mounted) return;
      if (gate != StartGateResult.allowed) {
        setState(() {
          _blocked = gate;
          _permissionBanner = _blockedText(gate);
        });
        return;
      }
      setState(() => _blocked = null);
      unawaited(_navEngine?.onLocationPermissionGranted());
      await TripRuntime.instance.platform!.checkStartGate();
      final controller = TripRuntime.instance.controller!;
      final voice = await _voiceCapability();
      // 現場即泊：StartupEntry.onSite；FGS 起咗之後第一個精度 ≤100 m 嘅 fix 鎖做目的地並即時 recommend。
      final outcome =
          onSite ? await controller.startOnSite(voice: voice) : await controller.start(StartupEntry.a2, voice: voice);
      if (!mounted) return;
      setState(() {
        _permissionBanner = outcome.accepted ? null : '未能開始行程';
      });
    } on MissingPluginException {
      return;
    } on PlatformException catch (error) {
      if (!mounted) return;
      await _showPermissionGap(error);
    } finally {
      _starting = false;
    }
  }

  Future<void> _requestFromSettings(TripPermissionKind kind) async {
    final info = (_perms ?? await _permPort.states())[kind];
    if (info.needsSettings) {
      await _permPort.openAppSettings();
    } else {
      await _permPort.request(kind);
    }
    await _refreshPermissions();
    final controller = TripRuntime.instance.controller;
    if (kind == TripPermissionKind.microphone && controller != null && controller.tripRunning) {
      controller.updateVoiceCapability(await _voiceCapability());
    }
  }

  Future<void> _selectPreference(DriverPreference value) async {
    try {
      TripRuntime.instance.setPreferenceCode(value.code);
      await _preferences.setPreference(value);
    } on PlatformException catch (error) {
      await _showPermissionGap(error);
    } on MissingPluginException {
      _preferences.preference = value;
    }
    if (mounted) {
      setState(() {});
    }
  }

  Future<void> _toggleHotword(bool enabled) async {
    if (enabled) {
      var micGranted = true;
      try {
        micGranted = await OverlayChannel.requestRecordAudioPermission();
      } on PlatformException {
        micGranted = false;
      } on MissingPluginException {
        micGranted = true; // 測試／非 Android 環境：冇原生通道
      }
      if (!micGranted && mounted) {
        ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('未有咪權限：語音指令用唔到，照用手動掣。')));
      }
    }
    try {
      await _preferences.setHotwordEnabled(enabled);
    } on PlatformException catch (error) {
      await _showPermissionGap(error);
    } on MissingPluginException {
      _preferences.hotwordEnabled = enabled;
    }
    final controller = TripRuntime.instance.controller;
    if (controller != null && controller.tripRunning) {
      controller.updateVoiceCapability(await _voiceCapability());
    }
    if (mounted) {
      setState(() {});
    }
  }

  Future<void> _showPermissionGap(PlatformException error) async {
    Object? status;
    try {
      status = await OverlayChannel.getPermissionStatus();
    } on PlatformException catch (statusError) {
      status = statusError.message;
    } on MissingPluginException {
      status = null;
    }
    if (!mounted) return;
    final missing = error.details is Map
        ? (error.details as Map)['missingItems']
        : null;
    final banner = missing == null
        ? '請查看主畫面頂部權限狀態橫額。請泊好車後再處理。'
        : '權限未齊：$missing。請查看主畫面頂部權限狀態橫額。請泊好車後再處理。';
    setState(() {
      _permissionBanner = status == null ? banner : '$banner $status';
    });
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(const SnackBar(content: Text('請查看主畫面頂部權限狀態橫額。請泊好車後再處理。')));
  }

  Future<void> _setDemo(bool demo) async {
    final ok = await TripRuntime.instance.setMode(demo ? AppRunMode.demo : AppRunMode.normal);
    if (!mounted) return;
    if (!ok) {
      final reason = !TripRuntime.instance.demoAllowed ? 'Release 版只可以用正常模式' : '行程進行中唔可以切換模式';
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(reason)));
    }
    setState(() {});
  }

  Future<void> _refreshPermissions() async {
    final snap = await _permPort.states();
    if (mounted) setState(() => _perms = snap);
    Object? status;
    try {
      status = await OverlayChannel.getPermissionStatus();
    } on PlatformException catch (error) {
      status = error.message;
    } on MissingPluginException {
      status = '（呢個環境冇原生權限通道）';
    }
    if (!mounted) return;
    setState(() => _permissionStatus = '${status ?? '—'}');
  }

  Widget _modeMarker(Key key) {
    final info = TripRuntime.instance.modeInfo;
    return Material(
      key: key,
      color: info.isDemo ? AppColors.amberSteady : AppColors.cardBackground,
      borderRadius: BorderRadius.circular(8),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
        child: Text(
          info.marker,
          style: TextStyle(color: info.isDemo ? Colors.black : AppColors.textPrimary, fontSize: 13),
        ),
      ),
    );
  }

  Widget _preferencesTab() {
    return ListView(
      key: const Key('tab_preferences'),
      padding: const EdgeInsets.all(16),
      children: [
        Text('偏好', style: TextStyle(color: AppColors.textPrimary, fontSize: 20)),
        const SizedBox(height: 8),
        RadioGroup<DriverPreference>(
          groupValue: _preferences.preference,
          onChanged: (value) {
            if (value != null) unawaited(_selectPreference(value));
          },
          child: Column(
            children: [
              for (final item in _preferenceItems)
                RadioListTile<DriverPreference>(
                  key: Key('pref_${item.$1.code}'),
                  value: item.$1,
                  title: Text(item.$2, style: TextStyle(color: AppColors.textPrimary)),
                ),
            ],
          ),
        ),
        SwitchListTile(
          key: const Key('pref_hotword'),
          title: Text('本程熱詞「泊車助手」', style: TextStyle(color: AppColors.textPrimary)),
          value: _preferences.hotwordEnabled,
          activeThumbColor: AppColors.statusVacancyGreen,
          onChanged: (enabled) => unawaited(_toggleHotword(enabled)),
        ),
      ],
    );
  }

  Widget _permissionRow(TripPermissionKind kind) {
    final info = _perms?[kind];
    final subtitle = info == null ? '撳上面重新檢查' : permissionStateLabel(info);
    final explain = switch (kind) {
      TripPermissionKind.location => '用嚟計目的地距離同推介停車場；冇就開唔到行程。',
      TripPermissionKind.notifications => '行程提示；拒絕都照開程。',
      TripPermissionKind.bluetooth => '偵測車機藍牙斷開＝熄匙。冇藍牙就唔會自動出現「可能已泊入」。',
      TripPermissionKind.microphone => '語音指令；冇就照用語音提示＋手動掣。',
    };
    final actionable = info != null && !info.granted && info.state != PermissionState.notApplicable;
    return ListTile(
      key: Key('perm_row_${kind.name}'),
      title: Text(permissionLabel(kind), style: TextStyle(color: AppColors.textPrimary)),
      subtitle: Text('$subtitle｜$explain', style: TextStyle(color: AppColors.textSecondary)),
      trailing: actionable
          ? TextButton(
              key: Key('perm_action_${kind.name}'),
              onPressed: () => unawaited(_requestFromSettings(kind)),
              child: Text(info.needsSettings ? '系統設定' : '允許'),
            )
          : null,
    );
  }

  Widget _settingsTab() {
    final info = TripRuntime.instance.modeInfo;
    return ListView(
      key: const Key('tab_settings'),
      padding: const EdgeInsets.all(16),
      children: [
        Text('設定', style: TextStyle(color: AppColors.textPrimary, fontSize: 20)),
        const SizedBox(height: 8),
        _modeMarker(const Key('settings_mode_marker')),
        SwitchListTile(
          key: const Key('demo_mode_switch'),
          title: Text('DEMO 模式', style: TextStyle(color: AppColors.textPrimary)),
          subtitle: Text(
            info.demoAllowed ? '示範數據＋模擬導航；流程同正常模式一樣。行程中唔可以切換。' : 'Release 版一律正常模式（即時數據）',
            style: TextStyle(color: AppColors.textSecondary),
          ),
          value: info.isDemo,
          onChanged: info.demoAllowed ? (v) => unawaited(_setDemo(v)) : null,
        ),
        const Divider(),
        ListTile(
          key: const Key('settings_permissions'),
          title: Text('權限狀態', style: TextStyle(color: AppColors.textPrimary)),
          subtitle: Text(_permissionStatus ?? '撳右邊重新檢查', style: TextStyle(color: AppColors.textSecondary)),
          trailing: IconButton(
            key: const Key('settings_permissions_refresh'),
            icon: const Icon(Icons.refresh, color: AppColors.textPrimary),
            onPressed: () => unawaited(_refreshPermissions()),
          ),
        ),
        ListTile(
          key: const Key('settings_tutorial'),
          title: Text('新手教學與運作模式', style: TextStyle(color: AppColors.textPrimary)),
          trailing: const Icon(Icons.chevron_right, color: AppColors.textPrimary),
          onTap: () => Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => const TutorialScreen())),
        ),
        for (final kind in TripPermissionKind.values) _permissionRow(kind),
        const Divider(),
        Text(
          _nativeState.isEmpty ? '原生狀態 —' : '原生狀態 $_nativeState',
          style: TextStyle(color: AppColors.textSecondary),
        ),
        if (_nativeDataJson.isNotEmpty) Text(_nativeDataJson, style: TextStyle(color: AppColors.textSecondary)),
      ],
    );
  }

  Widget _mapTab() {
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          children: [
            Align(alignment: Alignment.topLeft, child: _modeMarker(const Key('map_mode_marker'))),
            if (_permissionBanner != null)
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: Material(
                  color: AppColors.cardBackground,
                  child: Padding(
                    padding: const EdgeInsets.all(12),
                    child: Column(
                      key: const Key('permission_banner'),
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(_permissionBanner!, style: TextStyle(color: AppColors.amberSteady, fontSize: 14)),
                        if (_blocked != null)
                          Row(
                            children: [
                              if (_blocked != StartGateResult.locationNeedsSettings)
                                TextButton(
                                  key: const Key('permission_retry'),
                                  onPressed: () => unawaited(_startFromGesture()),
                                  child: const Text('再試'),
                                ),
                              TextButton(
                                key: const Key('permission_open_settings'),
                                onPressed: () => unawaited(_permPort.openAppSettings()),
                                child: const Text('去系統設定'),
                              ),
                            ],
                          ),
                      ],
                    ),
                  ),
                ),
              ),
            const Spacer(),
            PreTripBottomBar(
              onDepart: () => unawaited(_startFromGesture()),
              onHere: () => unawaited(_startFromGesture(onSite: true)),
            ),
          ],
        ),
      ),
    );
  }

  Widget _tabBody() {
    final Widget page = switch (_tab) {
      tabPreferences => _preferencesTab(),
      tabToday => const KeyedSubtree(key: Key('tab_today'), child: TodayTripScreen()),
      tabSettings => _settingsTab(),
      _ => _mapTab(),
    };
    if (_tab == tabMap) return page;
    return Positioned.fill(child: Material(color: AppColors.surface, child: SafeArea(child: page)));
  }

  @override
  Widget build(BuildContext context) {
    final controller = TripRuntime.instance.controller;
    final inTrip = controller != null && controller.snapshot.sessionId != null;
    final engine = _navEngine ?? controller?.navEngine;
    return Scaffold(
      backgroundColor: AppColors.surface,
      extendBody: true,
      body: Stack(
        children: [
          Positioned.fill(
            child: HomeMapBackdrop(
              key: const Key('stable_nav_view'),
              navEngine: engine,
            ),
          ),
          if (inTrip)
            InTripOverlay(
              controller: controller,
              navEngine: engine ?? controller.navEngine,
            )
          else
            _tabBody(),
        ],
      ),
      // 行程中底部分頁收起，只剩 InTripOverlay 嘅圓形按鈕。
      bottomNavigationBar: inTrip
          ? null
          : NavigationBar(
              key: const Key('pre_trip_bottom_bar'),
              selectedIndex: _tab,
              onDestinationSelected: (i) => setState(() => _tab = i),
              destinations: const [
                NavigationDestination(key: Key('nav_tab_preferences'), icon: Icon(Icons.tune), label: '偏好'),
                NavigationDestination(key: Key('nav_tab_map'), icon: Icon(Icons.map), label: '地圖'),
                NavigationDestination(key: Key('nav_tab_today'), icon: Icon(Icons.today), label: '今日行程'),
                NavigationDestination(key: Key('nav_tab_settings'), icon: Icon(Icons.settings), label: '設定'),
              ],
            ),
    );
  }
}

const _preferenceItems = <(DriverPreference, String)>[
  (DriverPreference.indoorOnly, '只揀室內停車場'),
  (DriverPreference.smartHybrid, '智能混合（預設）'),
  (DriverPreference.metersFocus, '咪錶優先'),
];
