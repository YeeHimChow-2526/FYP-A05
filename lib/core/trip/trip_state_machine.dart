import 'dart:math';

import '../constants/native_config.dart';
import '../voice/voice_capability.dart';
import '../voice/zh_hk_templates.dart';
import 'trip_types.dart';

class TripStateMachine {
  TripStateMachine({void Function(String reason)? onTripEnded, String Function()? sessionIdFactory})
      : _onTripEnded = onTripEnded ?? ((_) {}),
        _sessionIdFactory = sessionIdFactory ?? _defaultSessionId;

  final void Function(String reason) _onTripEnded;
  final String Function() _sessionIdFactory;

  void Function()? onStateChanged;
  void Function(bool mutedBefore)? onAlertCueReady;

  TripState state = TripState.preFgs;
  CapsuleLight capsuleLight = CapsuleLight.none;
  StatusPip statusPip = StatusPip.none;
  String? sessionId;
  bool destinationLocked = false;
  bool fgsRunning = false;
  bool fgsStartAccepted = false;
  bool recommend = false;
  bool jitTriggered = false;
  bool get jitRequested => jitTriggered;

  /// 1 km 預備區「綠燈」（就緒，唔係空位 pip）：同一個已確認目的地一旦 ≤ TRIGGER_DISTANCE_M 就保持，
  /// 之後距離變遠或網絡失敗都唔熄；只有改目的地或行程結束先重設。
  bool readinessLatched = false;
  bool hasDestination = false;
  String? destinationName;
  double? destLat;
  double? destLng;
  String? destinationPoiId;
  bool isMicFgsActive = false;
  bool voiceInputEnabled = false;

  /// 真收音＋辨識可用。全語音或 debug 注入唔算。
  bool voiceReplyAvailable = false;
  TripVoiceMode tripVoiceMode = TripVoiceMode.manualOnly;
  String? lastSpokenCue;
  int alertSpeakCount = 0;
  bool networkDegraded = false;
  bool backgroundResolvePending = false;

  int? _distanceToDestinationM;
  int? _lastApproachDistanceM;
  double _lastApproachSpeedKmh = 0;
  int _lastApproachLowSpeedHoldS = 0;
  int _lastApproachFixAgeS = 0;
  bool _treatAsOnSite = false;

  /// Phase 2「現場即泊」按鈕：行程前 Dart 冇定位，先開 FGS，等第一個精度合格嘅 fix 先鎖現場做目的地。
  bool onSitePendingFix = false;
  AlertCause? _deferredAlert;
  final Set<AlertCause> _spokenAlerts = {};
  int _consecutiveHighSpeedS = 0;
  int _accumulatedDistanceM = 0;

  void applyVoiceCapability(CapabilitySnapshot snapshot, bool hasConfirmed) {
    syncVoiceCapability(snapshot, hasConfirmed);
  }

  void syncVoiceCapability(CapabilitySnapshot snapshot, bool hasConfirmed) {
    final mode = snapshot.timedOut && !hasConfirmed ? TripVoiceMode.manualOnly : snapshot.mode;
    tripVoiceMode = mode;
    voiceInputEnabled = snapshot.voiceInputEnabled && mode == TripVoiceMode.fullVoice;
    voiceReplyAvailable = snapshot.canListen && voiceInputEnabled;
  }

  StartOutcome coldTapCapsule() => _rejected(null);

  void onMicFgsActive(bool active) {
    isMicFgsActive = active;
  }

