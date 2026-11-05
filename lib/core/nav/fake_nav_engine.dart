import 'package:flutter/material.dart';

import 'nav_engine.dart';

class FakeNavEngine extends NavEngine {
  FakeNavEngine({this.shouldFailGuidance = false});

  bool shouldFailGuidance;
  bool initialized = false;
  bool guidanceRunning = false;
  bool turnByTurnAudioEnabled = true;
  String? activeTitle;
  double? activeLat;
  double? activeLng;
  double? userLat;
  double? userLng;
  int overviewCount = 0;
  final List<String> calls = [];

  @override
  Widget buildNavView({Key? key}) {
    return _FakeNavPanel(key: key ?? const Key('fake_nav_view'), engine: this);
  }

  @override
  Future<bool> ensureInitialized() async {
    calls.add('ensureInitialized');
    initialized = true;
    return true;
  }

  @override
  Future<void> syncUserLocation(double lat, double lng) async {
    calls.add('syncUserLocation');
    userLat = lat;
    userLng = lng;
  }

  @override
  Future<bool> startGuidanceTo({required String title, required double lat, required double lng}) async {
    calls.add('setDestinations');
    calls.add('startGuidance');
    activeTitle = title;
    activeLat = lat;
    activeLng = lng;
    if (shouldFailGuidance) {
      guidanceRunning = false;
      return false;
    }
    guidanceRunning = true;
    return true;
  }

  @override
  Future<void> setTurnByTurnAudioEnabled(bool enabled) async {
    calls.add(enabled ? 'audioOn' : 'mute');
    turnByTurnAudioEnabled = enabled;
  }

  @override
  Future<void> showRouteOverview() async {
    calls.add('showRouteOverview');
    overviewCount += 1;
  }

  @override
  Future<void> stopGuidance() async {
    calls.add('stopGuidance');
    guidanceRunning = false;
    activeTitle = null;
  }
}

class _FakeNavPanel extends StatelessWidget {
  const _FakeNavPanel({super.key, required this.engine});

  final FakeNavEngine engine;

  @override
  Widget build(BuildContext context) {
    final origin = engine.userLat == null ? '—' : '${engine.userLat}, ${engine.userLng}';
    final dest = engine.activeTitle ?? '—';
    return ColoredBox(
      color: const Color(0xFF101418),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Text(
          'FAKE NAV MAP\norigin $origin\nwaypoint $dest\nguidance ${engine.guidanceRunning}\nmute ${!engine.turnByTurnAudioEnabled}',
          style: const TextStyle(color: Color(0xFFE0E0E0), fontSize: 16),
        ),
      ),
    );
  }
}
