import 'zh_hk_templates.dart';

enum CapabilityStatus { available, supportedNotInstalled, unsupported, unknown }

enum StartCommand { setDestination, onSite }

extension StartCommandWire on StartCommand {
  String get wireName => switch (this) {
        StartCommand.setDestination => 'SET_DESTINATION',
        StartCommand.onSite => 'ON_SITE',
      };

  static StartCommand fromWire(String raw) => switch (raw) {
        'SET_DESTINATION' => StartCommand.setDestination,
        'ON_SITE' => StartCommand.onSite,
        _ => throw ArgumentError('Unknown StartCommand: $raw'),
      };
}

extension CapabilityStatusWire on CapabilityStatus {
  String get wireName => switch (this) {
        CapabilityStatus.available => 'AVAILABLE',
        CapabilityStatus.supportedNotInstalled => 'SUPPORTED_NOT_INSTALLED',
        CapabilityStatus.unsupported => 'UNSUPPORTED',
        CapabilityStatus.unknown => 'UNKNOWN',
      };

  static CapabilityStatus fromWire(String raw) => switch (raw) {
        'AVAILABLE' => CapabilityStatus.available,
        'SUPPORTED_NOT_INSTALLED' => CapabilityStatus.supportedNotInstalled,
        'UNSUPPORTED' => CapabilityStatus.unsupported,
        'UNKNOWN' => CapabilityStatus.unknown,
        _ => throw ArgumentError('Unknown CapabilityStatus: $raw'),
      };
}

class CapabilitySnapshot {
  const CapabilitySnapshot({
    required this.stt,
    required this.tts,
    required this.mode,
    required this.voiceInputEnabled,
    this.timedOut = false,
  });

  final CapabilityStatus stt;
  final CapabilityStatus tts;
  final TripVoiceMode mode;
  final bool voiceInputEnabled;
  final bool timedOut;
}

extension TripVoiceModeWire on TripVoiceMode {
  String get wireName => switch (this) {
        TripVoiceMode.fullVoice => 'FULL_VOICE',
        TripVoiceMode.announceManual => 'ANNOUNCE_MANUAL',
        TripVoiceMode.manualOnly => 'MANUAL_ONLY',
      };

  static TripVoiceMode fromWire(String raw) => switch (raw) {
        'FULL_VOICE' => TripVoiceMode.fullVoice,
        'ANNOUNCE_MANUAL' => TripVoiceMode.announceManual,
        'MANUAL_ONLY' => TripVoiceMode.manualOnly,
        _ => throw ArgumentError('Unknown TripVoiceMode: $raw'),
      };
}