  StartOutcome requestStart(
    StartupEntry entry, {
    StartCommand? command,
    String? destinationName,
    bool gpsAvailable = true,
    double? gpsAccuracyM,
    int? distanceToDestinationM,
    bool awaitOnSiteFix = false,
  }) {
    final namedDestination = destinationName != null && destinationName.trim().isNotEmpty;
    final onSite = entry == StartupEntry.onSite || (entry == StartupEntry.b1 && command == StartCommand.onSite);
    if (entry == StartupEntry.b1) {
      final setDestination = command == StartCommand.setDestination && namedDestination;
      if (command == StartCommand.onSite) {
        if (!_onSiteGpsOk(gpsAvailable, gpsAccuracyM)) return _rejected(ZhHkTemplates.ON_SITE_LOCATION_MISSED);
      } else if (setDestination || namedDestination) {
        // locked below
      } else {
        return _rejected(ZhHkTemplates.B1_DESTINATION_MISSED);
      }
    }
    final deferFix = entry == StartupEntry.onSite && awaitOnSiteFix;
    if (entry == StartupEntry.onSite && !deferFix && !_onSiteGpsOk(gpsAvailable, gpsAccuracyM)) {
      return _rejected(ZhHkTemplates.ON_SITE_LOCATION_MISSED);
    }
    final id = _sessionIdFactory();
    sessionId = id;
    final locked = namedDestination || distanceToDestinationM != null;
    destinationLocked = locked;
    hasDestination = locked;
    this.destinationName = namedDestination ? destinationName : null;
    destLat = null;
    destLng = null;
    destinationPoiId = null;
    _distanceToDestinationM = distanceToDestinationM;
    // deferFix：未有合格 fix 唔可以當現場（冇目的地）；fix 到咗由 controller lockDestinationAt(距離 0) 觸發 JIT。
    _treatAsOnSite = onSite && !deferFix;
    onSitePendingFix = deferFix;
    fgsStartAccepted = true;
    fgsRunning = false;
    recommend = false;
    jitTriggered = false;
    readinessLatched = false;
    _assignStatusPip(StatusPip.none);
    isMicFgsActive = false;
    networkDegraded = false;
    backgroundResolvePending = false;
    _deferredAlert = null;
    _spokenAlerts.clear();
    alertSpeakCount = 0;
    _consecutiveHighSpeedS = 0;
    _accumulatedDistanceM = 0;
    voiceInputEnabled = tripVoiceMode == TripVoiceMode.fullVoice;
    lastSpokenCue = null;
    _enter(TripState.preFgs);
    return StartOutcome(accepted: true, sessionId: id, spokenCue: null, recommend: false, startFgs: true);
  }

  void onDestinationSet({
    int? distanceToDestinationM,
    String? destinationName,
    double? destLat,
    double? destLng,
    String? destinationPoiId,
  }) {
    hasDestination = true;
    destinationLocked = true;
    if (destinationName != null && destinationName.trim().isNotEmpty) this.destinationName = destinationName;
    this.destLat = destLat;
    this.destLng = destLng;
    this.destinationPoiId = destinationPoiId;
    backgroundResolvePending = false;
    _spokenAlerts.remove(AlertCause.placeClarification);
    if (_deferredAlert == AlertCause.placeClarification) _deferredAlert = null;
    if (distanceToDestinationM != null) _distanceToDestinationM = distanceToDestinationM;
    if (state == TripState.navToGate) return;
    final distance = _distanceToDestinationM;
    if (state == TripState.alertBreathing && !networkDegraded) {
      if (fgsRunning && distance != null && distance <= NativeConfig.TRIGGER_DISTANCE_M) {
        _armBackgroundPrefetch(distance);
        if (state == TripState.alertBreathing) _enter(TripState.amberSteady);
      } else {
        _enter(TripState.cruising);
      }
      return;
    }
    if (state == TripState.amberSteady && !jitTriggered) {
      _enter(statusPip != StatusPip.none ? TripState.nearbyReady : TripState.cruising);
    }
    _armBackgroundPrefetch(distance);
  }

  /// 已確認目的地改咗（座標／POI 唔同）：清走上一個目的地嘅 JIT、就緒、pip、接近同導航狀態，
  /// 等 onDestinationSet 按新目的地距離重新判斷（≤ TRIGGER_DISTANCE_M 即再觸發）。網絡狀態係真實狀態，唔重設。
  void onDestinationChanged() {
    if (!fgsRunning) return;
    jitTriggered = false;
    recommend = false;
    readinessLatched = false;
    _assignStatusPip(StatusPip.none);
    _distanceToDestinationM = null;
    _lastApproachDistanceM = null;
    _lastApproachSpeedKmh = 0;
    _lastApproachLowSpeedHoldS = 0;
    _lastApproachFixAgeS = 0;
    switch (state) {
      case TripState.navToGate || TripState.approachMuted || TripState.expanded || TripState.nearbyReady || TripState.amberSteady:
        _enter(networkDegraded ? TripState.greyBreathing : TripState.cruising);
      default:
        break;
    }
  }

