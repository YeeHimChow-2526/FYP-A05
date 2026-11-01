import 'package:flutter/services.dart';

import '../core/ignition/engine_shutdown_detector.dart';
import '../core/trip/trip_controller.dart';
import '../core/trip/trip_ports.dart';
import '../core/trip/trip_types.dart';
import '../core/voice/voice_capability.dart';
import '../core/voice/zh_hk_templates.dart';

class ChannelTripPlatform implements TripPlatform {
  ChannelTripPlatform(this.channel);

  static const String name = 'com.hkparking.copilot/trip_platform';

  final MethodChannel channel;
  TripController? _controller;

  void bind(TripController controller) {
    _controller = controller;
    channel.setMethodCallHandler(_handle);
  }

  @override
  Future<void> checkStartGate() {
    return channel.invokeMethod<void>('checkStartGate');
  }

  Future<bool> startForeground(StartupEntry entry) async {
    final ok = await channel.invokeMethod<bool>('startTripFgs', {'entry': entry.wireName});
    return ok == true;
  }

  @override
  Future<void> stopForeground(String reason) {
    return channel.invokeMethod<void>('stopTripFgs', {'reason': reason});
  }

  @override
  void applySamplingTier(SamplingTier tier) {
    channel.invokeMethod<void>('applySamplingTier', {'tier': tier.wireName});
  }

  @override
  void setNotificationText(String text) {
    channel.invokeMethod<void>('setNotificationText', {'text': text});
  }

  @override
  void speak(String text) {
    channel.invokeMethod<void>('speak', {'text': text});
  }

  @override
  void interruptSpeech() {
    channel.invokeMethod<void>('interruptSpeech');
  }

  @override
  void launchGateNavigation(String label) {
    channel.invokeMethod<void>('launchGateNavigation', {'label': label});
  }

  Future<Map<String, Object?>> transportConfig() async {
    final raw = await channel.invokeMethod<dynamic>('getTransportConfig');
    if (raw is! Map) return const {};
    return raw.map((key, value) => MapEntry(key.toString(), value));
  }

  Future<Map<String, String?>> prefsLoad() async {
    final raw = await channel.invokeMethod<dynamic>('prefsLoad');
    if (raw is! Map) return const {};
    return raw.map((key, value) => MapEntry(key.toString(), value as String?));
  }

  void prefsPut(String key, String value) {
    channel.invokeMethod<void>('prefsPut', {'key': key, 'value': value});
  }

  void prefsRemove(String key) {
    channel.invokeMethod<void>('prefsRemove', {'key': key});
  }

  Future<Map<String, Object?>> fixturePost(String path, String body) async {
    final raw = await channel.invokeMethod<dynamic>('fixturePost', {'path': path, 'body': body});
    if (raw is! Map) return const {};
    return raw.map((key, value) => MapEntry(key.toString(), value));
  }

  Future<CapabilitySnapshot> voiceCapability() async {
    final raw = await channel.invokeMethod<dynamic>('getVoiceCapability');
    if (raw is! Map) {
      return const CapabilitySnapshot(
        stt: CapabilityStatus.unknown,
        tts: CapabilityStatus.unknown,
        mode: TripVoiceMode.manualOnly,
        voiceInputEnabled: false,
      );
    }
    final mode = TripVoiceModeWire.fromWire(raw['tripVoiceMode'] as String? ?? 'MANUAL_ONLY');
    return CapabilitySnapshot(
      stt: CapabilityStatusWire.fromWire(raw['stt'] as String? ?? 'UNKNOWN'),
      tts: CapabilityStatusWire.fromWire(raw['tts'] as String? ?? 'UNKNOWN'),
      mode: mode,
      voiceInputEnabled: mode == TripVoiceMode.fullVoice,
      timedOut: raw['timedOut'] == true,
    );
  }

  Future<void> _handle(MethodCall call) async {
    final controller = _controller;
    if (controller == null) return;
    final args = call.arguments;
    final map = args is Map ? Map<Object?, Object?>.from(args) : const <Object?, Object?>{};
    switch (call.method) {
      case 'onFix':
        final speed = map['speedKmh'];
        controller.onFix(LocFix(
          lat: (map['lat'] as num?)?.toDouble() ?? 0,
          lng: (map['lng'] as num?)?.toDouble() ?? 0,
          accuracyM: (map['accuracyM'] as num?)?.toDouble() ?? 0,
          speedKmh: speed == null ? double.nan : (speed as num).toDouble(),
          timestampMs: (map['timestampMs'] as num?)?.toInt() ?? 0,
        ));
      case 'onTick':
        controller.onTick((map['nowMs'] as num?)?.toInt() ?? 0);
      case 'onEngineSignal':
        final signal = EngineSignalWire.fromWire(map['signal'] as String? ?? 'POWER');
        await controller.onEngineSignal(signal, disconnected: map['disconnected'] == true);
      case 'onNetworkChanged':
        await controller.onNetworkChanged(map['online'] == true);
      case 'onPermissionRevoked':
        controller.onPermissionRevoked(map['permission'] as String? ?? '');
      case 'onOemKilled':
        controller.onOemKilled();
      case 'onHotwordStart':
        await controller.start(StartupEntryWire.fromWire(map['entry'] as String? ?? 'A_1'));
      case 'onShareIntake':
        await controller.onShareIntake(
          title: map['title'] as String?,
          lat: (map['lat'] as num?)?.toDouble(),
          lng: (map['lng'] as num?)?.toDouble(),
          hasUrl: map['hasUrl'] == true,
        );
      case 'onFgsStopped':
        return;
    }
  }
}
