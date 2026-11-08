import 'dart:async';
import 'dart:developer' as developer;
import 'package:flutter/foundation.dart' show debugPrint;

import '../constants/native_config.dart';
import '../nav/fake_nav_engine.dart';
import '../nav/gate_coord_resolver.dart';
import '../nav/nav_engine.dart';
import '../net/api_client.dart';
import '../net/recommendation_card.dart';
import '../net/server_issue.dart';
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
import 'vacancy_pip.dart';

class TripSnapshot {
  const TripSnapshot({
    required this.state,
    required this.capsuleLight,
    required this.sessionId,
    this.cards = const [],
    this.destinationName,
    this.destLat,
    this.destLng,
    this.lastOriginLat,
    this.lastOriginLng,
    this.readinessLatched = false,
    this.vacancyPip = StatusPip.none,
    this.vacancyFresh = false,
    this.cardsOpen = false,
    this.destinationEpoch = 0,
    this.lockRelease = LockReleaseState.none,
    this.voiceInputBlocked,
    this.serverIssue,
    this.pendingSwitchName,
    this.pendingSwitchCarparkId,
  });

  final TripState state;
  final CapsuleLight capsuleLight;
  final String? sessionId;
  final List<RecommendationCard> cards;
  final String? destinationName;
  final double? destLat;
  final double? destLng;
  final double? lastOriginLat;
  final double? lastOriginLng;

  /// 1 km 就緒綠燈（per 已確認目的地 latch），唔代表有空位。
  final bool readinessLatched;

  /// 空位 pip（只由當前目的地最近一次成功 recommend 嘅首選卡計）；見 [VacancyPip]。
  final StatusPip vacancyPip;

  /// false＝未有／已失效（網絡失敗、改目的地、資料過期）；UI 唔可以當新鮮空位顯示。
  final bool vacancyFresh;

  /// 卡片列預設收起；只有司機明確動作（車場卡掣、詳細啲、手動／冇 STT 嘅 P）先打開。
  final bool cardsOpen;
  final int destinationEpoch;

  /// 舊目的地車位鎖：pending＝釋放中／等重試；failed＝網絡或伺服器錯（會再試）；unsupported＝後端唔支援。
  final LockReleaseState lockRelease;

  /// 非 null＝本次行程語音輸入停用原因（例如 NO_PERMISSION）；UI 照用手動掣。
  final String? voiceInputBlocked;

  /// typed 伺服器問題（null＝冇）；`message` 係用戶動作。
  final ServerIssue? serverIssue;

  /// 第 3 次起未收到同意：非 null 就停喺呢個車場，舊路線保持。
  final String? pendingSwitchName;

  /// 同上，用車場 id 對應原本嗰張停車場卡；唔再另開一張確認卡。
  final String? pendingSwitchCarparkId;
}

/// 改目的地時舊車位鎖嘅釋放狀態（合約 v2 `DESTINATION_CHANGED`）。
enum LockReleaseState { none, pending, failed, unsupported }

class HeldLock {
  const HeldLock({required this.carparkId, required this.lockRevision, required this.epoch});
  final String carparkId;

  /// null＝舊後端（commit 回應冇 lock_revision）；改目的地會明確報 unsupported。
  final int? lockRevision;
  final int epoch;
}

