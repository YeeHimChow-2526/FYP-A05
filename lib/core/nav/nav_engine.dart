import 'package:flutter/widgets.dart';

abstract class NavEngine {
  Widget buildNavView({Key? key});

  Future<bool> ensureInitialized();

  Future<void> syncUserLocation(double lat, double lng);

  Future<bool> startGuidanceTo({required String title, required double lat, required double lng});

  Future<void> setTurnByTurnAudioEnabled(bool enabled);

  Future<void> showRouteOverview();

  Future<void> stopGuidance();
}