  void onFgsStarted() {
    if (!fgsStartAccepted || sessionId == null || fgsRunning) return;
    fgsRunning = true;
    final distance = _distanceToDestinationM;
    final withinTrigger = destinationLocked && distance != null && distance <= NativeConfig.TRIGGER_DISTANCE_M;
    if (_treatAsOnSite || withinTrigger) {
      jitTriggered = true;
      readinessLatched = true;
      recommend = true;
      _enter(TripState.amberSteady);
    } else {
      recommend = false;
      _enter(TripState.cruising);
    }
  }

  void onDistanceToDestination(int distanceM) {
    if (!fgsRunning) return;
    if (state == TripState.expanded) {
      _distanceToDestinationM = distanceM;
      return;
    }
    if (state == TripState.approachMuted || state == TripState.parkedConfirmed || state == TripState.navToGate) return;
    _distanceToDestinationM = distanceM;
    _armBackgroundPrefetch(distanceM);
  }

  void onApproachSample({
    required int distanceM,
    required double speedKmh,
    required int lowSpeedHoldS,
    required int fixAgeS,
    int exitHoldS = 0,
  }) {
    if (!fgsRunning) return;
    _lastApproachDistanceM = distanceM;
    _lastApproachSpeedKmh = speedKmh;
    _lastApproachLowSpeedHoldS = lowSpeedHoldS;
    _lastApproachFixAgeS = fixAgeS;
    if (state == TripState.expanded) return;
    if (state == TripState.approachMuted && distanceM > NativeConfig.APPROACH_EXIT_DISTANCE_M && exitHoldS >= NativeConfig.APPROACH_EXIT_HOLD_S) {
      _enter(networkDegraded ? TripState.greyBreathing : TripState.cruising);
      return;
    }
    final coreHit = distanceM <= NativeConfig.APPROACH_CORE_RADIUS_M && speedKmh < NativeConfig.APPROACH_SPEED_THRESHOLD_KMH && lowSpeedHoldS >= NativeConfig.APPROACH_CONSECUTIVE_S;
    final deadReckoning = distanceM <= NativeConfig.APPROACH_RADIUS_M && speedKmh < NativeConfig.DEAD_RECKONING_SPEED_KMH && fixAgeS > NativeConfig.GPS_FIX_STALE_S;
    if ((coreHit || deadReckoning) && state != TripState.parkedConfirmed && state != TripState.alertBreathing) {
      _enter(TripState.approachMuted);
    }
  }

  void onNetworkFailure() {
    if (!fgsRunning) return;
    networkDegraded = true;
    recommend = false;
    // r7：已確認車場導航中（例如轉場 commit 失敗）保持 navToGate，唔丟失已確認狀態。
    if (state == TripState.approachMuted || state == TripState.navToGate) return;
    _enter(TripState.greyBreathing);
  }

  void onRequestSuccess() {
    if (!fgsRunning || !networkDegraded) return;
    networkDegraded = false;
    if (state == TripState.navToGate) {
      _deferredAlert = null;
      recommend = false;
      return;
    }
    if (state == TripState.approachMuted) {
      _deferredAlert = null;
      return;
    }
    final pending = _deferredAlert;
    _deferredAlert = null;
    if (pending != null) {
      _raiseAlert(pending);
      return;
    }
    if ((jitTriggered || backgroundResolvePending) && statusPip == StatusPip.none) {
      _enter(TripState.amberSteady);
    } else if (statusPip != StatusPip.none) {
      _enter(TripState.nearbyReady);
    } else {
      _enter(TripState.cruising);
    }
  }

  void onDriveSample({required double speedKmh, required int deltaDistanceM, required int deltaTimeS}) {
    if (!fgsRunning) return;
    if (deltaDistanceM > 0) _accumulatedDistanceM += deltaDistanceM;
    if (speedKmh > NativeConfig.SILENCE_DRIVE_SPEED_KMH) {
      if (deltaTimeS > 0) _consecutiveHighSpeedS += deltaTimeS;
    } else {
      _consecutiveHighSpeedS = 0;
    }
    if (destinationLocked) return;
    if (_consecutiveHighSpeedS >= NativeConfig.SILENCE_DRIVE_DURATION_S || _accumulatedDistanceM >= NativeConfig.SILENCE_DRIVE_DISTANCE_M) {
      _raiseAlert(AlertCause.noDestination);
    }
  }

