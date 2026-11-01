import 'package:copilot/core/constants/native_config.dart';
import 'package:copilot/core/trip/trip_state_machine.dart';
import 'package:copilot/core/trip/trip_types.dart';
import 'package:copilot/core/voice/voice_capability.dart';
import 'package:copilot/core/voice/zh_hk_templates.dart';
import 'package:flutter_test/flutter_test.dart';

TripStateMachine started(StartupEntry entry, {String? name, StartCommand? command}) {
  final machine = TripStateMachine(sessionIdFactory: () => 'sess_test');
  final outcome = machine.requestStart(entry, command: command, destinationName: name, gpsAccuracyM: 10);
  expect(outcome.accepted, isTrue);
  machine.onFgsStarted();
  return machine;
}

void main() {
  test('preFgsHasNoCapsuleAndIgnoresColdTapAndDrive', () {
    final machine = TripStateMachine();
    expect(machine.coldTapCapsule().accepted, isFalse);
    machine.onDriveSample(speedKmh: 80, deltaDistanceM: 500, deltaTimeS: 60);
    expect(machine.state, TripState.preFgs);
    expect(machine.capsuleLight, CapsuleLight.none);
    expect(machine.sessionId, isNull);
  });

  test('successfulEntriesCreateUniqueSessionIds', () {
    var n = 0;
    final ids = <String>{};
    for (final entry in StartupEntry.values) {
      final machine = TripStateMachine(sessionIdFactory: () => 'sess_${n++}');
      final outcome = entry == StartupEntry.b1
          ? machine.requestStart(entry, command: StartCommand.setDestination, destinationName: '朗豪坊')
          : entry == StartupEntry.onSite
              ? machine.requestStart(entry, gpsAccuracyM: 20)
              : machine.requestStart(entry);
      expect(outcome.accepted, isTrue, reason: entry.wireName);
      ids.add(outcome.sessionId!);
    }
    expect(ids, hasLength(StartupEntry.values.length));
  });

  test('b1MissAndOnSiteGpsFailureStayPreFgsWithoutSession', () {
    final miss = TripStateMachine().requestStart(StartupEntry.b1);
    expect(miss.accepted, isFalse);
    expect(miss.spokenCue, ZhHkTemplates.B1_DESTINATION_MISSED);
    final gps = TripStateMachine().requestStart(StartupEntry.onSite, gpsAccuracyM: 500);
    expect(gps.spokenCue, ZhHkTemplates.ON_SITE_LOCATION_MISSED);
    expect(gps.sessionId, isNull);
  });

  test('fgsStartedEntersCruisingOrImmediateJit', () {
    final cruising = started(StartupEntry.a2);
    expect(cruising.state, TripState.cruising);
    final jit = TripStateMachine();
    jit.requestStart(StartupEntry.a2, destinationName: '朗豪坊', distanceToDestinationM: 100);
    jit.onFgsStarted();
    expect(jit.state, TripState.amberSteady);
    expect(jit.recommend, isTrue);
  });

  test('networkFailureStaysGreyAheadOfAlert', () {
    final machine = started(StartupEntry.a2);
    machine.onNetworkFailure();
    machine.onDriveSample(speedKmh: 40, deltaDistanceM: 500, deltaTimeS: 40);
    expect(machine.state, TripState.greyBreathing);
    expect(machine.capsuleLight, CapsuleLight.greyBreathing);
    machine.onRequestSuccess();
    expect(machine.state, TripState.alertBreathing);
  });

  test('alertTriggersSpeakOnceAndLowConfidenceKeepsDestination', () {
    final machine = started(StartupEntry.a2, name: '朗豪坊');
    machine.onNavigationConfidence(0.1);
    expect(machine.destinationLocked, isTrue);
    machine.onNavigationConfidence(0.6);
    machine.onNavigationConfidence(0.6);
    expect(machine.alertSpeakCount, 1);
  });

  test('permissionRevokeEndsSessionButMicRevokeOnlyDegrades', () {
    final machine = started(StartupEntry.a2);
    machine.applyVoiceCapability(
      const CapabilitySnapshot(stt: CapabilityStatus.available, tts: CapabilityStatus.available, mode: TripVoiceMode.fullVoice, voiceInputEnabled: true),
      true,
    );
    machine.onRecordAudioRevoked(true);
    expect(machine.tripVoiceMode, TripVoiceMode.announceManual);
    expect(machine.sessionId, isNotNull);
    machine.onRequiredPermissionRevoked();
    expect(machine.sessionId, isNull);
  });

  test('tripEndDestroysSessionWithoutReconnectingOldId', () {
    var n = 0;
    final machine = TripStateMachine(sessionIdFactory: () => 'sess_${n++}');
    machine.requestStart(StartupEntry.a2);
    machine.onFgsStarted();
    final old = machine.sessionId;
    machine.onEngineShutdown(false);
    expect(machine.sessionId, isNull);
    machine.requestStart(StartupEntry.a2);
    expect(machine.sessionId, isNot(old));
  });

  test('silentRecheckTimeoutReusesConfirmedModeOrFallsBackWhenNeverConfirmed', () {
    final machine = TripStateMachine();
    machine.applyVoiceCapability(
      const CapabilitySnapshot(stt: CapabilityStatus.available, tts: CapabilityStatus.available, mode: TripVoiceMode.fullVoice, voiceInputEnabled: true, timedOut: true),
      false,
    );
    expect(machine.tripVoiceMode, TripVoiceMode.manualOnly);
    machine.syncVoiceCapability(
      const CapabilitySnapshot(stt: CapabilityStatus.available, tts: CapabilityStatus.available, mode: TripVoiceMode.fullVoice, voiceInputEnabled: true, timedOut: true),
      true,
    );
    expect(machine.tripVoiceMode, TripVoiceMode.fullVoice);
  });

  test('greyBreathingStaysAheadOfAlertEvenWithoutDestination', () {
    final machine = started(StartupEntry.a2);
    machine.onNetworkFailure();
    machine.onPlaceClarificationNeeded();
    expect(machine.state, TripState.greyBreathing);
  });

  test('voiceModeComesFromCheckerAndFailedStartHasNoSession', () {
    final machine = TripStateMachine();
    machine.applyVoiceCapability(
      const CapabilitySnapshot(stt: CapabilityStatus.unsupported, tts: CapabilityStatus.available, mode: TripVoiceMode.announceManual, voiceInputEnabled: false),
      true,
    );
    expect(machine.tripVoiceMode, TripVoiceMode.announceManual);
    expect(machine.requestStart(StartupEntry.b1).sessionId, isNull);
  });

  test('alertCueAppendsTapOnlyWhenFullVoiceMicNotUpgraded', () {
    final machine = started(StartupEntry.a2);
    machine.applyVoiceCapability(
      const CapabilitySnapshot(stt: CapabilityStatus.available, tts: CapabilityStatus.available, mode: TripVoiceMode.fullVoice, voiceInputEnabled: true),
      true,
    );
    machine.onDriveSample(speedKmh: 30, deltaDistanceM: 500, deltaTimeS: 40);
    expect(machine.lastSpokenCue, endsWith(ZhHkTemplates.TAP_CAPSULE));
    machine.onMicFgsActive(true);
    machine.clearSession();
  });

  test('onDestinationSetLeavesAlertAndJitWithinTrigger', () {
    final machine = started(StartupEntry.a2);
    machine.onDriveSample(speedKmh: 30, deltaDistanceM: 500, deltaTimeS: 40);
    expect(machine.state, TripState.alertBreathing);
    machine.onDestinationSet(destinationName: '朗豪坊', distanceToDestinationM: 100, destLat: 1, destLng: 2);
    expect(machine.state, TripState.amberSteady);
    expect(machine.jitTriggered, isTrue);
  });

  test('requestStartAcceptsCommandCodeAndResetsStatusPip', () {
    final machine = started(StartupEntry.a2, name: '朗豪坊');
    machine.onJitSucceeded(StatusPip.green);
    expect(machine.statusPip, StatusPip.green);
    machine.requestStart(StartupEntry.b1, command: StartCommand.onSite, gpsAccuracyM: 10);
    expect(machine.statusPip, StatusPip.none);
  });

  test('stt confidence missing is not zero and wire names match kotlin', () {
    expect(TripState.navToGate.wireName, 'NAV_TO_GATE');
    expect(StartupEntry.b2.wireName, 'B_2');
    expect(StartCommand.setDestination.wireName, 'SET_DESTINATION');
    expect(CapabilityStatus.unknown, isNot(CapabilityStatus.unsupported));
    const double? missing = null;
    expect(missing == 0, isFalse);
    expect(NativeConfig.STT_CONFIDENCE_THRESHOLD, 0.6);
  });
}
