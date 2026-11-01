import 'dart:async';

import '../constants/native_config.dart';
import '../net/api_client.dart';
import '../net/recommendation_card.dart';
import '../poi/poi_name_resolver.dart';
import '../poi/trie_result.dart';
import '../voice/voice_capability.dart';
import '../voice/zh_hk_templates.dart';
import 'distance_tracker.dart';
import 'sync_api.dart';
import 'today_trip_store.dart';
import 'today_trip_sync.dart';
import 'trip_ports.dart';
import 'trip_state_machine.dart';
import 'trip_types.dart';

class TripSnapshot {
  const TripSnapshot({required this.state, required this.capsuleLight, required this.sessionId});

  final TripState state;
  final CapsuleLight capsuleLight;
  final String? sessionId;
}

class _MutableNetwork implements NetworkStatusProvider {
  bool online = true;
  @override
  bool isOnline() => online;
}

class TripController {
  TripController({
    required this.platform,
    required this.api,
    required this.store,
    required this.sync,
    required this.nowMs,
    required this.delay,
    NetworkStatusProvider? network,
    this.resolver,
    String Function()? sessionIdFactory,
  }) : network = network ?? _MutableNetwork() {
    tsm = TripStateMachine(onTripEnded: (_) {}, sessionIdFactory: sessionIdFactory);
    tracker = DistanceTracker(DistanceTrackerListener(onSample: _onSample, onTierChanged: platform.applySamplingTier));
    detector = EngineShutdownDetector(nowMs: nowMs);
    tsm.onAlertCueReady = (mutedBefore) => _speakAlert(mutedBefore);
    tsm.onStateChanged = _emit;
  }

  final TripPlatform platform;
  final ApiClient api;
  final TodayTripStore store;
  final TodayTripSync sync;
  final NowMs nowMs;
  final Delay delay;
  final NetworkStatusProvider network;
  final PoiNameResolver? resolver;

  late final TripStateMachine tsm;
  late final DistanceTracker tracker;
  late final EngineShutdownDetector detector;

  final List<RecommendationCard> cards = [];
  final Set<String> fullCarparkIds = {};
  final StreamController<TripSnapshot> _changes = StreamController<TripSnapshot>.broadcast();

  int destinationEpoch = 0;
  String? committedCarparkId;
  String? committedCarparkName;
  bool shutdownInProgress = false;
  bool _jitLaunched = false;
  bool _greyRetryInFlight = false;
  bool _commitInFlight = false;
  bool _driverAsked = false;

  Stream<TripSnapshot> get changes => _changes.stream;
  TripState get state => tsm.state;
  CapsuleLight get capsuleLight => tsm.capsuleLight;
  String? get sessionId => tsm.sessionId;

  Future<StartOutcome> start(
    StartupEntry entry, {
    CapabilitySnapshot? voice,
    StartCommand? command,
    String? destinationName,
    double? gpsAccuracyM,
  }) async {
    _resetTripFields();
    if (voice != null) tsm.applyVoiceCapability(voice, false);
    final outcome = tsm.requestStart(entry, command: command, destinationName: destinationName, gpsAccuracyM: gpsAccuracyM);
    if (!outcome.accepted) {
      _speak(outcome.spokenCue, driverAsked: true);
      return outcome;
    }
    final started = await platform.startForeground(entry);
    if (!started) {
      await platform.stopForeground('BOOTSTRAP_TIMEOUT');
      return outcome;
    }
    tsm.onFgsStarted();
    platform.applySamplingTier(tracker.tier);
    _emit();
    return outcome;
  }

  void lockDestinationAt(String name, double? lat, double? lng, {String? poiId}) {
    destinationEpoch += 1;
    committedCarparkId = null;
    committedCarparkName = null;
    _jitLaunched = false;
    tracker.setDestination(lat, lng);
    tsm.onDestinationSet(
      distanceToDestinationM: tracker.distanceToDestinationM(),
      destinationName: name,
      destLat: lat,
      destLng: lng,
      destinationPoiId: poiId,
    );
    _maybePublishJit();
    _emit();
  }

  void lockDestinationByName(String name) {
    final resolved = resolver?.resolve(name);
    if (resolved is Hit) {
      lockDestinationAt(name, resolved.poi.lat, resolved.poi.lng, poiId: resolved.poi.id);
      return;
    }
    tsm.onPlaceClarificationNeeded();
  }

  void onFix(LocFix fix) {
    if (!tsm.fgsRunning) return;
    tracker.onFix(fix);
  }