class PendingLockRelease {
  PendingLockRelease({required this.carparkId, required this.lockRevision, required this.newEpoch});
  final String carparkId;
  final int? lockRevision;
  final int newEpoch;
  int attempts = 0;
  LockReleaseState state = LockReleaseState.pending;
  String? lastError;
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
    NavEngine? navEngine,
    String Function()? sessionIdFactory,
  }) : network = network ?? TripNetwork(),
       navEngine = navEngine ?? FakeNavEngine() {
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
  final NavEngine navEngine;

  late final TripStateMachine tsm;
  late final DistanceTracker tracker;
  late final EngineShutdownDetector detector;

  final List<RecommendationCard> cards = [];
  final Set<String> fullCarparkIds = {};
  final StreamController<TripSnapshot> _changes = StreamController<TripSnapshot>.broadcast(sync: true);

  int destinationEpoch = 0;
  int _lifecycleToken = 0;
  String? committedCarparkId;

  /// 今次行程已成功確認過嘅車場。第 3 個新車場先停低等同意；失敗、同一間、改目的地都唔重置。
  final Set<String> _countedCarparkConfirms = {};
  String? pendingSwitchCarparkId;
  String? pendingSwitchName;
  int? _pendingSwitchIndex;
  int _switchConsentToken = 0;
  bool _switchAcceptStarted = false;

  /// 正常模式（即時 API）先隔離示範卡。DEMO 行程唔開，fixture 卡仍然可以確認。
  bool isolateDemonstrationSupply = false;
  String? committedCarparkName;
  bool shutdownInProgress = false;
  bool _greyRetryInFlight = false;
  bool _commitInFlight = false;
  bool _driverAsked = false;
  bool _disposed = false;
  bool _loggedMissingOrigin = false;
  bool _spokeMissingOrigin = false;
  String? shareDroppedReason;
  LocFix? _lastFix;
  Future<void>? _jitRecommend;

  Stream<TripSnapshot> get changes => _changes.stream;
  TripSnapshot get snapshot => TripSnapshot(
        state: tsm.state,
        capsuleLight: tsm.capsuleLight,
        sessionId: tsm.sessionId,
        cards: List<RecommendationCard>.unmodifiable(cards),
        destinationName: tsm.destinationName,
        destLat: tsm.destLat,
        destLng: tsm.destLng,
        lastOriginLat: _lastFix?.lat,
        lastOriginLng: _lastFix?.lng,
        readinessLatched: tsm.readinessLatched,
        vacancyPip: tsm.statusPip,
        vacancyFresh: vacancyFresh,
        cardsOpen: cardsOpen,
        destinationEpoch: destinationEpoch,
        lockRelease: pendingLockRelease?.state ?? LockReleaseState.none,
        voiceInputBlocked: voiceInputBlockedReason,
        serverIssue: serverIssue,
        pendingSwitchName: pendingSwitchName,
        pendingSwitchCarparkId: pendingSwitchCarparkId,
      );
  HeldLock? heldLock;
  PendingLockRelease? pendingLockRelease;
  String? _commitInFlightCarpark;
  bool _releaseInFlight = false;
  String? lastCommitConflict;
  bool cardsOpen = false;
  bool vacancyFresh = false;
  bool get tripRunning => tsm.fgsRunning;
  TripState get state => tsm.state;
  CapsuleLight get capsuleLight => tsm.capsuleLight;
  String? get sessionId => tsm.sessionId;

  Future<StartOutcome> start(
    StartupEntry entry, {
    CapabilitySnapshot? voice,
    StartCommand? command,
    String? destinationName,
    double? gpsAccuracyM,
    bool awaitOnSiteFix = false,
  }) async {
    if (voice != null) tsm.applyVoiceCapability(voice, false);
    _onSiteAccuracySpoken = false;
    final outcome = tsm.requestStart(
      entry,
      command: command,
      destinationName: destinationName,
      gpsAccuracyM: gpsAccuracyM,
      awaitOnSiteFix: awaitOnSiteFix,
    );
    if (!outcome.accepted) {
      _speak(outcome.spokenCue, driverAsked: true);
      return outcome;
    }
    final token = ++_lifecycleToken;
    _resetTripFields();
    sessionStartedAtMs = nowMs();
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
    // Android SpeechRecognizer 唔做長開熱詞：開程唔開咪，只喺 P 口播後 one-shot 聽追問。
    _emit();
    return outcome;
  }

  /// t1253：行程中語音能力改變（用戶開關、權限結果、返回 App）。用戶關咗就唔會被重開；
  /// 唔再可用就即刻停咪。只改模式，唔會因為有權限而主動開咪。
  void updateVoiceCapability(CapabilitySnapshot voice) {
    tsm.applyVoiceCapability(voice, true);
    if (!_voiceUsable) platform.stopListening();
    _emit();
  }

  /// 地圖頁「現場即泊」：以 StartupEntry.onSite 開 FGS；第一個精度 ≤ ON_SITE_GPS_ACCURACY_MAX_M 嘅 fix
  /// 會被鎖成目的地（距離 0 ≤ TRIGGER_DISTANCE_M）並即時發 JIT recommend。
  Future<StartOutcome> startOnSite({CapabilitySnapshot? voice}) {
    return start(StartupEntry.onSite, voice: voice, awaitOnSiteFix: true);
  }

  /// 偏好分頁寫入（DriverPreference.code）；recommend 請求用佢，唔再硬編 SMART_HYBRID。
  String preferenceCode = 'SMART_HYBRID';

  static const String onSiteDestinationName = '現在位置';
  bool _onSiteAccuracySpoken = false;

  void _tryLockOnSite(LocFix fix) {
    if (!tsm.onSitePendingFix) return;
    if (fix.accuracyM <= NativeConfig.ON_SITE_GPS_ACCURACY_MAX_M) {
      tsm.onSitePendingFix = false;
      lockDestinationAt(onSiteDestinationName, fix.lat, fix.lng);
      return;
    }
    if (!_onSiteAccuracySpoken) {
      _onSiteAccuracySpoken = true;
      _speak(ZhHkTemplates.ON_SITE_LOCATION_MISSED, driverAsked: true);
    }
  }

  /// 目的地身份＝座標＋POI id（名改咗但同一點唔算改目的地）。
  bool _isDestinationChange(double? lat, double? lng, String? poiId) {
    if (!tsm.destinationLocked) return false;
    return tsm.destLat != lat || tsm.destLng != lng || tsm.destinationPoiId != poiId;
  }

  void _resetForNewDestination() {
    final wasGuiding = tsm.state == TripState.navToGate;
    _releaseNeededFor = heldLock?.carparkId ?? _commitInFlightCarpark ?? pendingLockRelease?.carparkId;
    _releaseRevision = heldLock?.lockRevision ?? pendingLockRelease?.lockRevision;
    _releaseLegacy = heldLock != null && heldLock!.lockRevision == null;
    heldLock = null;
    cards.clear();
    fullCarparkIds.clear();
    _clearPendingSwitch();
    cardsOpen = false;
    vacancyFresh = false;
    tsm.onDestinationChanged();
    if (wasGuiding) developer.log('destination changed during gate guidance', name: 'TripController');
  }

  // ---- 導航目標（同「用戶目的地」分開）----
  // 撳「去」／改目的地 → 即刻導航去用戶目的地；只有成功 commit 一張卡先改導航去車場。
  // 所有 start／stop 串行執行；每次新目標 +1 serial，被取代嘅請求唔會再發出，遲到結果唔會改狀態。
  NavTarget? navTarget;
  int _navSerial = 0;
  Future<void> _navChain = Future<void>.value();

  Future<bool> _routeTo(NavTargetKind kind, String title, double lat, double lng) {
    final serial = ++_navSerial;
    navTarget = NavTarget(kind: kind, title: title, lat: lat, lng: lng, state: NavTargetState.starting);
    _emit();
    final done = Completer<bool>();
    _navChain = _navChain.then((_) async {
      if (serial != _navSerial || _disposed) {
        done.complete(false);
        return;
      }
      var ok = false;
      try {
        ok = await navEngine.startGuidanceTo(title: title, lat: lat, lng: lng);
      } catch (error) {
        developer.log('startGuidanceTo threw $error', name: 'TripController');
      }
      if (serial == _navSerial && !_disposed) {
        navTarget = navTarget?.withState(ok ? NavTargetState.guiding : NavTargetState.failed);
        _emit();
      }
      done.complete(ok && serial == _navSerial);
    });
    return done.future;
  }

  Future<void> _navStop() {
    ++_navSerial;
    navTarget = null;
    final done = Completer<void>();
    _navChain = _navChain.then((_) async {
      try {
        await navEngine.stopGuidance();
      } catch (error) {
        developer.log('stopGuidance failed $error', name: 'TripController');
      }
      done.complete();
    });
    return done.future;
  }

  /// 送出／重試 `DESTINATION_CHANGED`。成功（包括 NO_LOCK／LOCK_NEWER_KEPT）或被更新嘅改動取代先清；
  /// 網絡／5xx 保留 failed 等下次（上線、成功 recommend）重試；422（舊後端）標 unsupported，唔假裝成功。
  Future<void> _flushDestinationRelease() async {
    final pending = pendingLockRelease;
    final session = tsm.sessionId;
    if (pending == null || session == null || _releaseInFlight || _disposed) return;
    if (pending.state == LockReleaseState.unsupported) return;
    if (!network.isOnline()) {
      pending.state = LockReleaseState.failed;
      pending.lastError = 'OFFLINE';
      _emit();
      return;
    }
    _releaseInFlight = true;
    pending.attempts += 1;
    try {
      final outcome = await api.releaseSession(
        session,
        pending.carparkId,
        reason: ApiClient.RELEASE_DESTINATION_CHANGED,
        lockRevision: pending.lockRevision,
        newDestinationEpoch: pending.newEpoch,
      );
      if (!identical(pendingLockRelease, pending) || session != tsm.sessionId) return;
      final status = outcome.bodyFields['status'] as String?;
      if (outcome.succeeded || (outcome.httpStatus == 409 && (status == 'STALE_EPOCH' || status == 'SESSION_CLOSED'))) {
        pendingLockRelease = null;
      } else if (outcome.httpStatus == 422 || outcome.httpStatus == 404) {
        pending
          ..state = LockReleaseState.unsupported
          ..lastError = 'HTTP_${outcome.httpStatus}${status == null ? '' : '_$status'}';
      } else {
        pending
          ..state = LockReleaseState.failed
          ..lastError = status ?? 'HTTP_${outcome.httpStatus}';
      }
    } finally {
      _releaseInFlight = false;
      _emit();
      // 送緊嗰陣又改咗目的地（A→B→A）：較新嗰筆而家先送。
      final next = pendingLockRelease;
      if (next != null && !identical(next, pending) && next.state == LockReleaseState.pending) {
        unawaited(_flushDestinationRelease());
      }
    }
  }

  Future<void> retryLockRelease() => _flushDestinationRelease();

  static String _idempotencyKey(int epoch, String carparkId) {
    final safe = carparkId.replaceAll(RegExp(r'[^A-Za-z0-9_.:-]'), '_');
    final key = 'c$epoch-$safe';
    final padded = key.length < 8 ? key.padRight(8, '0') : key;
    return padded.length > 128 ? padded.substring(0, 128) : padded;
  }

  /// 原生 STT 文字（已由現有 CommandCodes 解析）派發。只喺全語音（有 STT、開咗語音輸入）＋行程中生效。
  String onVoiceUtterance({
    required String code,
    String text = '',
    String? destinationName,
    bool confirmed = false,
    bool lowConfidence = false,
    bool blockedAsSelfHearing = false,
  }) {
    if (_disposed || !tsm.fgsRunning || shutdownInProgress) return 'IGNORED_NOT_RUNNING';
    if (!_voiceUsable) return 'IGNORED_NO_STT';
    if (lowConfidence || blockedAsSelfHearing) return 'IGNORED_LOW_CONFIDENCE';
    switch (code) {
      case 'SHOW_DETAILS':
        if (cards.isEmpty) return 'NO_CARDS';
        showCardDetails();
        return 'SHOW_DETAILS';
      case 'FIND_PARKING':
        unawaited(requestRecommendation());
        return 'FIND_PARKING';
      case 'SET_DESTINATION':
        final name = destinationName?.trim() ?? '';
        if (name.isEmpty) return 'IGNORED_NO_DESTINATION';
        unawaited(submitDestinationText(name));
        return 'SET_DESTINATION';
      case 'CONFIRM_TARGET':
        if (!confirmed) return 'IGNORED_UNCONFIRMED';
        if (pendingSwitchCarparkId != null) {
          unawaited(acceptPendingSwitch());
          return 'CONFIRM_TARGET';
        }
        if (cards.isEmpty) return 'IGNORED_UNCONFIRMED';
        // 只確認實際首選（第一張 viable）；佢唔可以預留就拒絕，唔會跳去鎖另一張冇講過嘅卡。
        unawaited(confirmCandidate(leadIndex ?? 0));
        return 'CONFIRM_TARGET';
      case 'CANCEL':
        platform.interruptSpeech();
        if (pendingSwitchCarparkId != null) {
          rejectPendingSwitch();
          return 'CANCEL';
        }
        if (cardsOpen) {
          cardsOpen = false;
          _emit();
        }
        return 'CANCEL';
      case 'NEXT_OPTION':
        return 'UNSUPPORTED';
      default:
        return 'UNKNOWN';
    }
  }

  /// 車場卡掣：明確打開／收起。
  void toggleCards() {
    cardsOpen = !cardsOpen;
    _emit();
  }

  /// 原生辨識錯誤：永久（NO_PERMISSION／NO_RECOGNIZER／LANGUAGE_UNAVAILABLE）→ 本次行程停用語音、轉手動。
  /// 暫時性（NO_MATCH、SPEECH_TIMEOUT、HOTWORD_UNSUPPORTED…）只記低，唔重試開咪（避免長開）。
  void onVoiceError(String code, {bool permanent = false}) {
    lastVoiceError = code;
    if (permanent && voiceInputBlockedReason == null) {
      voiceInputBlockedReason = code;
      if (cards.isNotEmpty) cardsOpen = true;
    }
    _emit();
  }

  String? lastVoiceError;
  String? voiceInputBlockedReason;

  bool get _voiceUsable =>
      tsm.tripVoiceMode == TripVoiceMode.fullVoice && tsm.voiceInputEnabled && voiceInputBlockedReason == null;

  /// 「詳細啲」：彈出當前卡片並同步讀出首選（語音指令接駁喺 Dart 未有；呢個係畀之後接嘅入口）。
  /// 實際首選：第一張 viable 卡；全部係參考卡 → null。
  int? get leadIndex {
    final i = cards.indexWhere((card) => card.isViable);
    return i < 0 ? null : i;
  }

  void showCardDetails() {
    if (cards.isEmpty) return;
    cardsOpen = true;
    // 有首選讀首選；只得參考卡就讀第一張參考卡（伺服器文字已講明「只供參考」）。
    _speak(cards[leadIndex ?? 0].spokenCue, driverAsked: true);
    _emit();
  }

  String? _abortedSession;
  String? _releaseNeededFor;
  int? _releaseRevision;
  bool _releaseLegacy = false;

  void lockDestinationAt(String name, double? lat, double? lng, {String? poiId}) {
    // destinationEpoch 每次鎖都 +1：舊 recommend／commit 結果（包括 A→B→A 嘅 A 舊請求）一律作廢。
    _releaseNeededFor = null;
    serverIssue = null;
    _pendingListen = null; // 改目的地：未講完嗰句嘅 onSpeechDone 唔可以再開咪
    final changed = _isDestinationChange(lat, lng, poiId);
    if (changed) _resetForNewDestination();
    destinationEpoch += 1;
    final releaseCarpark = _releaseNeededFor;
    if (releaseCarpark != null) {
      // 即時釋放（唔理新目的地幾遠）；同一時間只留最新一筆（epoch 最大）。
      pendingLockRelease = PendingLockRelease(carparkId: releaseCarpark, lockRevision: _releaseRevision, newEpoch: destinationEpoch);
      if (_releaseLegacy) {
        pendingLockRelease!
          ..state = LockReleaseState.unsupported
          ..lastError = 'LEGACY_BACKEND_NO_LOCK_REVISION';
      } else {
        unawaited(_flushDestinationRelease());
      }
    }
    committedCarparkId = null;
    committedCarparkName = null;
    confirmNote = null;
    tracker.setDestination(lat, lng);
    final wasJitTriggered = tsm.jitTriggered;
    tsm.onDestinationSet(
      distanceToDestinationM: tracker.distanceToDestinationM(),
      destinationName: name,
      destLat: lat,
      destLng: lng,
      destinationPoiId: poiId,
    );
    if (tsm.fgsRunning && !shutdownInProgress && (changed || navTarget == null)) {
      if (lat != null && lng != null && name != onSiteDestinationName) {
        unawaited(_routeTo(NavTargetKind.destination, name, lat, lng));
      } else if (navTarget != null) {
        // 現場即泊（距離 0，唔導航）或者未有座標：唔留舊路線
        unawaited(_navStop());
      }
    }
    _rearmDistanceFromLastFix();
    _publishJitEdge(wasJitTriggered);
    _emit();
  }

  Future<void> submitDestinationText(String raw) async {
    final text = raw.trim();
    if (text.isEmpty) {
      tsm.onPlaceClarificationNeeded();
      _emit();
      return;
    }
    final resolved = resolver?.resolve(text);
    if (resolved is Hit) {
      lockDestinationAt(text, resolved.poi.lat, resolved.poi.lng, poiId: resolved.poi.id);
      await _jitRecommend;
      return;
    }
    tsm.onPlaceClarificationNeeded();
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

  /// r9：DEMO 重播時 false，裝置 GPS 嘅 fix 唔入（唔同固定行程爭）。正常模式永遠 true。
  bool deviceFixesEnabled = true;

  /// 原生 GPS fix（channel）入口；DEMO 重播時丟棄。
  void onDeviceFix(LocFix fix) {
    if (!deviceFixesEnabled) return;
    onFix(fix);
  }

  void onFix(LocFix fix) {
    if (!tsm.fgsRunning || shutdownInProgress) return;
    _lastFix = fix;
    tracker.onFix(fix);
    unawaited(navEngine.syncUserLocation(fix.lat, fix.lng));
    _tryLockOnSite(fix);
  }

  void onTick(int nowMs) {
    if (!tsm.fgsRunning || shutdownInProgress) return;
    tracker.onTick(nowMs);
  }

  Future<void> requestRecommendation() {
    if (tsm.state == TripState.greyBreathing) return retryFromGrey();
    return _recommend(QueryTriggerMode.driverActive, driverAsked: true);
  }

  Future<void> retryFromGrey() async {
    if (_greyRetryInFlight || shutdownInProgress) return;
    _greyRetryInFlight = true;
    try {
      await _recommend(QueryTriggerMode.driverActive, driverAsked: true);
    } finally {
      _greyRetryInFlight = false;
    }
  }

  /// t1276（r7）：最近一次確認操作嘅人話結果（畫面顯示；唔含 session）。
  String? confirmNote;

  void _confirmLog(String carparkId, String outcome) {
    debugPrint('HKP_NAV confirm carpark=$carparkId outcome=$outcome');
  }

  int get _switchConsentWindowMs =>
      ((NativeConfig.LISTEN_OPENING_WAIT_S + NativeConfig.LISTEN_MAX_UTTERANCE_S + NativeConfig.STT_RESULT_TIMEOUT_S) * 1000).round();

  bool _switchNeedsConsent(RecommendationCard card) =>
      !_countedCarparkConfirms.contains(card.carparkId) && _countedCarparkConfirms.length >= 2;

  bool _isDemonstrationCard(RecommendationCard card) {
    final id = card.carparkId.trim();
    if (id.startsWith('DEMO_')) return true;
    if (kDemoCarparkCoords.containsKey(id)) return true;
    if (card.feeSummary.trim() == 'DEMO') return true;
    final name = card.name;
    return name.contains('（DEMO）') || name.contains('(DEMO)');
  }

  RecommendationCard _forSupply(RecommendationCard card) {
    if (!isolateDemonstrationSupply || !_isDemonstrationCard(card)) return card;
    return card.copyWith(commitAllowed: false, demonstrationSupply: true);
  }

  void _clearPendingSwitch() {
    _switchConsentToken += 1;
    pendingSwitchCarparkId = null;
    pendingSwitchName = null;
    _pendingSwitchIndex = null;
  }

  void _armSwitchConsent(RecommendationCard card, int index, String label) {
    if (pendingSwitchCarparkId == card.carparkId) {
      confirmNote = '未確認「$label」，保持原路線';
      _confirmLog(card.carparkId, 'awaiting_consent');
      _emit();
      return;
    }
    _clearPendingSwitch();
    pendingSwitchCarparkId = card.carparkId;
    pendingSwitchName = label;
    _pendingSwitchIndex = index;
    cardsOpen = true;
    confirmNote = '未確認「$label」，保持原路線';
    _confirmLog(card.carparkId, 'awaiting_consent');
    final ask = ZhHkTemplates.confirmSwitchAsk(label);
    final cue = tsm.voiceReplyAvailable
        ? ZhHkTemplates.withVoiceReplyPrompt(ask, tsm.tripVoiceMode, tsm.isMicFgsActive, canListen: true)
        : ask;
    _speak(cue, driverAsked: true);
    _emit();
    final token = _switchConsentToken;
    final session = tsm.sessionId;
    final epoch = destinationEpoch;
    final id = card.carparkId;
    unawaited(() async {
      await delay(_switchConsentWindowMs);
      if (token != _switchConsentToken || _disposed) return;
      if (!_stillCurrent(session, epoch) || pendingSwitchCarparkId != id) return;
      _clearPendingSwitch();
      confirmNote = '逾時未確認「$label」，保持原路線';
      _confirmLog(id, 'switch_timeout');
      _emit();
    }());
  }

  /// 畫面「確認」或注入／語音「好」。只執行一次；進行中再嚟會忽略。
  Future<void> acceptPendingSwitch() async {
    final index = _pendingSwitchIndex;
    if (_switchAcceptStarted || index == null || pendingSwitchCarparkId == null) return;
    _switchAcceptStarted = true;
    _switchConsentToken += 1;
    try {
      await confirmCandidate(index, explicitConsent: true);
    } finally {
      _switchAcceptStarted = false;
    }
  }

  /// 畫面「取消」、語音「唔好」，或逾時。唔轉線。
  void rejectPendingSwitch() {
    final id = pendingSwitchCarparkId;
    if (id == null || _commitInFlight) return;
    final name = pendingSwitchName ?? id;
    _clearPendingSwitch();
    confirmNote = '已取消「$name」，保持原路線';
    _confirmLog(id, 'switch_rejected');
    _emit();
  }

  Future<void> confirmCandidate(int index, {bool explicitConsent = false}) async {
    if (index < 0 || index >= cards.length) return;
    final card = cards[index];
    final label0 = card.name.trim().isEmpty ? card.carparkId : card.name;
    if (_commitInFlight) {
      // 一次只處理一個確認；唔靜默。
      confirmNote = '確認緊「${_commitInFlightLabel ?? label0}」，請等等';
      _confirmLog(card.carparkId, 'busy');
      _emit();
      return;
    }
    if (tsm.state == TripState.navToGate && card.carparkId == committedCarparkId) {
      cardsOpen = false;
      confirmNote = '已經確認咗「$label0」';
      _confirmLog(card.carparkId, 'already_confirmed');
      _emit();
      return;
    }
    final consentForThis = explicitConsent && pendingSwitchCarparkId == card.carparkId;
    if (_switchNeedsConsent(card) && !consentForThis) {
      _armSwitchConsent(card, index, label0);
      return;
    }
    if (consentForThis) _clearPendingSwitch();
    // navToGate 下揀另一間：照送 commit（伺服器喺同一 session 換車場、釋放舊位）；
    // 成功前保留現有車場／路線／鎖（heldLock 只喺成功後換）。
    _commitInFlight = true;
    _commitInFlightLabel = label0;
    confirmNote = '確認緊「$label0」…';
    _emit();
    if (!card.commitAllowed) {
      // 參考卡／未確認：App 自己唔送 commit（伺服器亦會拒），唔靠 409。
      _commitInFlight = false;
      _commitInFlightLabel = null;
      confirmNote = '「$label0」唔可以預留（只供參考）';
      _confirmLog(card.carparkId, 'not_committable');
      _speak(ZhHkTemplates.REFERENCE_NOT_COMMITTABLE, driverAsked: true);
      _emit();
      return;
    }
    final capturedSession = tsm.sessionId;
    final capturedEpoch = destinationEpoch;
    _commitInFlightCarpark = card.carparkId;
    try {
      final outcome = await api.commitSession(
        capturedSession ?? '',
        card.carparkId,
        destinationEpoch: capturedEpoch,
        idempotencyKey: _idempotencyKey(capturedEpoch, card.carparkId),
      );
      if (_commitInFlightCarpark == card.carparkId) _commitInFlightCarpark = null;
      // 遲到結果（改咗目的地／完程）：唔導航、唔記鎖；伺服器端已由 DESTINATION_CHANGED（epoch）處理。
      if (!_stillCurrent(capturedSession, capturedEpoch)) {
        // 用戶停程時 commit 仲喺途中：伺服器其後先成功鎖位 → 即刻用回應嘅 revision 補送 USER_ABORTED，
        // 唔留孤兒鎖等逾時（改目的地情況已由 DESTINATION_CHANGED／STALE_EPOCH 處理）。
        final revision = outcome.bodyFields['lock_revision'];
        _confirmLog(card.carparkId, 'stale_ignored');
        if (outcome.succeeded && capturedSession != null && capturedSession == _abortedSession && network.isOnline()) {
          unawaited(api.releaseSession(capturedSession, card.carparkId,
              reason: ApiClient.RELEASE_USER_ABORTED, lockRevision: revision is num ? revision.toInt() : null));
        }
        return;
      }
      _applyNetworkOutcome(outcome);
      final conflictStatus = outcome.bodyFields['status'] as String?;
      final issue = outcome.serverIssue;
      if (issue != null) {
        lastCommitConflict = issue.code;
        serverIssue = issue;
        // 呢個車場留唔到（容量未知／帳未就緒）：同 LOCK_FULL 一樣喺卡上剔走，等用戶自己揀下一張；唔自動 commit 第二個。
        if (issue.kind == ServerIssueKind.capacityUnavailable) fullCarparkIds.add(card.carparkId);
        confirmNote = _failNote(label0);
        _confirmLog(card.carparkId, 'server_issue_${issue.code}');
        _speak(issue.spoken, driverAsked: true);
        _emit();
        return;
      }
      if (outcome.httpStatus == 409 && conflictStatus != null && conflictStatus != 'LOCK_FULL') {
        lastCommitConflict = conflictStatus;
        confirmNote = _failNote(label0);
        _confirmLog(card.carparkId, 'conflict_$conflictStatus');
        _speak(ZhHkTemplates.NETWORK_FAILED, driverAsked: true);
        _emit();
        return;
      }
      if (outcome.httpStatus == 409) {
        fullCarparkIds.add(card.carparkId);
        confirmNote = '「$label0」已滿，${_failSuffix()}';
        _confirmLog(card.carparkId, 'full');
        _speak(outcome.actionSpokenCue, driverAsked: true);
        _emit();
        return;
      }
      if (outcome.succeeded) {
        serverIssue = null;
        committedCarparkId = card.carparkId;
        committedCarparkName = card.name;
        final revision = outcome.bodyFields['lock_revision'];
        heldLock = HeldLock(carparkId: card.carparkId, lockRevision: revision is num ? revision.toInt() : null, epoch: capturedEpoch);
        tsm.onCommitSucceeded();
        final label = card.name.trim().isEmpty ? card.carparkId : card.name;
        final gate = GateCoordResolver.resolve(
          card,
          trie: resolver?.trie,
          fallbackLat: tsm.destLat,
          fallbackLng: tsm.destLng,
        );
        developer.log('gate coord layer=${gate.layer} id=${card.carparkId}', name: 'TripController');
        confirmNote = '已確認「$label」，準備導航…';
        _confirmLog(card.carparkId, 'committed');
        _countedCarparkConfirms.add(card.carparkId);
        cardsOpen = false;
        await _speakConfirm('已確認$label，正在計算路線');
        _emit();
        final guided = await _routeTo(NavTargetKind.carpark, label, gate.lat, gate.lng);
        if (!_stillCurrent(capturedSession, capturedEpoch)) return;
        confirmNote = guided ? '已確認「$label」，導航緊去停車場' : '已確認「$label」，但路線計算失敗（試咗外部導航）';
        if (!guided) {
          developer.log('startGuidanceTo failed; fallback launchGateNavigation', name: 'TripController');
          platform.launchGateNavigation(label, lat: gate.lat, lng: gate.lng);
        }
        _emit();
        return;
      }
      confirmNote = _failNote(label0);
      _confirmLog(card.carparkId, 'failed_http_${outcome.httpStatus}');
      _speak(ZhHkTemplates.NETWORK_FAILED, driverAsked: true);
      _emit();
    } finally {
      _commitInFlight = false;
      _commitInFlightLabel = null;
      if (_commitInFlightCarpark == card.carparkId) _commitInFlightCarpark = null;
    }
  }

  String? _commitInFlightLabel;

  String _failSuffix() => committedCarparkId == null
      ? '保持導航去目的地'
      : '保持去「${committedCarparkName ?? committedCarparkId}」';

  String _failNote(String label) => '確認「$label」唔成功，${_failSuffix()}';

  Future<void> abortTripByUser() async {
    if (_disposed || shutdownInProgress) return;
    if (tsm.sessionId == null && !tsm.fgsRunning && !tsm.fgsStartAccepted) return;
    shutdownInProgress = true;
    _lifecycleToken += 1;
    final session = tsm.sessionId;
    _abortedSession = session;
    final lock = heldLock;
    final pending = pendingLockRelease;
    if (session != null && (lock != null || pending != null)) {
      unawaited(_terminalAbortRelease(session, lock, pending));
    }
    tsm.clearSession();
    await _teardownSession('USER_ABORT');
  }

  /// 結束行程：先補送未完成嘅 DESTINATION_CHANGED（刪舊 epoch 鎖），再送終止 USER_ABORTED（tombstone）。
  Future<void> _terminalAbortRelease(String session, HeldLock? lock, PendingLockRelease? pending) async {
    if (!network.isOnline()) return;
    if (pending != null && pending.state != LockReleaseState.unsupported) {
      await api.releaseSession(session, pending.carparkId,
          reason: ApiClient.RELEASE_DESTINATION_CHANGED, lockRevision: pending.lockRevision, newDestinationEpoch: pending.newEpoch);
    }
    if (lock != null) {
      await api.releaseSession(session, lock.carparkId, reason: ApiClient.RELEASE_USER_ABORTED, lockRevision: lock.lockRevision);
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
      // 事件身份喺建立嗰刻鎖低（之後換目的地／重開 App 都唔變）。
      store.upsertCandidate(
        session,
        carparkId,
        carparkName,
        nowMs(),
        lockRevision: heldLock?.lockRevision,
        destinationEpoch: destinationEpoch,
      );
      if (network.isOnline()) {
        unawaited(api.releaseSession(session, carparkId, isOnline: true, lockRevision: heldLock?.lockRevision));
      }
    }
    final fadeToken = _lifecycleToken;
    await delay(NativeConfig.TEARDOWN_FADE_MS);
    if (_disposed || fadeToken != _lifecycleToken) return within ? 'PARKED' : 'OUTSIDE';
    await _teardownSession(within ? 'PARKED_CONFIRMED' : 'ENGINE_SHUTDOWN');
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
    if (online) {
      await sync.flushPendingConfirms();
      await _flushDestinationRelease();
    }
  }

  void onPermissionRevoked(String permission) {
    if (permission == 'RECORD_AUDIO') {
      tsm.onRecordAudioRevoked(tsm.tripVoiceMode != TripVoiceMode.manualOnly);
      return;
    }
    tsm.onRequiredPermissionRevoked();
  }

  void onOemKilled() {
    final inTrip = tsm.sessionId != null || tsm.fgsRunning || tsm.fgsStartAccepted;
    if (!inTrip) return;
    tsm.onOemKilled();
    if (_disposed) return;
    unawaited(_teardownSession('ABANDONED_OEM_KILL'));
  }

  void onFgsStopped() {
    if (_disposed) return;
    _lifecycleToken += 1;
    shutdownInProgress = false;
    tsm.clearSession();
    tracker.reset();
    detector.reset();
    _emit();
  }

  void dispose() {
    if (_disposed) return;
    _disposed = true;
    _lifecycleToken += 1;
    if (!_changes.isClosed) _changes.close();
  }

  void _onSample(DistanceSample sample) {
    if (!tsm.fgsRunning || shutdownInProgress) return;
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

  void _rearmDistanceFromLastFix() {
    if (_lastFix == null || !tsm.fgsRunning || shutdownInProgress) return;
    if (tsm.state == TripState.navToGate || tsm.state == TripState.approachMuted || tsm.state == TripState.parkedConfirmed) return;
    final distance = tracker.distanceToDestinationM();
    if (distance == null) return;
    tsm.onDistanceToDestination(distance);
  }

  void _publishJitEdge(bool wasJitTriggered) {
    if (_disposed || shutdownInProgress || !tsm.fgsRunning) return;
    if (!wasJitTriggered && tsm.jitTriggered && tsm.recommend) {
      _jitRecommend = _recommend(QueryTriggerMode.backgroundPrefetch, driverAsked: false);
    }
  }

  Future<void> _recommend(QueryTriggerMode mode, {required bool driverAsked}) async {
    final capturedSession = tsm.sessionId;
    final capturedEpoch = destinationEpoch;
    final fix = _lastFix;
    if (fix == null && !_loggedMissingOrigin) {
      _loggedMissingOrigin = true;
      developer.log('recommend origin missing; using 0,0', name: 'TripController');
    }
    final outcome = await api.recommendAndRoute(
      RecommendRequest(
        sessionId: capturedSession ?? '',
        originLat: fix?.lat ?? 0,
        originLng: fix?.lng ?? 0,
        destLat: tsm.destLat ?? 0,
        destLng: tsm.destLng ?? 0,
        destName: tsm.destinationName ?? '',
        preference: preferenceCode,
        expandScarcity: false,
        destinationEpoch: capturedEpoch,
        sessionStartedAtSec: sessionStartedAtMs == null ? null : sessionStartedAtMs! ~/ 1000,
      ),
      mode,
    );
    if (!_stillCurrent(capturedSession, capturedEpoch)) return;
    if (fix == null && !_spokeMissingOrigin) {
      _spokeMissingOrigin = true;
      _speak(ZhHkTemplates.ON_SITE_LOCATION_MISSED, driverAsked: true);
    }
    _driverAsked = driverAsked;
    try {
      _applyNetworkOutcome(outcome);
      if (!_stillCurrent(capturedSession, capturedEpoch)) return;
      if (!outcome.succeeded) {
        if (outcome.shouldTurnGrey) vacancyFresh = false;
        final issue = outcome.serverIssue;
        if (issue != null) {
          // typed 合約回應：顯示要做乜；唔自動換目的地／開新 session。
          serverIssue = issue;
          if (mode == QueryTriggerMode.driverActive) _speak(issue.spoken, driverAsked: true);
          _emit();
          return;
        }
        if (mode == QueryTriggerMode.driverActive) {
          for (final cue in outcome.spokenCues) {
            _speak(cue, driverAsked: true);
          }
        }
        _emit();
        return;
      }
      serverIssue = null;
      final next = (outcome.response?.cards ?? const <RecommendationCard>[])
          .where((card) => !fullCarparkIds.contains(card.carparkId))
          .map(_forSupply)
          .toList();
      if (next.isNotEmpty) {
        cards
          ..clear()
          ..addAll(next);
      }
      if (pendingLockRelease?.state == LockReleaseState.failed) unawaited(_flushDestinationRelease());
      // 空位 pip 只由今次成功結果計；結果空就唔沿用舊數字當新鮮。
      vacancyFresh = next.isNotEmpty && VacancyPip.isFresh(next);
      if (tsm.fgsRunning) tsm.onJitSucceeded(next.isEmpty ? StatusPip.none : VacancyPip.forLead(next));
      if (mode == QueryTriggerMode.driverActive) {
        final lead = next.where((card) => card.isViable).firstOrNull;
        final topCue = outcome.response?.spokenCue;
        if (lead == null) {
          // 冇可做首選嘅卡：讀伺服器頂層提示（擴大範圍提問；唔自動擴大）；參考卡照出。
          if (next.isNotEmpty) cardsOpen = true;
          _speak(topCue ?? ZhHkTemplates.NO_MORE_OPTIONS, driverAsked: true,
              listenAfter: _voiceUsable && (outcome.response?.awaitVoiceReply ?? false));
        } else {
          // 原規格：全語音（有 STT）P 只口播首選，卡要「詳細啲」或車場卡掣；冇 STT／手動 → P 直接出卡。
          if (!_voiceUsable) {
            cardsOpen = true; // 冇 STT／手動／咪被拒：直接出卡（手動後備）
          }
          // 全語音：等原生 TTS 講完（onSpeechDone）先 one-shot 開咪；冇固定延遲。
          if (!next.any((card) => card.commitAllowed)) {
            // 全部都係未確認／參考：先講清楚冇得預留，再讀首張（伺服器文字亦講明唔可以預留）。
            _speak(topCue ?? ZhHkTemplates.NO_RESERVABLE_OPTION, driverAsked: true);
          }
          _speak(lead.spokenCue, driverAsked: true, listenAfter: _voiceUsable);
        }
      }
      _emit();
    } finally {
      _driverAsked = false;
    }
  }

  /// 只處理網絡健康（灰／恢復）；空位 pip 由 _recommend 用真結果設定。
  /// 舊版喺度一律 onJitSucceeded(StatusPip.none)，令恢復後 pip 永遠 none（commit 成功亦會洗走 pip）。
  void _applyNetworkOutcome(CallOutcome outcome) {
    if (outcome.shouldTurnGrey) {
      tsm.onNetworkFailure();
      vacancyFresh = false;
    }
    if (outcome.shouldTurnReady && tsm.networkDegraded) tsm.onRequestSuccess();
  }

  bool _stillCurrent(String? capturedSession, int capturedEpoch) {
    if (_disposed || shutdownInProgress) return false;
    return capturedSession != null && capturedSession == tsm.sessionId && capturedEpoch == destinationEpoch;
  }

  void _speakAlert(bool mutedBefore) {
    if (mutedBefore && !_driverAsked) return;
    _speak(tsm.lastSpokenCue, driverAsked: _driverAsked || tsm.state == TripState.alertBreathing);
  }

  void _speak(String? text, {required bool driverAsked, bool listenAfter = false}) {
    if (text == null || text.isEmpty) return;
    if (tsm.tripVoiceMode == TripVoiceMode.manualOnly) return;
    if (tsm.state == TripState.approachMuted && !driverAsked) return;
    // 任何新一句都取代未完成嘅「講完開咪」：唔會喺下一句播緊時開咪。
    _pendingListen = null;
    if (!listenAfter) {
      platform.speak(text);
      return;
    }
    final id = 'u${++_utteranceSerial}';
    _pendingListen = (id: id, session: tsm.sessionId, epoch: destinationEpoch);
    platform.speak(text, utteranceId: id);
  }

  int _utteranceSerial = 0;
  ({String id, String? session, int epoch})? _pendingListen;
  String? _guidanceResumeUtterance;

  /// 確認口播：先停 SDK 導航聲，講完（或講唔到）再恢復。手動模式唔口播、亦唔暫停 SDK。
  Future<void> _speakConfirm(String text) async {
    if (tsm.tripVoiceMode == TripVoiceMode.manualOnly || text.isEmpty) return;
    await navEngine.suspendTurnByTurnAudio();
    final id = 'u${++_utteranceSerial}';
    _guidanceResumeUtterance = id;
    _pendingListen = null;
    platform.speak(text, utteranceId: id);
  }

  /// 有排緊「講完先聽」（測試／診斷用）。
  bool get listenPendingOnSpeech => _pendingListen != null;

  /// 原生 TTS 真完結（UtteranceProgressListener）。只有同一句、同一 session／目的地、仲係全語音行程中先開咪；
  /// 講唔到（錯誤／被停／排唔到）→ 唔開咪，改出卡（手動後備）。
  void onSpeechDone(String utteranceId, {required bool played}) {
    if (_guidanceResumeUtterance == utteranceId) {
      _guidanceResumeUtterance = null;
      if (!_disposed) unawaited(navEngine.resumeTurnByTurnAudio());
    }
    final pending = _pendingListen;
    if (pending == null || pending.id != utteranceId) return;
    _pendingListen = null;
    if (_disposed || !tsm.fgsRunning || shutdownInProgress) return;
    if (!_stillCurrent(pending.session, pending.epoch)) return;
    if (!played) {
      if (cards.isNotEmpty && !cardsOpen) {
        cardsOpen = true;
        _emit();
      }
      return;
    }
    if (_voiceUsable) platform.startListening();
  }

  void _handleTripEnded(String reason) {
    if (_disposed) return;
    if (reason == 'ENGINE_SHUTDOWN' || reason == 'PARKED_CONFIRMED') {
      platform.interruptSpeech();
      return;
    }
    unawaited(_teardownSession(reason));
  }

  Future<void> _teardownSession(String reason) async {
    await _navStop();
    if (_disposed) return;
    _finishTeardown(reason);
  }

  void _finishTeardown(String reason) {
    _guidanceResumeUtterance = null;
    unawaited(navEngine.resumeTurnByTurnAudio());
    platform.interruptSpeech();
    unawaited(platform.stopForeground(reason));
    tracker.reset();
    detector.reset();
    committedCarparkId = null;
    committedCarparkName = null;
    confirmNote = null;
    _countedCarparkConfirms.clear();
    _clearPendingSwitch();
    fullCarparkIds.clear();
    cardsOpen = false;
    vacancyFresh = false;
    heldLock = null;
    pendingLockRelease = null;
    _commitInFlightCarpark = null;
    platform.stopListening();
    _commitInFlight = false;
    _greyRetryInFlight = false;
    shutdownInProgress = false;
    _emit();
  }

  String _parkedCarparkId() => committedCarparkId ?? cards.firstOrNull?.carparkId ?? tsm.destinationName ?? '';

  String _parkedCarparkName() => committedCarparkName ?? cards.firstOrNull?.name ?? tsm.destinationName ?? '';

  /// 本次 session 開始時間（送 `session_started_at`）；每次 start 重設。
  int? sessionStartedAtMs;

  /// 最近一次 typed 伺服器問題（狀態列顯示要做乜）；成功／新行程／改目的地清走。
  ServerIssue? serverIssue;

  void _resetTripFields() {
    serverIssue = null;
    _pendingListen = null;
    voiceInputBlockedReason = null;
    lastVoiceError = null;
    cards.clear();
    heldLock = null;
    pendingLockRelease = null;
    _commitInFlightCarpark = null;
    lastCommitConflict = null;
    cardsOpen = false;
    vacancyFresh = false;
    fullCarparkIds.clear();
    committedCarparkId = null;
    committedCarparkName = null;
    confirmNote = null;
    _countedCarparkConfirms.clear();
    _clearPendingSwitch();
    shutdownInProgress = false;
    _lastFix = null;
    _loggedMissingOrigin = false;
    _spokeMissingOrigin = false;
    tracker.reset();
    detector.reset();
  }

  void _emit() {
    if (_disposed || _changes.isClosed) return;
    _changes.add(snapshot);
  }
}

enum NavTargetKind { destination, carpark }

enum NavTargetState { starting, guiding, failed }

/// 而家導航緊去邊（可以同用戶目的地唔同：commit 之後係車場）。state 只喺 engine 真係回覆後先變。
class NavTarget {
  const NavTarget({required this.kind, required this.title, required this.lat, required this.lng, required this.state});

  final NavTargetKind kind;
  final String title;
  final double lat;
  final double lng;
  final NavTargetState state;

  NavTarget withState(NavTargetState next) => NavTarget(kind: kind, title: title, lat: lat, lng: lng, state: next);
}
