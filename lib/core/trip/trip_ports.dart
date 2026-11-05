import '../ignition/engine_shutdown_detector.dart';
import 'trip_types.dart';

export '../ignition/engine_shutdown_detector.dart' show EngineSignal, EngineShutdownDetector;

class LocFix {
  const LocFix({
    required this.lat,
    required this.lng,
    required this.accuracyM,
    required this.speedKmh,
    required this.timestampMs,
  });

  final double lat;
  final double lng;
  final double accuracyM;
  final double speedKmh;
  final int timestampMs;
}

abstract class TripPlatform {
  Future<bool> startForeground(StartupEntry entry);
  Future<void> stopForeground(String reason);
  void applySamplingTier(SamplingTier tier);
  void setNotificationText(String text);
  void speak(String text);
  void interruptSpeech();
  void launchGateNavigation(String label, {double? lat, double? lng});
}

typedef NowMs = int Function();
typedef Delay = Future<void> Function(int ms);