  void onNavigationConfidence(double confidence) {
    if (!fgsRunning) return;
    if (confidence < NativeConfig.NAV_CONFIDENCE_LOW_THRESHOLD) return;
    if (confidence >= NativeConfig.NAV_CONFIDENCE_HIGH_THRESHOLD) {
      destinationLocked = true;
      hasDestination = true;
      return;
    }
    _raiseAlert(AlertCause.navMidConfidence);
  }

  void onPlaceClarificationNeeded() {
    if (!fgsRunning) return;
    _raiseAlert(AlertCause.placeClarification);
  }

  void onBackgroundResolveStarted() {
    if (!fgsRunning) return;
    backgroundResolvePending = true;
    if (state == TripState.cruising || state == TripState.nearbyReady) _enter(TripState.amberSteady);
  }

  void onBackgroundResolveEnded() {
    backgroundResolvePending = false;
    if (state == TripState.amberSteady && !jitTriggered) {
      _enter(statusPip != StatusPip.none ? TripState.nearbyReady : TripState.cruising);
    }
  }

  void onDriverActionFailed(bool networkCause) {
    if (!fgsRunning) return;
    if (networkCause) {
      onNetworkFailure();
      return;
    }
    if (destinationLocked) return;
    _raiseAlert(AlertCause.driverActionFailed);
  }

  void onCommitSucceeded() {
    if (!fgsRunning) return;
    _enter(TripState.navToGate);
  }

  void enterExpanded() {
    if (!fgsRunning) return;
    if (state == TripState.navToGate || state == TripState.ended || state == TripState.parkedConfirmed) return;
    _enter(TripState.expanded);
  }

  void onCardDismissed(bool hasRecommendation) {
    if (!fgsRunning) return;
    if (state == TripState.navToGate || state == TripState.ended || state == TripState.parkedConfirmed) return;
    if (_approachConditionsMet()) {
      _enter(TripState.approachMuted);
      return;
    }
    _enter(hasRecommendation || statusPip != StatusPip.none ? TripState.nearbyReady : TripState.cruising);
  }

  void onJitSucceeded(StatusPip pip) {
    if (!fgsRunning || networkDegraded) return;
    if (state == TripState.approachMuted || state == TripState.parkedConfirmed || state == TripState.navToGate) return;
    _assignStatusPip(pip);
    recommend = false;
    if (state == TripState.expanded) return;
    _enter(TripState.nearbyReady);
  }

  void onRequiredPermissionRevoked() {
    if (!_inTrip()) return;
    _enter(TripState.ended);
    _assignCapsuleLight(CapsuleLight.none);
    _onTripEnded('PERMISSION_REVOKED');
    clearSession();
  }

  void onRecordAudioRevoked(bool ttsAvailable) {
    if (!_inTrip() || tripVoiceMode != TripVoiceMode.fullVoice) return;
    voiceInputEnabled = false;
    tripVoiceMode = ttsAvailable ? TripVoiceMode.announceManual : TripVoiceMode.manualOnly;
  }

  void onEngineShutdown(bool withinTargetOrBasementLoss) {
    if (!_inTrip()) return;
    if (withinTargetOrBasementLoss) {
      onParkedConfirmed();
    } else {
      _enter(TripState.ended);
      _assignCapsuleLight(CapsuleLight.none);
      _onTripEnded('ENGINE_SHUTDOWN');
      clearSession();
    }
  }

  void onParkedConfirmed() {
    if (!_inTrip()) return;
    _enter(TripState.parkedConfirmed);
    _assignCapsuleLight(CapsuleLight.none);
    _onTripEnded('PARKED_CONFIRMED');
    clearSession();
  }

  void onOemKilled() {
    if (!_inTrip()) return;
    _enter(TripState.ended);
    _assignCapsuleLight(CapsuleLight.none);
    clearSession();
  }

  void clearSession() {
    sessionId = null;
    fgsRunning = false;
    fgsStartAccepted = false;
    recommend = false;
    jitTriggered = false;
    readinessLatched = false;
    _assignStatusPip(StatusPip.none);
    destinationLocked = false;
    hasDestination = false;
    destinationName = null;
    destLat = null;
    destLng = null;
    destinationPoiId = null;
    isMicFgsActive = false;
    _treatAsOnSite = false;
    onSitePendingFix = false;
    networkDegraded = false;
    backgroundResolvePending = false;
    _deferredAlert = null;
    voiceInputEnabled = false;
    _distanceToDestinationM = null;
  }

