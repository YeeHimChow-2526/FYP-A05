import 'dart:math';

import '../constants/native_config.dart';
import 'trip_ports.dart';
import 'trip_types.dart';

class DistanceTrackerListener {
  const DistanceTrackerListener({required this.onSample, required this.onTierChanged});

  final void Function(DistanceSample sample) onSample;
  final void Function(SamplingTier tier) onTierChanged;
}

class DistanceTracker {
  DistanceTracker(this.listener);

  final DistanceTrackerListener listener;

  SamplingTier tier = SamplingTier.far;
  int? lastDistanceM;
  int? latestClockMs;

  double? _destLat;
  double? _destLng;
  LocFix? _lastValidFix;
  double? _resolvedSpeedKmh;
  int? _lowStreakStartMs;
  int? _exitStreakStartMs;
  bool _approachMuted = false;
  bool _staleEmittedForGap = false;

  static const double _earthRadiusM = 6371000;

  void setDestination(double? lat, double? lng) {
    if (lat == null || lng == null) {
      _destLat = null;
      _destLng = null;
      lastDistanceM = null;
      _lowStreakStartMs = null;
      _exitStreakStartMs = null;
      _resolvedSpeedKmh = null;
      _staleEmittedForGap = false;
      _promoteTier(SamplingTier.far);
      return;
    }
    _destLat = lat;
    _destLng = lng;
    _lowStreakStartMs = null;
    _exitStreakStartMs = null;
    final fix = _lastValidFix;
    if (fix == null) return;
    final ageMs = _ageOf(fix);
    final fresh = ageMs <= NativeConfig.GPS_FIX_STALE_S * 1000;
    if (!fresh) _staleEmittedForGap = true;
    _emitFromFix(fix, ageMs, fresh);
  }

  void setApproachMuted(bool muted) {
    if (_approachMuted == muted) {
      if (!muted) _exitStreakStartMs = null;
      return;
    }
    _approachMuted = muted;
    _exitStreakStartMs = null;
  }

  void onFix(LocFix fix) {
    if (fix.accuracyM > NativeConfig.VALID_FIX_MAX_ACCURACY_M) return;
    final previous = _lastValidFix;
    if (previous != null && fix.timestampMs < previous.timestampMs) return;
    if (previous != null) {
      final gap = fix.timestampMs - previous.timestampMs;
      if (_staleEmittedForGap || gap > NativeConfig.GPS_FIX_STALE_S * 1000) {
        _lowStreakStartMs = null;
      }
      if (_staleEmittedForGap || gap > NativeConfig.APPROACH_EXIT_HOLD_S * 1000) {
        _exitStreakStartMs = null;
      }
    }
    _staleEmittedForGap = false;
    _resolvedSpeedKmh = _resolveSpeed(fix, previous);
    _lastValidFix = fix;
    _bumpClock(fix.timestampMs);
    _emitFromFix(fix, 0, true);
  }

  void onTick(int nowMs) {
    _bumpClock(nowMs);
    final fix = _lastValidFix;
    if (fix == null || _destLat == null || _destLng == null) return;
    final ageMs = nowMs - fix.timestampMs;
    if (ageMs <= NativeConfig.GPS_FIX_STALE_S * 1000) return;
    if (_staleEmittedForGap) return;
    _staleEmittedForGap = true;
    _lowStreakStartMs = null;
    _exitStreakStartMs = null;
    final distance = lastDistanceM ?? _roundHaversine(fix.lat, fix.lng, _destLat!, _destLng!);
    lastDistanceM = distance;
    _updateTier(distance);
    listener.onSample(DistanceSample(
      distanceM: distance,
      speedKmh: sampleSpeedForTsm(_resolvedSpeedKmh),
      fixAgeS: _staleFixAgeS(ageMs),
      lowSpeedHoldS: 0,
      exitHoldS: 0,
      fresh: false,
    ));
  }

  int? distanceToDestinationM() {
    final fix = _lastValidFix;
    final lat = _destLat;
    final lng = _destLng;
    if (fix == null || lat == null || lng == null) return null;
    return _roundHaversine(fix.lat, fix.lng, lat, lng);
  }

  double? recentSpeedKmh([int? nowMs]) {
    final fix = _lastValidFix;
    if (fix == null) return null;
    final clock = nowMs ?? latestClockMs ?? fix.timestampMs;
    final ageMs = clock - fix.timestampMs;
    final maxAge = _intervalMs(tier) + NativeConfig.GPS_FIX_STALE_S * 1000;
    if (ageMs > maxAge) return null;
    return _resolvedSpeedKmh;
  }

  void reset() {
    _destLat = null;
    _destLng = null;
    _lastValidFix = null;
    _resolvedSpeedKmh = null;
    _lowStreakStartMs = null;
    _exitStreakStartMs = null;
    _approachMuted = false;
    lastDistanceM = null;
    latestClockMs = null;
    _staleEmittedForGap = false;
    _promoteTier(SamplingTier.far);
  }

