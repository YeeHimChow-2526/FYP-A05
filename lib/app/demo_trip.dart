import 'dart:async';
import 'dart:math' as math;

import '../core/trip/trip_controller.dart';
import '../core/trip/trip_ports.dart';

/// r9 DEMO 行程：固定起點 → 沿彌敦道向北 → 朗豪坊（同 fixture 車場 CP_LANGHAM 一致）。
/// 每次重新開始都由同一個起點播；位置經 TripController.onFix → GoogleNavEngine
/// （simulateUserLocation=true）→ Navigation SDK simulator.setUserLocation，即真地圖上郁。
/// 空位／車場全部係本地 fixture（示範數據），唔打雲端、唔鎖真車位。
class DemoTripScenario {
  const DemoTripScenario._();

  static const String destinationName = '朗豪坊（DEMO）';
  static const double destLat = 22.3180;
  static const double destLng = 114.1686;

  /// 由尖沙咀彌敦道南端向北嘅大約路線（約 2.4 km）；只係示範用嘅固定點。
  static const List<(double, double)> waypoints = [
    (22.2965, 114.1722),
    (22.3005, 114.1720),
    (22.3050, 114.1716),
    (22.3095, 114.1711),
    (22.3135, 114.1703),
    (22.3165, 114.1695),
    (destLat, destLng),
  ];

  /// 每步大約 15 m（1 秒一步 ≈ 54 km/h）。
  static const double stepMeters = 15;
  static const double speedKmh = 54;
}

/// 固定點逐步重播。`tick()` 每次出一個點；到終點後停喺終點。
class DemoTripPlayer {
  DemoTripPlayer(this.controller, {this.interval = const Duration(seconds: 1), List<(double, double)>? path})
      : _path = densify(path ?? DemoTripScenario.waypoints, DemoTripScenario.stepMeters);

  final TripController controller;
  final Duration interval;
  final List<(double, double)> _path;
  Timer? _timer;
  int _index = 0;
  int _ts = 0;

  bool get running => _timer != null;
  int get index => _index;
  int get length => _path.length;
  (double, double) get current => _path[_index.clamp(0, _path.length - 1)];

  /// 行程開始：重設去起點、鎖 DEMO 目的地（如果用戶未揀）、開始播。
  void start() {
    stop();
    _index = 0;
    _ts = 0;
    controller.deviceFixesEnabled = false; // 唔同真 GPS 爭
    if (controller.tsm.destinationName == null) {
      controller.lockDestinationAt(DemoTripScenario.destinationName, DemoTripScenario.destLat, DemoTripScenario.destLng);
    }
    tick();
    _timer = Timer.periodic(interval, (_) => tick());
  }

  /// 出下一個點（測試可直接叫）。
  void tick() {
    if (!controller.tsm.fgsRunning) {
      stop();
      return;
    }
    final (lat, lng) = _path[_index];
    _ts += interval.inMilliseconds;
    controller.onFix(LocFix(lat: lat, lng: lng, accuracyM: 5, speedKmh: DemoTripScenario.speedKmh, timestampMs: _ts));
    if (_index < _path.length - 1) _index += 1;
  }

  /// 停播＋重設（行程結束／取消／切模式）。
  void stop() {
    _timer?.cancel();
    _timer = null;
    _index = 0;
  }

  static List<(double, double)> densify(List<(double, double)> pts, double stepM) {
    final out = <(double, double)>[pts.first];
    for (var i = 1; i < pts.length; i++) {
      final (aLat, aLng) = pts[i - 1];
      final (bLat, bLng) = pts[i];
      final dy = (bLat - aLat) * 111320;
      final dx = (bLng - aLng) * 111320 * 0.925; // cos(22.3°)
      final d = (dx * dx + dy * dy);
      final n = (d <= 0 ? 1 : (math.sqrt(d) / stepM).ceil()).clamp(1, 1000);
      for (var k = 1; k <= n; k++) {
        out.add((aLat + (bLat - aLat) * k / n, aLng + (bLng - aLng) * k / n));
      }
    }
    return out;
  }
}