  bool _withinTriggerForJit(int? distanceM) => fgsRunning && destinationLocked && distanceM != null && distanceM <= NativeConfig.TRIGGER_DISTANCE_M && !jitTriggered;

  void _armBackgroundPrefetch(int? distanceM) {
    if (state == TripState.navToGate || !_withinTriggerForJit(distanceM)) return;
    jitTriggered = true;
    readinessLatched = true;
    recommend = true;
    if (!networkDegraded && state != TripState.alertBreathing && state != TripState.approachMuted && state != TripState.parkedConfirmed && state != TripState.navToGate) {
      _enter(TripState.amberSteady);
    }
  }

  void _raiseAlert(AlertCause cause) {
    if (!fgsRunning) return;
    final mutedBefore = state == TripState.approachMuted;
    if (networkDegraded) {
      _deferredAlert = cause;
      _enter(TripState.greyBreathing);
      return;
    }
    _enter(TripState.alertBreathing);
    if (_spokenAlerts.add(cause)) {
      alertSpeakCount += 1;
      lastSpokenCue = _cueFor(cause);
      onAlertCueReady?.call(mutedBefore);
    }
  }

  String _cueFor(AlertCause cause) {
    final cue = switch (cause) {
      AlertCause.noDestination => ZhHkTemplates.SILENCE_DRIVE,
      AlertCause.navMidConfidence => ZhHkTemplates.ASK_DESTINATION,
      AlertCause.placeClarification => ZhHkTemplates.PLACE_CLARIFY,
      AlertCause.driverActionFailed => ZhHkTemplates.ASK_DESTINATION,
    };
    return ZhHkTemplates.withVoiceReplyPrompt(
      cue,
      tripVoiceMode,
      isMicFgsActive,
      canListen: voiceReplyAvailable,
    );
  }

  bool _approachConditionsMet() {
    final distance = _lastApproachDistanceM;
    if (distance == null) return false;
    final coreHit = distance <= NativeConfig.APPROACH_CORE_RADIUS_M && _lastApproachSpeedKmh < NativeConfig.APPROACH_SPEED_THRESHOLD_KMH && _lastApproachLowSpeedHoldS >= NativeConfig.APPROACH_CONSECUTIVE_S;
    final deadReckoning = distance <= NativeConfig.APPROACH_RADIUS_M && _lastApproachSpeedKmh < NativeConfig.DEAD_RECKONING_SPEED_KMH && _lastApproachFixAgeS > NativeConfig.GPS_FIX_STALE_S;
    return coreHit || deadReckoning;
  }

  bool _onSiteGpsOk(bool gpsAvailable, double? accuracyM) => gpsAvailable && accuracyM != null && accuracyM <= NativeConfig.ON_SITE_GPS_ACCURACY_MAX_M;

  bool _inTrip() => sessionId != null || fgsStartAccepted || fgsRunning;

  StartOutcome _rejected(String? spokenCue) {
    lastSpokenCue = spokenCue;
    return StartOutcome(accepted: false, sessionId: null, spokenCue: spokenCue, recommend: false, startFgs: false);
  }

  void _assignCapsuleLight(CapsuleLight next) {
    if (capsuleLight == next) return;
    capsuleLight = next;
    onStateChanged?.call();
  }

  void _assignStatusPip(StatusPip next) {
    if (statusPip == next) return;
    statusPip = next;
    onStateChanged?.call();
  }

  void _enter(TripState next) {
    final nextLight = switch (next) {
      TripState.preFgs || TripState.ended || TripState.parkedConfirmed => CapsuleLight.none,
      TripState.cruising || TripState.nearbyReady || TripState.expanded || TripState.navToGate || TripState.approachMuted => CapsuleLight.greySteady,
      TripState.amberSteady => CapsuleLight.amberSteady,
      TripState.greyBreathing => CapsuleLight.greyBreathing,
      TripState.alertBreathing => CapsuleLight.alertBreathing,
    };
    final changed = state != next || capsuleLight != nextLight;
    state = next;
    capsuleLight = nextLight;
    if (changed) onStateChanged?.call();
  }

  static String _defaultSessionId() {
    final random = Random.secure();
    final hex = List.generate(16, (_) => random.nextInt(256).toRadixString(16).padLeft(2, '0')).join();
    return 'sess_$hex';
  }
}