  static double sampleSpeedForTsm(double? speed) => speed ?? double.maxFinite;

  static double haversineM(double lat1, double lng1, double lat2, double lng2) {
    final dLat = _rad(lat2 - lat1);
    final dLng = _rad(lng2 - lng1);
    final a = sin(dLat / 2) * sin(dLat / 2) + cos(_rad(lat1)) * cos(_rad(lat2)) * sin(dLng / 2) * sin(dLng / 2);
    final c = 2 * atan2(sqrt(a), sqrt(1 - a));
    return _earthRadiusM * c;
  }

  void _emitFromFix(LocFix fix, int ageMs, bool fresh) {
    final lat = _destLat;
    final lng = _destLng;
    if (lat == null || lng == null) return;
    final distance = _roundHaversine(fix.lat, fix.lng, lat, lng);
    lastDistanceM = distance;
    final lowHold = _computeLowHold(distance, fix.timestampMs);
    final exitHold = _computeExitHold(distance, fix.timestampMs);
    _updateTier(distance);
    listener.onSample(DistanceSample(
      distanceM: distance,
      speedKmh: sampleSpeedForTsm(_resolvedSpeedKmh),
      fixAgeS: fresh ? max(0, ageMs ~/ 1000) : _staleFixAgeS(ageMs),
      lowSpeedHoldS: lowHold,
      exitHoldS: exitHold,
      fresh: fresh,
    ));
  }

  int _computeLowHold(int distanceM, int timestampMs) {
    final speed = _resolvedSpeedKmh;
    final qualifies = distanceM <= NativeConfig.APPROACH_CORE_RADIUS_M && speed != null && speed < NativeConfig.APPROACH_SPEED_THRESHOLD_KMH;
    if (!qualifies) {
      _lowStreakStartMs = null;
      return 0;
    }
    final start = _lowStreakStartMs;
    if (start == null) {
      _lowStreakStartMs = timestampMs;
      return 0;
    }
    return max(0, (timestampMs - start) ~/ 1000);
  }

  int _computeExitHold(int distanceM, int timestampMs) {
    if (!_approachMuted) {
      _exitStreakStartMs = null;
      return 0;
    }
    if (distanceM <= NativeConfig.APPROACH_EXIT_DISTANCE_M) {
      _exitStreakStartMs = null;
      return 0;
    }
    final start = _exitStreakStartMs;
    if (start == null) {
      _exitStreakStartMs = timestampMs;
      return 0;
    }
    return max(0, (timestampMs - start) ~/ 1000);
  }

  void _updateTier(int distanceM) {
    final SamplingTier next;
    if (tier == SamplingTier.approach) {
      if (distanceM > NativeConfig.HIGH_FREQ_EXIT_RADIUS_M) {
        next = distanceM <= NativeConfig.TRIGGER_DISTANCE_M ? SamplingTier.near : SamplingTier.far;
      } else {
        next = SamplingTier.approach;
      }
    } else if (distanceM <= NativeConfig.APPROACH_RADIUS_M) {
      next = SamplingTier.approach;
    } else if (distanceM <= NativeConfig.TRIGGER_DISTANCE_M) {
      next = SamplingTier.near;
    } else {
      next = SamplingTier.far;
    }
    _promoteTier(next);
  }

  void _promoteTier(SamplingTier next) {
    if (tier == next) return;
    tier = next;
    listener.onTierChanged(next);
  }

  double? _resolveSpeed(LocFix fix, LocFix? previous) {
    if (!fix.speedKmh.isNaN && fix.speedKmh >= 0) return fix.speedKmh;
    if (previous == null) return null;
    final dtMs = fix.timestampMs - previous.timestampMs;
    if (dtMs <= 0) return null;
    final metres = haversineM(previous.lat, previous.lng, fix.lat, fix.lng);
    return metres / (dtMs / 1000.0) * 3.6;
  }

  int _ageOf(LocFix fix) {
    final clock = latestClockMs ?? fix.timestampMs;
    return max(0, clock - fix.timestampMs);
  }

  void _bumpClock(int ms) {
    final current = latestClockMs;
    latestClockMs = current == null ? ms : max(current, ms);
  }

  int _roundHaversine(double lat1, double lng1, double lat2, double lng2) => haversineM(lat1, lng1, lat2, lng2).round();

  int _staleFixAgeS(int ageMs) => max(max(0, ageMs ~/ 1000), NativeConfig.GPS_FIX_STALE_S + 1);

  static int _intervalMs(SamplingTier tier) => switch (tier) {
        SamplingTier.far => NativeConfig.LOC_INTERVAL_FAR_MS,
        SamplingTier.near => NativeConfig.LOC_INTERVAL_NEAR_MS,
        SamplingTier.approach => NativeConfig.LOC_INTERVAL_APPROACH_MS,
      };

  static double _rad(double degrees) => degrees * pi / 180;
}