  void onTick(int nowMs) {
    if (!tsm.fgsRunning) return;
    tracker.onTick(nowMs);
  }

  Future<void> requestRecommendation() async {
    if (_greyRetryInFlight) return;
    _greyRetryInFlight = true;
    _driverAsked = true;
    try {
      await _recommend(QueryTriggerMode.driverActive);
    } finally {
      _driverAsked = false;
      _greyRetryInFlight = false;
    }
  }

  Future<void> confirmCandidate(int index) async {
    if (_commitInFlight || index < 0 || index >= cards.length) return;
    _commitInFlight = true;
    final card = cards[index];
    final capturedSession = tsm.sessionId;
    final capturedEpoch = destinationEpoch;
    try {
      final outcome = await api.commitSession(capturedSession ?? '', card.carparkId);
      if (!_stillCurrent(capturedSession, allowEpochMismatch: true)) return;
      _applyNetworkOutcome(outcome);
      if (capturedEpoch != destinationEpoch) return;
      if (outcome.httpStatus == 409) {
        fullCarparkIds.add(card.carparkId);
        _speak(outcome.actionSpokenCue, driverAsked: true);
        return;
      }
      if (outcome.succeeded) {
        committedCarparkId = card.carparkId;
        committedCarparkName = card.name;
        tsm.onCommitSucceeded();
        final label = card.name.trim().isEmpty ? card.carparkId : card.name;
        platform.launchGateNavigation(label);
        return;
      }
      _speak(ZhHkTemplates.NETWORK_FAILED, driverAsked: true);
    } finally {
      _commitInFlight = false;
    }
  }

  Future<String> onEngineSignal(EngineSignal signal, {required bool disconnected}) async {
    if (!disconnected) {
      detector.onReconnected(signal);
      return 'RECONNECTED';
    }
    final token = detector.onDisconnected(signal);
    if (token == null) return 'IGNORED_EXTRA';
    await delay(NativeConfig.ENGINE_OFF_DEBOUNCE_MS);
    if (!detector.confirm(token)) return 'CANCELLED';
    final speed = tracker.recentSpeedKmh(nowMs());
    if (speed != null && speed >= NativeConfig.ENGINE_OFF_MAX_SPEED_KMH) {
      detector.reset();
      return 'IGNORED_MOVING';
    }
    if (shutdownInProgress) return 'ALREADY_SHUTTING_DOWN';
    shutdownInProgress = true;
    final distance = tracker.lastDistanceM;
    final within = tsm.state == TripState.approachMuted || (distance != null && distance <= NativeConfig.APPROACH_RADIUS_M);
    final session = tsm.sessionId;
    if (within && session != null) {
      store.upsertCandidate(session, committedCarparkId ?? '', tsm.destinationName ?? '', nowMs());
      if (network.isOnline()) {
        final outcome = await api.releaseSession(session, committedCarparkId ?? '', isOnline: true);
        if (!shutdownInProgress) _applyNetworkOutcome(outcome);
      }
    }
    tsm.onEngineShutdown(within);
    await delay(NativeConfig.TEARDOWN_FADE_MS);
    tracker.reset();
    detector.reset();
    committedCarparkId = null;
    committedCarparkName = null;
    fullCarparkIds.clear();
    shutdownInProgress = false;
    _emit();
    return within ? 'PARKED' : 'OUTSIDE';
  }

  Future<void> onShareIntake({String? title, double? lat, double? lng, required bool hasUrl}) async {
    if (!tsm.fgsStartAccepted) {
      final started = await start(StartupEntry.b2);
      if (!started.accepted) return;
    }
    final named = title != null && title.trim().isNotEmpty;
    if (lat != null && lng != null) {
      String? poiId;
      if (named) {
        final resolved = resolver?.resolve(title);
        if (resolved is Hit) poiId = resolved.poi.id;
      }
      lockDestinationAt(named ? title : '$lat,$lng', lat, lng, poiId: poiId);
      return;
    }
    if (!named) {
      tsm.onPlaceClarificationNeeded();
      return;
    }
    final resolved = resolver?.resolve(title);
    if (resolved is Hit) {
      lockDestinationAt(title, resolved.poi.lat, resolved.poi.lng, poiId: resolved.poi.id);
    } else if (resolved is Ambiguous) {
      tsm.onPlaceClarificationNeeded();
    } else {
      tsm.onBackgroundResolveStarted();
      lockDestinationAt(title, null, null);
      tsm.onBackgroundResolveEnded();
    }
  }

  Future<void> onNetworkChanged(bool online) async {
    if (network is _MutableNetwork) (network as _MutableNetwork).online = online;
    if (online) await sync.flushPendingConfirms();
  }

