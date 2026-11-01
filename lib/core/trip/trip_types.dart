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
}

extension SamplingTierWire on SamplingTier {
  String get wireName => switch (this) {
        SamplingTier.far => 'FAR',
        SamplingTier.near => 'NEAR',
        SamplingTier.approach => 'APPROACH',
      };
}
