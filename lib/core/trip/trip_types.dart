enum StartupEntry { a1, a2, a3, b1, b2, b3, onSite }

enum TripState {
  preFgs,
  cruising,
  amberSteady,
  greyBreathing,
  alertBreathing,
  nearbyReady,
  expanded,
  navToGate,
  approachMuted,
  parkedConfirmed,
  ended,
}

enum CapsuleLight { none, greySteady, amberSteady, greyBreathing, alertBreathing }

enum StatusPip { none, green, yellow, red }

enum AlertCause { noDestination, navMidConfidence, placeClarification, driverActionFailed }

enum SamplingTier { far, near, approach }

class StartOutcome {
  const StartOutcome({
    required this.accepted,
    required this.sessionId,
    required this.spokenCue,
    required this.recommend,
    required this.startFgs,
  });

  final bool accepted;
  final String? sessionId;
  final String? spokenCue;
  final bool recommend;
  final bool startFgs;
}

class DistanceSample {
  const DistanceSample({
    required this.distanceM,
    required this.speedKmh,
    required this.fixAgeS,
    required this.lowSpeedHoldS,
    required this.exitHoldS,
    required this.fresh,
  });

  final int distanceM;
  final double speedKmh;
  final int fixAgeS;
  final int lowSpeedHoldS;
  final int exitHoldS;
  final bool fresh;
}

extension StartupEntryWire on StartupEntry {
  String get wireName => switch (this) {
        StartupEntry.a1 => 'A_1',
        StartupEntry.a2 => 'A_2',
        StartupEntry.a3 => 'A_3',
        StartupEntry.b1 => 'B_1',
        StartupEntry.b2 => 'B_2',
        StartupEntry.b3 => 'B_3',
        StartupEntry.onSite => 'ON_SITE',
      };

  static StartupEntry fromWire(String raw) => switch (raw) {
        'A_1' => StartupEntry.a1,
        'A_2' => StartupEntry.a2,
        'A_3' => StartupEntry.a3,
        'B_1' => StartupEntry.b1,
        'B_2' => StartupEntry.b2,
        'B_3' => StartupEntry.b3,
        'ON_SITE' => StartupEntry.onSite,
        _ => throw ArgumentError('Unknown StartupEntry: $raw'),
      };
}

extension TripStateWire on TripState {
  String get wireName => switch (this) {
        TripState.preFgs => 'PRE_FGS',
        TripState.cruising => 'CRUISING',
        TripState.amberSteady => 'AMBER_STEADY',
        TripState.greyBreathing => 'GREY_BREATHING',
        TripState.alertBreathing => 'ALERT_BREATHING',
        TripState.nearbyReady => 'NEARBY_READY',
        TripState.expanded => 'EXPANDED',
        TripState.navToGate => 'NAV_TO_GATE',
        TripState.approachMuted => 'APPROACH_MUTED',
        TripState.parkedConfirmed => 'PARKED_CONFIRMED',
        TripState.ended => 'ENDED',
      };

  static TripState fromWire(String raw) => switch (raw) {
        'PRE_FGS' => TripState.preFgs,
        'CRUISING' => TripState.cruising,
        'AMBER_STEADY' => TripState.amberSteady,
        'GREY_BREATHING' => TripState.greyBreathing,
        'ALERT_BREATHING' => TripState.alertBreathing,
        'NEARBY_READY' => TripState.nearbyReady,
        'EXPANDED' => TripState.expanded,
        'NAV_TO_GATE' => TripState.navToGate,
        'APPROACH_MUTED' => TripState.approachMuted,
        'PARKED_CONFIRMED' => TripState.parkedConfirmed,
        'ENDED' => TripState.ended,
        _ => throw ArgumentError('Unknown TripState: $raw'),
      };
}

extension CapsuleLightWire on CapsuleLight {
  String get wireName => switch (this) {
        CapsuleLight.none => 'NONE',
        CapsuleLight.greySteady => 'GREY_STEADY',
        CapsuleLight.amberSteady => 'AMBER_STEADY',
        CapsuleLight.greyBreathing => 'GREY_BREATHING',
        CapsuleLight.alertBreathing => 'ALERT_BREATHING',
      };

  static CapsuleLight fromWire(String raw) => switch (raw) {
        'NONE' => CapsuleLight.none,
        'GREY_STEADY' => CapsuleLight.greySteady,
        'AMBER_STEADY' => CapsuleLight.amberSteady,
        'GREY_BREATHING' => CapsuleLight.greyBreathing,
        'ALERT_BREATHING' => CapsuleLight.alertBreathing,
        _ => throw ArgumentError('Unknown CapsuleLight: $raw'),
      };
}

extension StatusPipWire on StatusPip {
  String get wireName => switch (this) {
        StatusPip.none => 'NONE',
        StatusPip.green => 'GREEN',
        StatusPip.yellow => 'YELLOW',
        StatusPip.red => 'RED',
      };

  static StatusPip fromWire(String raw) => switch (raw) {
        'NONE' => StatusPip.none,
        'GREEN' => StatusPip.green,
        'YELLOW' => StatusPip.yellow,
        'RED' => StatusPip.red,
        _ => throw ArgumentError('Unknown StatusPip: $raw'),
      };
}

extension AlertCauseWire on AlertCause {
  String get wireName => switch (this) {
        AlertCause.noDestination => 'NO_DESTINATION',
        AlertCause.navMidConfidence => 'NAV_MID_CONFIDENCE',
        AlertCause.placeClarification => 'PLACE_CLARIFICATION',
        AlertCause.driverActionFailed => 'DRIVER_ACTION_FAILED',
      };

  static AlertCause fromWire(String raw) => switch (raw) {
        'NO_DESTINATION' => AlertCause.noDestination,
        'NAV_MID_CONFIDENCE' => AlertCause.navMidConfidence,
        'PLACE_CLARIFICATION' => AlertCause.placeClarification,
        'DRIVER_ACTION_FAILED' => AlertCause.driverActionFailed,
        _ => throw ArgumentError('Unknown AlertCause: $raw'),
      };
}

extension SamplingTierWire on SamplingTier {
  String get wireName => switch (this) {
        SamplingTier.far => 'FAR',
        SamplingTier.near => 'NEAR',
        SamplingTier.approach => 'APPROACH',
      };

  static SamplingTier fromWire(String raw) => switch (raw) {
        'FAR' => SamplingTier.far,
        'NEAR' => SamplingTier.near,
        'APPROACH' => SamplingTier.approach,
        _ => throw ArgumentError('Unknown SamplingTier: $raw'),
      };
}