  void onPermissionRevoked(String permission) {
    if (permission == 'RECORD_AUDIO') {
      tsm.onRecordAudioRevoked(tsm.tripVoiceMode != TripVoiceMode.manualOnly);
      return;
    }
    tsm.onRequiredPermissionRevoked();
  }

  void onOemKilled() {
    tsm.onOemKilled();
  }

  void _onSample(DistanceSample sample) {
    if (!tsm.fgsRunning) return;
    tracker.setApproachMuted(tsm.state == TripState.approachMuted);
    tsm.onApproachSample(
      distanceM: sample.distanceM,
      speedKmh: sample.speedKmh,
      lowSpeedHoldS: sample.lowSpeedHoldS,
      fixAgeS: sample.fixAgeS,
      exitHoldS: sample.exitHoldS,
    );
    if (tsm.state == TripState.approachMuted || tsm.state == TripState.parkedConfirmed) return;
    if (sample.fresh && tsm.state != TripState.navToGate) {
      tsm.onDistanceToDestination(sample.distanceM);
    }
    _maybePublishJit();
  }

  void _maybePublishJit() {
    if (!tsm.fgsRunning || !tsm.recommend || _jitLaunched || shutdownInProgress) return;
    _jitLaunched = true;
    unawaited(_recommend(QueryTriggerMode.backgroundPrefetch));
  }

  Future<void> _recommend(QueryTriggerMode mode) async {
    final capturedSession = tsm.sessionId ?? '';
    final capturedEpoch = destinationEpoch;
    final outcome = await api.recommendAndRoute(
      RecommendRequest(
        sessionId: capturedSession,
        originLat: 0,
        originLng: 0,
        destLat: tsm.destLat ?? 0,
        destLng: tsm.destLng ?? 0,
        destName: tsm.destinationName ?? '',
        preference: 'SMART_HYBRID',
      ),
      mode,
    );
    if (capturedSession != (tsm.sessionId ?? '') || shutdownInProgress) return;
    _applyNetworkOutcome(outcome);
    if (capturedEpoch != destinationEpoch) return;
    final next = (outcome.response?.cards ?? const <RecommendationCard>[]).where((card) => !fullCarparkIds.contains(card.carparkId)).toList();
    cards
      ..clear()
      ..addAll(next);
    if (outcome.succeeded) {
      tsm.onJitSucceeded(StatusPip.none);
      if (mode == QueryTriggerMode.driverActive) {
        if (next.isEmpty) {
          _speak(ZhHkTemplates.NO_MORE_OPTIONS, driverAsked: true);
        } else {
          _speak(next.first.spokenCue, driverAsked: true);
        }
      }
    } else if (mode == QueryTriggerMode.driverActive) {
      for (final cue in outcome.spokenCues) {
        _speak(cue, driverAsked: true);
      }
    }
    _emit();
  }

  void _applyNetworkOutcome(CallOutcome outcome) {
    if (outcome.shouldTurnGrey) tsm.onNetworkFailure();
    if (outcome.shouldTurnReady) {
      if (tsm.networkDegraded) tsm.onRequestSuccess();
      if (tsm.fgsRunning && tsm.state != TripState.nearbyReady) tsm.onJitSucceeded(StatusPip.none);
    }
  }

  bool _stillCurrent(String? capturedSession, {required bool allowEpochMismatch}) {
    if (shutdownInProgress) return false;
    return capturedSession == tsm.sessionId;
  }

  void _speakAlert(bool mutedBefore) {
    if (mutedBefore && !_driverAsked) return;
    _speak(tsm.lastSpokenCue, driverAsked: _driverAsked || tsm.state == TripState.alertBreathing);
  }

  void _speak(String? text, {required bool driverAsked}) {
    if (text == null || text.isEmpty) return;
    if (tsm.tripVoiceMode == TripVoiceMode.manualOnly) return;
    if (tsm.state == TripState.approachMuted && !driverAsked) return;
    platform.speak(text);
  }

  void _resetTripFields() {
    cards.clear();
    fullCarparkIds.clear();
    committedCarparkId = null;
    committedCarparkName = null;
    destinationEpoch = 0;
    _jitLaunched = false;
    shutdownInProgress = false;
    tracker.reset();
    detector.reset();
  }

  void _emit() {
    if (_changes.isClosed) return;
    _changes.add(TripSnapshot(state: tsm.state, capsuleLight: tsm.capsuleLight, sessionId: tsm.sessionId));
  }
}
