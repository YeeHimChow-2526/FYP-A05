import 'dart:async';

import 'package:copilot/app/trip_runtime.dart';
import 'package:copilot/core/constants/app_colors.dart';
import 'package:copilot/core/nav/nav_engine.dart';
import 'package:copilot/core/platform/overlay_channel.dart';
import 'package:copilot/core/preferences/preference_manager.dart';
import 'package:copilot/core/trip/trip_controller.dart';
import 'package:copilot/core/trip/trip_types.dart';
import 'package:copilot/features/nav_shell/home_map_backdrop.dart';
import 'package:copilot/features/nav_shell/nav_shell_screen.dart';
import 'package:copilot/features/nav_shell/pre_trip_bottom_bar.dart';
import 'package:copilot/features/trip_history/today_trip_screen.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

class IgnitionScreen extends StatefulWidget {
  const IgnitionScreen({super.key});

  @override
  State<IgnitionScreen> createState() => _IgnitionScreenState();
}

class _IgnitionScreenState extends State<IgnitionScreen> {
  final PreferenceManager _preferences = PreferenceManager();
  String _nativeState = '';
  String _nativeDataJson = '';
  String? _permissionBanner;
  TripController? _watched;
  StreamSubscription<TripSnapshot>? _tripChanges;

  NavEngine? get _navEngine =>
      TripRuntime.instance.navEngine ??
      TripRuntime.instance.controller?.navEngine;

  @override
  void initState() {
    super.initState();
    OverlayChannel.attach();
    OverlayChannel.tripStateListeners.add(_onTripStateChanged);
    unawaited(_ensureRuntime());
  }

  @override
  void dispose() {
    _tripChanges?.cancel();
    OverlayChannel.tripStateListeners.remove(_onTripStateChanged);
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

  void _onTripStateChanged(String state, String dataJson) {
    setState(() {
      _nativeState = state;
      _nativeDataJson = dataJson;
    });
  }

  Future<void> _startFromGesture() async {
    try {
      await TripRuntime.instance.attach();
      _watchTrip();
      await TripRuntime.instance.platform!.checkStartGate();
      final outcome = await TripRuntime.instance.controller!.start(
        StartupEntry.a2,
      );
      if (!mounted) return;
      setState(() {
        _permissionBanner = outcome.accepted ? null : '未能開始行程';
      });
    } on MissingPluginException {
      return;
    } on PlatformException catch (error) {
      if (!mounted) return;
      await _showPermissionGap(error);
    }
  }

  Future<void> _selectPreference(DriverPreference value) async {
    try {
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
    try {
      await _preferences.setHotwordEnabled(enabled);
    } on PlatformException catch (error) {
      await _showPermissionGap(error);
    } on MissingPluginException {
      _preferences.hotwordEnabled = enabled;
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

  void _openToday() {
    Navigator.of(context)
        .push(MaterialPageRoute<void>(builder: (_) => const TodayTripScreen()));
  }

  void _openSettings() {
    showModalBottomSheet<void>(
      context: context,
      backgroundColor: AppColors.cardBackgroundSolid,
      builder: (context) {
        return SafeArea(
          child: ListView(
            shrinkWrap: true,
            padding: const EdgeInsets.all(16),
            children: [
              Text(
                '設定',
                style: TextStyle(color: AppColors.textPrimary, fontSize: 18),
              ),
              const SizedBox(height: 12),
              for (final item in _preferenceItems)
                ListTile(
                  title: Text(
                    item.$2,
                    style: TextStyle(
                      color: _preferences.preference == item.$1
                          ? AppColors.amberSteady
                          : AppColors.textPrimary,
                    ),
                  ),
                  onTap: () {
                    unawaited(_selectPreference(item.$1));
                    Navigator.of(context).pop();
                  },
                ),
              SwitchListTile(
                contentPadding: EdgeInsets.zero,
                title: Text(
                  '本程熱詞',
                  style: TextStyle(color: AppColors.textPrimary),
                ),
                value: _preferences.hotwordEnabled,
                activeThumbColor: AppColors.statusVacancyGreen,
                onChanged: (enabled) {
                  unawaited(_toggleHotword(enabled));
                },
              ),
              Text(
                _nativeState.isEmpty ? '原生狀態 —' : '原生狀態 $_nativeState',
                style: TextStyle(color: AppColors.textSecondary),
              ),
              if (_nativeDataJson.isNotEmpty)
                Text(
                  _nativeDataJson,
                  style: TextStyle(color: AppColors.textSecondary),
                ),
            ],
          ),
        );
      },
    );
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
            SafeArea(
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: Column(
                  children: [
                    if (_permissionBanner != null)
                      Material(
                        color: AppColors.cardBackground,
                        child: Padding(
                          padding: const EdgeInsets.all(12),
                          child: Text(
                            _permissionBanner!,
                            style: TextStyle(
                              color: AppColors.amberSteady,
                              fontSize: 14,
                            ),
                          ),
                        ),
                      ),
                    const Spacer(),
                    Align(
                      alignment: Alignment.centerRight,
                      child: Material(
                        color: AppColors.cardBackground,
                        shape: const CircleBorder(),
                        child: IconButton(
                          key: const Key('pre_trip_settings'),
                          tooltip: '設定',
                          onPressed: _openSettings,
                          icon: const Icon(
                            Icons.tune,
                            color: AppColors.textPrimary,
                          ),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
        ],
      ),
      bottomNavigationBar: inTrip
          ? null
          : PreTripBottomBar(
              onDepart: () {
                unawaited(_startFromGesture());
              },
              onHere: () {
                unawaited(_startFromGesture());
              },
              onToday: _openToday,
            ),
    );
  }
}

const _preferenceItems = <(DriverPreference, String)>[
  (DriverPreference.indoorOnly, 'INDOOR_ONLY'),
  (DriverPreference.smartHybrid, 'SMART_HYBRID'),
  (DriverPreference.metersFocus, 'METERS_FOCUS'),
];
