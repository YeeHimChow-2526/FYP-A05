import 'package:flutter/material.dart';
import 'package:google_navigation_flutter/google_navigation_flutter.dart';

import 'nav_engine.dart';

class GoogleNavEngine implements NavEngine {
  static const int navigationNotificationId = 42;

  GoogleNavigationViewController? _view;
  Future<bool>? _initializing;
  bool _ready = false;
  double? _pendingLat;
  double? _pendingLng;

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
        initialNavigationUIEnabledPreference: NavigationUIEnabledPreference.automatic,
        onViewCreated: _onViewCreated,
      ),
    );
  }

  Future<void> _onViewCreated(GoogleNavigationViewController controller) async {
    _view = controller;
    await controller.setReportIncidentButtonEnabled(false);
    await controller.setRecenterButtonEnabled(true);
    await controller.setNavigationHeaderEnabled(true);
    await controller.setNavigationFooterEnabled(true);
    await controller.setMyLocationEnabled(true);
    final lat = _pendingLat;
    final lng = _pendingLng;
    if (lat != null && lng != null) {
      await syncUserLocation(lat, lng);
    }
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
      if (!await GoogleMapsNavigator.areTermsAccepted()) {
        final accepted = await GoogleMapsNavigator.showTermsAndConditionsDialog('泊車副駕', '泊車副駕');
        if (!accepted) {
          _initializing = null;
          return false;
        }
      }
      if (!await GoogleMapsNavigator.isInitialized()) {
        await GoogleMapsNavigator.initializeNavigationSession(
          taskRemovedBehavior: TaskRemovedBehavior.quitService,
          notificationOptions: const NavigationNotificationOptions(
            notificationId: navigationNotificationId,
            defaultMessage: '導航進行中',
            resumeAppOnTap: true,
          ),
        );
      }
      _ready = true;
      return true;
    } catch (_) {
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
    try {
      await GoogleMapsNavigator.simulator.setUserLocation(LatLng(latitude: lat, longitude: lng));
    } catch (_) {}
  }

  @override
  Future<bool> startGuidanceTo({required String title, required double lat, required double lng}) async {
    if (!await ensureInitialized()) return false;
    try {
      final status = await GoogleMapsNavigator.setDestinations(
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
      if (status != NavigationRouteStatus.statusOk) return false;
      await GoogleMapsNavigator.startGuidance();
      return true;
    } catch (_) {
      return false;
    }
  }

  @override
  Future<void> setTurnByTurnAudioEnabled(bool enabled) async {
    if (!await ensureInitialized()) return;
    try {
      await GoogleMapsNavigator.setAudioGuidance(
        NavigationAudioGuidanceSettings(
          guidanceType: enabled ? NavigationAudioGuidanceType.alertsAndGuidance : NavigationAudioGuidanceType.silent,
        ),
      );
    } catch (_) {}
  }

  @override
  Future<void> showRouteOverview() async {
    final view = _view;
    if (view == null) return;
    try {
      await view.showRouteOverview();
    } catch (_) {}
  }

  @override
  Future<void> stopGuidance() async {
    try {
      if (await GoogleMapsNavigator.isInitialized()) {
        await GoogleMapsNavigator.stopGuidance();
        await GoogleMapsNavigator.clearDestinations();
      }
    } catch (_) {}
  }
}
