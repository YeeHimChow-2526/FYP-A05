import 'package:copilot/core/ignition/engine_shutdown_detector.dart';
import 'package:copilot/core/net/api_client.dart';
import 'package:copilot/core/net/api_endpoint_policy.dart';
import 'package:copilot/core/preferences/preference_manager.dart';
import 'package:copilot/core/trip/trip_types.dart';
import 'package:copilot/core/voice/voice_capability.dart';
import 'package:copilot/core/voice/zh_hk_templates.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('F5 cross-channel enums round-trip fromWire and reject unknown', () {
    for (final value in StartupEntry.values) {
      expect(StartupEntryWire.fromWire(value.wireName), value);
    }
    for (final value in TripState.values) {
      expect(TripStateWire.fromWire(value.wireName), value);
    }
    for (final value in CapsuleLight.values) {
      expect(CapsuleLightWire.fromWire(value.wireName), value);
    }
    for (final value in StatusPip.values) {
      expect(StatusPipWire.fromWire(value.wireName), value);
    }
    for (final value in AlertCause.values) {
      expect(AlertCauseWire.fromWire(value.wireName), value);
    }
    for (final value in SamplingTier.values) {
      expect(SamplingTierWire.fromWire(value.wireName), value);
    }
    for (final value in StartCommand.values) {
      expect(StartCommandWire.fromWire(value.wireName), value);
    }
    for (final value in CapabilityStatus.values) {
      expect(CapabilityStatusWire.fromWire(value.wireName), value);
    }
    for (final value in TripVoiceMode.values) {
      expect(TripVoiceModeWire.fromWire(value.wireName), value);
    }
    for (final value in QueryTriggerMode.values) {
      expect(QueryTriggerModeWire.fromWire(value.wireName), value);
    }
    for (final value in DriverPreference.values) {
      expect(DriverPreference.fromWire(value.code), value);
    }
    for (final value in EngineSignal.values) {
      expect(EngineSignalWire.fromWire(value.wireName), value);
    }
    for (final value in EndpointReason.values) {
      expect(EndpointReasonWire.fromWire(value.wireName), value);
    }
    expect(() => TripStateWire.fromWire('NOPE'), throwsArgumentError);
    expect(() => StartupEntryWire.fromWire(''), throwsArgumentError);
    expect(() => DriverPreference.fromWire('OUTDOOR'), throwsArgumentError);
    expect(() => QueryTriggerModeWire.fromWire('JIT'), throwsArgumentError);
  });
}
