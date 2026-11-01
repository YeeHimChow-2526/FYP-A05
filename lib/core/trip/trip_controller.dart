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

class TripNetwork implements NetworkStatusProvider {
  TripNetwork([this.online = true]);

  bool online;

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
  }) : network = network ?? TripNetwork() {
    tsm = TripStateMachine(onTripEnded: _handleTripEnded, sessionIdFactory: sessionIdFactory);
    tracker = DistanceTracker(DistanceTrackerListener(onSample: _onSample, onTierChanged: platform.applySamplingTier));
    detector = EngineShutdownDetector(nowMs: nowMs);
    tsm.onAlertCueReady = (mutedBefore) => _speakAlert(mutedBefore);
    tsm.onStateChanged = _onStateChanged;
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
  final List<Timer> _timers = [];

  int destinationEpoch = 0;
  int _lifecycleToken = 0;
  String? committedCarparkId;
  String? committedCarparkName;
  bool shutdownInProgress = false;
  bool _greyRetryInFlight = false;
  bool _commitInFlight = false;
  bool _driverAsked = false;
  bool _disposed = false;
  String? shareDroppedReason;

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
    if (voice != null) tsm.applyVoiceCapability(voice, false);
    final outcome = tsm.requestStart(entry, command: command, destinationName: destinationName, gpsAccuracyM: gpsAccuracyM);
    if (!outcome.accepted) {
      _speak(outcome.spokenCue, driverAsked: true);
      return outcome;
    }
    final token = ++_lifecycleToken;
    _resetTripFields();
    final started = await platform.startForeground(entry);
    if (token != _lifecycleToken || _disposed) {
      return const StartOutcome(accepted: false, sessionId: null, spokenCue: null, recommend: false, startFgs: false);
    }
    if (!started) {
      await platform.stopForeground('BOOTSTRAP_TIMEOUT');
      if (token == _lifecycleToken && !_disposed) tsm.clearSession();
      return const StartOutcome(accepted: false, sessionId: null, spokenCue: null, recommend: false, startFgs: false);
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
    tracker.setDestination(lat, lng);
    final wasJitTriggered = tsm.jitTriggered;
    tsm.onDestinationSet(
      distanceToDestinationM: tracker.distanceToDestinationM(),
      destinationName: name,
      destLat: lat,
      destLng: lng,
      destinationPoiId: poiId,
    );
    _publishJitEdge(wasJitTriggered);
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
    if (!tsm.fgsRunning || shutdownInProgress || tsm.state == TripState.navToGate) return;
    tracker.onFix(fix);
  }

  void onTick(int nowMs) {
    if (!tsm.fgsRunning || shutdownInProgress || tsm.state == TripState.navToGate) return;
    tracker.onTick(nowMs);
  }

  Future<void> requestRecommendation() => _recommend(QueryTriggerMode.driverActive, driverAsked: true);

  Future<void> retryFromGrey() async {
    if (_greyRetryInFlight || shutdownInProgress) return;
    _greyRetryInFlight = true;
    try {
      await _recommend(QueryTriggerMode.driverActive, driverAsked: true);
    } finally {
      _greyRetryInFlight = false;
    }
  }

  Future<void> confirmCandidate(int index) async {
    if (_commitInFlight || tsm.state == TripState.navToGate || index < 0 || index >= cards.length) return;
    _commitInFlight = true;
    final card = cards[index];
    final capturedSession = tsm.sessionId;
    final capturedEpoch = destinationEpoch;
    try {
      final outcome = await api.commitSession(capturedSession ?? '', card.carparkId);
      if (!_stillCurrent(capturedSession, capturedEpoch)) return;
      _applyNetworkOutcome(outcome);
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
    if (!tsm.fgsRunning || shutdownInProgress) return 'IGNORED_NOT_RUNNING';
    if (!disconnected) {
      detector.onReconnected(signal);
      return 'RECONNECTED';
    }
    final token = detector.onDisconnected(signal);
    if (token == null) return 'IGNORED_EXTRA';
    final opToken = _lifecycleToken;
    await delay(NativeConfig.ENGINE_OFF_DEBOUNCE_MS);
    if (_disposed || opToken != _lifecycleToken) return 'CANCELLED';
    if (!tsm.fgsRunning || shutdownInProgress) return 'IGNORED_NOT_RUNNING';
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
    final carparkId = _parkedCarparkId();
    final carparkName = _parkedCarparkName();
    tsm.onEngineShutdown(within);
    if (within && session != null) {
      store.upsertCandidate(session, carparkId, carparkName, nowMs());
      if (network.isOnline()) {
        unawaited(api.releaseSession(session, carparkId, isOnline: true));
      }
    }
    final fadeToken = _lifecycleToken;
    await delay(NativeConfig.TEARDOWN_FADE_MS);
    if (_disposed || fadeToken != _lifecycleToken) return within ? 'PARKED' : 'OUTSIDE';
    _finishTeardown(within ? 'PARKED_CONFIRMED' : 'ENGINE_SHUTDOWN');
    return within ? 'PARKED' : 'OUTSIDE';
  }

  Future<void> onShareIntake({String? title, double? lat, double? lng, required bool hasUrl}) async {
    if (!tsm.fgsRunning) {
      final started = await start(StartupEntry.b2);
      if (!started.accepted || !tsm.fgsRunning) {
        shareDroppedReason = 'SHARE_DROPPED';
        return;
      }
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
    if (network is TripNetwork) (network as TripNetwork).online = online;
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

  void dispose() {
    if (_disposed) return;
    _disposed = true;
    _lifecycleToken += 1;
    for (final timer in _timers) {
      timer.cancel();
    }
    _timers.clear();
    if (!_changes.isClosed) _changes.close();
  }

  void _onSample(DistanceSample sample) {
    if (!tsm.fgsRunning || shutdownInProgress || tsm.state == TripState.navToGate) return;
    final wasJitTriggered = tsm.jitTriggered;
    tsm.onApproachSample(
      distanceM: sample.distanceM,
      speedKmh: sample.speedKmh,
      lowSpeedHoldS: sample.lowSpeedHoldS,
      fixAgeS: sample.fixAgeS,
      exitHoldS: sample.exitHoldS,
    );
    if (tsm.state == TripState.approachMuted || tsm.state == TripState.parkedConfirmed || tsm.state == TripState.navToGate) return;
    if (sample.fresh) tsm.onDistanceToDestination(sample.distanceM);
    _publishJitEdge(wasJitTriggered);
  }

  void _onStateChanged() {
    tracker.setApproachMuted(tsm.state == TripState.approachMuted);
    _emit();
  }

  void _publishJitEdge(bool wasJitTriggered) {
    if (_disposed || shutdownInProgress || !tsm.fgsRunning) return;
    if (!wasJitTriggered && tsm.jitTriggered && tsm.recommend) {
      unawaited(_recommend(QueryTriggerMode.backgroundPrefetch, driverAsked: false));
    }
  }

  Future<void> _recommend(QueryTriggerMode mode, {required bool driverAsked}) async {
    final capturedSession = tsm.sessionId;
    final capturedEpoch = destinationEpoch;
    final outcome = await api.recommendAndRoute(
      RecommendRequest(
        sessionId: capturedSession ?? '',
        originLat: 0,
        originLng: 0,
        destLat: tsm.destLat ?? 0,
        destLng: tsm.destLng ?? 0,
        destName: tsm.destinationName ?? '',
        preference: 'SMART_HYBRID',
        expandScarcity: false,
      ),
      mode,
    );
    if (!_stillCurrent(capturedSession, capturedEpoch)) return;
    _driverAsked = driverAsked;
    try {
      _applyNetworkOutcome(outcome);
      if (!_stillCurrent(capturedSession, capturedEpoch)) return;
      if (!outcome.succeeded) {
        if (mode == QueryTriggerMode.driverActive) {
          for (final cue in outcome.spokenCues) {
            _speak(cue, driverAsked: true);
          }
        }
        _emit();
        return;
      }
      final next = (outcome.response?.cards ?? const <RecommendationCard>[]).where((card) => !fullCarparkIds.contains(card.carparkId)).toList();
      if (next.isNotEmpty) {
        cards
          ..clear()
          ..addAll(next);
      }
      if (mode == QueryTriggerMode.driverActive) {
        if (next.isEmpty) {
          _speak(ZhHkTemplates.NO_MORE_OPTIONS, driverAsked: true);
        } else {
          _speak(next.first.spokenCue, driverAsked: true);
        }
      }
      _emit();
    } finally {
      _driverAsked = false;
    }
  }

  void _applyNetworkOutcome(CallOutcome outcome) {
    if (outcome.shouldTurnGrey) tsm.onNetworkFailure();
    if (outcome.shouldTurnReady) {
      if (tsm.networkDegraded) tsm.onRequestSuccess();
      if (tsm.fgsRunning && tsm.state != TripState.nearbyReady) tsm.onJitSucceeded(StatusPip.none);
    }
  }

  bool _stillCurrent(String? capturedSession, int capturedEpoch) {
    if (_disposed || shutdownInProgress) return false;
    return capturedSession != null && capturedSession == tsm.sessionId && capturedEpoch == destinationEpoch;
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

  void _handleTripEnded(String reason) {
    if (_disposed) return;
    if (reason == 'ENGINE_SHUTDOWN' || reason == 'PARKED_CONFIRMED') {
      platform.interruptSpeech();
      return;
    }
    _finishTeardown(reason);
  }

  void _finishTeardown(String reason) {
    platform.interruptSpeech();
    unawaited(platform.stopForeground(reason));
    tracker.reset();
    detector.reset();
    committedCarparkId = null;
    committedCarparkName = null;
    fullCarparkIds.clear();
    _commitInFlight = false;
    _greyRetryInFlight = false;
    shutdownInProgress = false;
    _emit();
  }

  String _parkedCarparkId() => committedCarparkId ?? cards.firstOrNull?.carparkId ?? tsm.destinationName ?? '';

  String _parkedCarparkName() => committedCarparkName ?? cards.firstOrNull?.name ?? tsm.destinationName ?? '';

  void _resetTripFields() {
    cards.clear();
    fullCarparkIds.clear();
    committedCarparkId = null;
    committedCarparkName = null;
    shutdownInProgress = false;
    tracker.reset();
    detector.reset();
  }

  void _emit() {
    if (_disposed || _changes.isClosed) return;
    _changes.add(TripSnapshot(state: tsm.state, capsuleLight: tsm.capsuleLight, sessionId: tsm.sessionId));
  }
}
