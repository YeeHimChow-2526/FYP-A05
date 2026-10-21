import 'dart:async';

import 'package:copilot/core/constants/app_colors.dart';
import 'package:copilot/core/platform/overlay_channel.dart';
import 'package:copilot/core/preferences/preference_manager.dart';
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
  bool _tripStarted = false;

  @override
  void initState() {
    super.initState();
    OverlayChannel.attach();
    OverlayChannel.tripStateListeners.add(_onTripStateChanged);
  }

  @override
  void dispose() {
    OverlayChannel.tripStateListeners.remove(_onTripStateChanged);
    super.dispose();
  }

  void _onTripStateChanged(String state, String dataJson) {
    setState(() {
      _nativeState = state;
      _nativeDataJson = dataJson;
    });
  }

  Future<void> _startFromGesture() async {
    try {
      await OverlayChannel.startBubbleService();
      if (!mounted) return;
      setState(() {
        _tripStarted = true;
        _permissionBanner = null;
      });
    } on MissingPluginException {
      return;
    } on PlatformException catch (error) {
      if (!mounted) return;
      setState(() {
        _tripStarted = false;
      });
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
    final missing = error.details is Map ? (error.details as Map)['missingItems'] : null;
    final banner = missing == null
        ? '請查看主畫面頂部權限狀態橫額。請泊好車後再處理。'
        : '權限未齊：$missing。請查看主畫面頂部權限狀態橫額。請泊好車後再處理。';
    setState(() {
      _permissionBanner = status == null ? banner : '$banner $status';
    });
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('請查看主畫面頂部權限狀態橫額。請泊好車後再處理。')),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.surface,
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(24),
          children: [
            Text(
              '泊車副駕',
              style: TextStyle(
                color: AppColors.textPrimary,
                fontSize: 28,
                fontWeight: FontWeight.w700,
              ),
            ),
            const SizedBox(height: 24),
            if (_permissionBanner != null)
              Padding(
                padding: const EdgeInsets.only(bottom: 16),
                child: Text(
                  _permissionBanner!,
                  style: TextStyle(color: AppColors.amberSteady, fontSize: 16),
                ),
              ),
            Text(
              _tripStarted ? '已開程' : '未開程',
              style: TextStyle(color: AppColors.textSecondary, fontSize: 14),
            ),
            const SizedBox(height: 12),
            Text(
              '三段偏好',
              style: TextStyle(color: AppColors.textSecondary, fontSize: 16),
            ),
            const SizedBox(height: 12),
            for (final item in _preferenceItems)
              Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: _PreferenceTile(
                  label: item.$2,
                  selected: _preferences.preference == item.$1,
                  onTap: () {
                    unawaited(_selectPreference(item.$1));
                  },
                ),
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
            const SizedBox(height: 24),
            FilledButton(
              onPressed: () {
                unawaited(_startFromGesture());
              },
              child: const Text('出發'),
            ),
            const SizedBox(height: 12),
            FilledButton(
              onPressed: () {
                unawaited(_startFromGesture());
              },
              child: const Text('現場即泊'),
            ),
            const SizedBox(height: 32),
            Text(
              '原生狀態',
              style: TextStyle(color: AppColors.textSecondary, fontSize: 16),
            ),
            const SizedBox(height: 8),
            Text(
              _nativeState.isEmpty ? '—' : _nativeState,
              style: TextStyle(color: AppColors.greySteady, fontSize: 20),
            ),
            if (_nativeDataJson.isNotEmpty)
              Text(
                _nativeDataJson,
                style: TextStyle(color: AppColors.textSecondary),
              ),
          ],
        ),
      ),
    );
  }
}

const _preferenceItems = <(DriverPreference, String)>[
  (DriverPreference.indoorOnly, 'INDOOR_ONLY'),
  (DriverPreference.smartHybrid, 'SMART_HYBRID'),
  (DriverPreference.metersFocus, 'METERS_FOCUS'),
];

class _PreferenceTile extends StatelessWidget {
  const _PreferenceTile({
    required this.label,
    required this.selected,
    required this.onTap,
  });

  final String label;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: selected ? AppColors.cardBackgroundSolid : AppColors.cardBackground,
      child: InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
          child: Text(
            label,
            style: TextStyle(
              color: selected ? AppColors.amberSteady : AppColors.textPrimary,
              fontSize: 18,
            ),
          ),
        ),
      ),
    );
  }
}
