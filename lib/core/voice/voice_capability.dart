import 'zh_hk_templates.dart';

enum CapabilityStatus { available, supportedNotInstalled, unsupported, unknown }

enum StartCommand { setDestination, onSite }

extension StartCommandWire on StartCommand {
  String get wireName => switch (this) {
        StartCommand.setDestination => 'SET_DESTINATION',
        StartCommand.onSite => 'ON_SITE',
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
}
