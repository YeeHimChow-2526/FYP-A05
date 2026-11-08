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
  /// [utteranceId] 非 null → 原生喺 TTS 真正講完／失敗／被停先回 `onSpeechDone(utteranceId, played)`。
  void speak(String text, {String? utteranceId});
  void interruptSpeech();
  void launchGateNavigation(String label, {double? lat, double? lng});

  /// 原生 STT 開／收咪（VoiceInput port）。認到嘅文字經 `onVoiceUtterance` 返嚟 Dart。
  void startListening({bool hotword = false});
  void stopListening();
}

typedef NowMs = int Function();
typedef Delay = Future<void> Function(int ms);
