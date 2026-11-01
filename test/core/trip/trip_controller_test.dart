import 'dart:async';
import 'dart:convert';

import 'package:copilot/core/constants/native_config.dart';
import 'package:copilot/core/net/api_client.dart';
import 'package:copilot/core/net/api_transport.dart';
import 'package:copilot/core/net/http_result.dart';
import 'package:copilot/core/net/recommend_response.dart';
import 'package:copilot/core/net/recommendation_card.dart';
import 'package:copilot/core/poi/local_poi_trie.dart';
import 'package:copilot/core/poi/poi_name_resolver.dart';
import 'package:copilot/core/poi/stt_typo.dart';
import 'package:copilot/core/trip/distance_tracker.dart';
import 'package:copilot/core/trip/prefs_port.dart';
import 'package:copilot/core/trip/sync_api.dart';
import 'package:copilot/core/trip/today_trip_store.dart';
import 'package:copilot/core/trip/today_trip_sync.dart';
import 'package:copilot/core/trip/trip_controller.dart';
import 'package:copilot/core/trip/trip_ports.dart';
import 'package:copilot/core/trip/trip_types.dart';
import 'package:copilot/core/voice/voice_capability.dart';
import 'package:copilot/core/voice/zh_hk_templates.dart';
import 'package:flutter_test/flutter_test.dart';

const destLat = 22.3193;
const destLng = 114.1694;
const delta1000 = 0.008983;

class HoldTransport implements ApiTransport {
  final calls = <String>[];
  final bodies = <String>[];
  final waiting = <Completer<HttpResult>>[];
  HttpResult? next;

  @override
  Future<HttpResult> post(String path, String bodyJson) {
    calls.add(path);
    bodies.add(bodyJson);
    if (next != null) return Future.value(next!);
    final gate = Completer<HttpResult>();
    waiting.add(gate);
    return gate.future;
  }
}

class FakePlatform implements TripPlatform {
  bool allow = true;
  int starts = 0;
  int interrupts = 0;
  final spoken = <String>[];
  final tiers = <SamplingTier>[];
  final nav = <String>[];
  final stops = <String>[];

  @override
  Future<bool> startForeground(StartupEntry entry) async {
    starts += 1;
    return allow;
  }

  @override
  Future<void> stopForeground(String reason) async => stops.add(reason);
  @override
  void applySamplingTier(SamplingTier tier) => tiers.add(tier);
  @override
  void setNotificationText(String text) {}
  @override
  void speak(String text) => spoken.add(text);
  @override
  void interruptSpeech() => interrupts += 1;
  @override
  void launchGateNavigation(String label) => nav.add(label);
}

const CapabilitySnapshot speakingMode = CapabilitySnapshot(
  stt: CapabilityStatus.available,
  tts: CapabilityStatus.available,
  mode: TripVoiceMode.announceManual,
  voiceInputEnabled: false,
);

class ScriptDelay {
  final pending = <Completer<void>>[];
  Future<void> call(int ms) {
    final gate = Completer<void>();
    pending.add(gate);
    return gate.future;
  }

  void finishNext() => pending.removeAt(0).complete();
}

class _Online implements NetworkStatusProvider {
  @override
  bool isOnline() => true;
}

RecommendationCard card(String id, [String name = '場']) => RecommendationCard(
      carparkId: id,
      name: name,
      type: 'M',
      vacancies: 1,
      distanceMeters: 10,
      feeSummary: '',
      spokenCue: '去$id',
      isStale: false,
    );

HttpResult ok(List<RecommendationCard> cards) => HttpResult(
      statusCode: 200,
      recommendResponse: RecommendResponse(status: 'SUCCESS', cards: cards),
    );

Future<TripController> boot(
  HoldTransport transport,
  FakePlatform platform,
  ScriptDelay delay, {
  PoiNameResolver? resolver,
  bool online = true,
  TripNetwork? network,
  String Function()? sessionIdFactory,
  bool startTrip = true,
}) async {
  final net = network ?? TripNetwork(online);
  final api = ApiClient(transport, sleeper: (_) async {});
  final store = TodayTripStore(MemoryPrefsPort(), hktDateOf: (_) => '2026-10-26', nowMs: () => 1_000);
  final sync = TodayTripSync(store, _NoSync(), net, nowMs: () => 1_000);
  final controller = TripController(
    platform: platform,
    api: api,
    store: store,
    sync: sync,
    nowMs: () => 5_000,
    delay: delay.call,
    network: net,
    resolver: resolver,
    sessionIdFactory: sessionIdFactory ?? () => 'sess_test',
  );
  if (startTrip) await controller.start(StartupEntry.a2);
  return controller;
}

class _NoSync implements SyncApi {
  @override
  Future<CallOutcome> syncDriverConfirmed(String sessionId, int timestampSec) async => const CallOutcome(
        succeeded: false,
        httpStatus: 500,
        spokenCues: [],
        attempts: 1,
        waitIntervalsMs: [],
        shouldTurnGrey: true,
        shouldTurnReady: false,
      );
  @override
  Future<CallOutcome> submitTripReport({
    required String sessionId,
    required String carparkId,
    required String ratingTag,
    required String perkStatusTag,
    required String perkNotes,
    required int tripTimestampSec,
  }) async => const CallOutcome(succeeded: false, httpStatus: 500, spokenCues: [], attempts: 1, waitIntervalsMs: [], shouldTurnGrey: false, shouldTurnReady: false);
}

class _Offline implements NetworkStatusProvider {
  @override
  bool isOnline() => false;
}

void main() {
  test('C1 rejected start does not open fgs', () async {
    final platform = FakePlatform();
    final controller = TripController(
      platform: platform,
      api: ApiClient(HoldTransport(), sleeper: (_) async {}),
      store: TodayTripStore(MemoryPrefsPort(), hktDateOf: (_) => '2026-10-26'),
      sync: TodayTripSync(TodayTripStore(MemoryPrefsPort()), _NoSync(), _Online()),
      nowMs: () => 1,
      delay: (_) async {},
    );
    final outcome = await controller.start(StartupEntry.b1);
    expect(outcome.accepted, isFalse);
    expect(platform.starts, 0);
    expect(controller.sessionId, isNull);
  });

  test('C2 and C3 fgs handshake', () async {
    final okPlatform = FakePlatform();
    final okController = await boot(HoldTransport(), okPlatform, ScriptDelay());
    expect(okController.state, TripState.cruising);
    expect(okPlatform.tiers, contains(SamplingTier.far));
    final bad = FakePlatform()..allow = false;
    final failed = TripController(
      platform: bad,
      api: ApiClient(HoldTransport(), sleeper: (_) async {}),
      store: TodayTripStore(MemoryPrefsPort(), hktDateOf: (_) => '2026-10-26'),
      sync: TodayTripSync(TodayTripStore(MemoryPrefsPort()), _NoSync(), _Online()),
      nowMs: () => 1,
      delay: (_) async {},
    );
    await failed.start(StartupEntry.a2);
    expect(failed.state, TripState.preFgs);
    expect(bad.stops, ['BOOTSTRAP_TIMEOUT']);
  });

  test('C4 jit recommend fires once', () async {
    final transport = HoldTransport()..next = ok([card('CP')]);
    final controller = await boot(transport, FakePlatform(), ScriptDelay());
    controller.lockDestinationAt('尖沙咀', destLat, destLng);
    controller.onFix(LocFix(lat: destLat + delta1000 * 0.9, lng: destLng, accuracyM: 10, speedKmh: 20, timestampMs: 1000));
    controller.onFix(LocFix(lat: destLat + delta1000 * 0.8, lng: destLng, accuracyM: 10, speedKmh: 20, timestampMs: 2000));
    expect(transport.calls.where((path) => path == ApiClient.RECOMMEND), hasLength(1));
    final body = jsonDecode(transport.bodies.single) as Map<String, Object?>;
    expect(body['session_id'], 'sess_test');
    expect(body['origin_lat'], 0.0);
    expect(body['origin_lng'], 0.0);
    expect(body['dest_lat'], destLat);
    expect(body['dest_lng'], destLng);
    expect(body['dest_name'], '尖沙咀');
    expect(body['preference'], 'SMART_HYBRID');
    expect(body['expand_scarcity'], false);
  });

  test('C5 background failure is silent then success leaves grey', () async {
    final transport = HoldTransport()..next = const HttpResult(statusCode: 0, networkError: true, timedOut: true);
    final platform = FakePlatform();
    final controller = await boot(transport, platform, ScriptDelay());
    controller.tsm.applyVoiceCapability(speakingMode, true);
    controller.lockDestinationAt('尖沙咀', destLat, destLng);
    controller.onFix(LocFix(lat: destLat + delta1000 * 0.4, lng: destLng, accuracyM: 10, speedKmh: 10, timestampMs: 1000));
    await Future<void>.delayed(const Duration(milliseconds: 1));
    expect(controller.state, TripState.greyBreathing);
    expect(platform.spoken, isEmpty);
    transport.next = ok(const []);
    await controller.requestRecommendation();
    expect(controller.state, isNot(TripState.greyBreathing));
  });

  test('C6 commit success navigates and stops feeding distance', () async {
    final transport = HoldTransport()..next = ok([card('CP', '朗豪坊')]);
    final platform = FakePlatform();
    final controller = await boot(transport, platform, ScriptDelay());
    controller.lockDestinationAt('朗豪坊', destLat, destLng);
    controller.onFix(LocFix(lat: destLat + delta1000 * 0.4, lng: destLng, accuracyM: 10, speedKmh: 10, timestampMs: 1000));
    await Future<void>.delayed(Duration.zero);
    await controller.confirmCandidate(0);
    expect(controller.state, TripState.navToGate);
    expect(platform.nav, ['朗豪坊']);
    final distanceBefore = controller.tracker.lastDistanceM;
    final commits = transport.calls.where((path) => path == ApiClient.COMMIT).length;
    for (var i = 0; i < 4; i++) {
      controller.onFix(LocFix(lat: destLat + delta1000 * 0.07, lng: destLng, accuracyM: 10, speedKmh: 5, timestampMs: 3000 + i * 1000));
    }
    await controller.confirmCandidate(0);
    expect(transport.calls.where((path) => path == ApiClient.COMMIT).length, commits);
    expect(controller.tracker.lastDistanceM, distanceBefore);
    expect(controller.state, TripState.navToGate);
  });

  test('C7 full carpark then no more options', () async {
    final transport = HoldTransport()..next = ok([card('CP')]);
    final platform = FakePlatform();
    final controller = await boot(transport, platform, ScriptDelay());
    controller.lockDestinationAt('場', destLat, destLng);
    controller.onFix(LocFix(lat: destLat + delta1000 * 0.4, lng: destLng, accuracyM: 10, speedKmh: 10, timestampMs: 1000));
    await Future<void>.delayed(const Duration(milliseconds: 1));
    controller.tsm.applyVoiceCapability(
      const CapabilitySnapshot(stt: CapabilityStatus.unsupported, tts: CapabilityStatus.available, mode: TripVoiceMode.announceManual, voiceInputEnabled: false),
      true,
    );
    transport.next = const HttpResult(statusCode: 409, spokenCue: '滿');
    await controller.confirmCandidate(0);
    expect(platform.spoken, contains('滿'));
    transport.next = ok([card('CP')]);
    await controller.requestRecommendation();
    expect(platform.spoken, contains(ZhHkTemplates.NO_MORE_OPTIONS));
  });

  test('C8 commit in flight posts once', () async {
    final transport = HoldTransport()..next = ok([card('CP')]);
    final controller = await boot(transport, FakePlatform(), ScriptDelay());
    controller.lockDestinationAt('場', destLat, destLng);
    controller.onFix(LocFix(lat: destLat + delta1000 * 0.4, lng: destLng, accuracyM: 10, speedKmh: 10, timestampMs: 1000));
    await Future<void>.delayed(Duration.zero);
    transport.next = null;
    final first = controller.confirmCandidate(0);
    final second = controller.confirmCandidate(0);
    await Future<void>.delayed(Duration.zero);
    expect(transport.calls.where((path) => path == ApiClient.COMMIT), hasLength(1));
    transport.waiting.last.complete(const HttpResult(statusCode: 200));
    await first;
    await second;
  });

  test('C9 destination change drops in-flight cards', () async {
    final transport = HoldTransport();
    final platform = FakePlatform();
    final controller = await boot(transport, platform, ScriptDelay());
    controller.tsm.applyVoiceCapability(speakingMode, true);
    controller.lockDestinationAt('甲', destLat, destLng);
    final pending = controller.requestRecommendation();
    await Future<void>.delayed(Duration.zero);
    controller.lockDestinationAt('乙', destLat, destLng);
    transport.waiting.first.complete(ok([card('OLD')]));
    await pending;
    expect(controller.cards, isEmpty);
    expect(platform.spoken, isNot(contains('去OLD')));
    expect(platform.spoken, isEmpty);
  });

  test('C10 reconnect during debounce cancels', () async {
    final delay = ScriptDelay();
    final controller = await boot(HoldTransport(), FakePlatform(), delay);
    final pending = controller.onEngineSignal(EngineSignal.power, disconnected: true);
    await Future<void>.delayed(Duration.zero);
    await controller.onEngineSignal(EngineSignal.power, disconnected: false);
    delay.finishNext();
    expect(await pending, 'CANCELLED');
    expect(controller.store.list(1000), isEmpty);
  });

  test('C11 within range writes candidate and releases only online', () async {
    final delay = ScriptDelay();
    final transport = HoldTransport()..next = const HttpResult(statusCode: 200);
    final controller = await boot(transport, FakePlatform(), delay);
    controller.lockDestinationAt('場', destLat, destLng);
    controller.onFix(LocFix(lat: destLat + delta1000 * 0.1, lng: destLng, accuracyM: 10, speedKmh: 1, timestampMs: 1000));
    final pending = controller.onEngineSignal(EngineSignal.power, disconnected: true);
    await Future<void>.delayed(Duration.zero);
    delay.finishNext();
    await Future<void>.delayed(Duration.zero);
    expect(controller.store.find('sess_test'), isNotNull);
    expect(transport.calls, contains(ApiClient.RELEASE));
    delay.finishNext();
    await pending;
  });

  test('C12 moving ignition is ignored', () async {
    final delay = ScriptDelay();
    final controller = await boot(HoldTransport(), FakePlatform(), delay);
    controller.lockDestinationAt('場', destLat, destLng);
    controller.onFix(LocFix(lat: destLat + delta1000 * 0.1, lng: destLng, accuracyM: 10, speedKmh: 30, timestampMs: 1000));
    final pending = controller.onEngineSignal(EngineSignal.power, disconnected: true);
    await Future<void>.delayed(Duration.zero);
    delay.finishNext();
    expect(await pending, 'IGNORED_MOVING');
  });

  test('C13 outside range writes no candidate', () async {
    final delay = ScriptDelay();
    final controller = await boot(HoldTransport(), FakePlatform(), delay);
    controller.lockDestinationAt('場', destLat, destLng);
    controller.onFix(LocFix(lat: destLat + delta1000 * 2, lng: destLng, accuracyM: 10, speedKmh: 1, timestampMs: 1000));
    final pending = controller.onEngineSignal(EngineSignal.power, disconnected: true);
    await Future<void>.delayed(Duration.zero);
    delay.finishNext();
    await Future<void>.delayed(Duration.zero);
    expect(controller.store.list(1000), isEmpty);
    delay.finishNext();
    expect(await pending, 'OUTSIDE');
  });

  test('C14 two signals shut down once', () async {
    final delay = ScriptDelay();
    final transport = HoldTransport()..next = const HttpResult(statusCode: 200);
    final controller = await boot(transport, FakePlatform(), delay);
    controller.lockDestinationAt('場', destLat, destLng);
    controller.onFix(LocFix(lat: destLat + delta1000 * 0.1, lng: destLng, accuracyM: 10, speedKmh: 1, timestampMs: 1000));
    await Future<void>.delayed(const Duration(milliseconds: 1));
    final first = controller.onEngineSignal(EngineSignal.power, disconnected: true);
    final second = controller.onEngineSignal(EngineSignal.btCar, disconnected: true);
    expect(await second, 'IGNORED_EXTRA');
    delay.finishNext();
    await Future<void>.delayed(Duration.zero);
    delay.finishNext();
    await first;
    expect(controller.tracker.tier, SamplingTier.far);
    await controller.start(StartupEntry.a2);
    expect(controller.state, TripState.cruising);
  });

  test('C15 drops recommend that returns during fade', () async {
    final delay = ScriptDelay();
    final transport = HoldTransport();
    final platform = FakePlatform();
    final controller = await boot(transport, platform, delay);
    controller.tsm.applyVoiceCapability(speakingMode, true);
    controller.lockDestinationAt('場', destLat, destLng);
    final pendingRecommend = controller.requestRecommendation();
    await Future<void>.delayed(Duration.zero);
    final shutdown = controller.onEngineSignal(EngineSignal.power, disconnected: true);
    await Future<void>.delayed(Duration.zero);
    delay.finishNext();
    await Future<void>.delayed(Duration.zero);
    transport.waiting.first.complete(ok([card('LATE')]));
    await pendingRecommend;
    expect(controller.cards, isEmpty);
    expect(platform.spoken, isNot(contains('去LATE')));
    expect(controller.state, isNot(TripState.nearbyReady));
    delay.finishNext();
    await shutdown;
    expect(controller.state, TripState.ended);
  });

  test('C16 grey retry is single flight', () async {
    final transport = HoldTransport();
    final controller = await boot(transport, FakePlatform(), ScriptDelay());
    final first = controller.retryFromGrey();
    final second = controller.retryFromGrey();
    await Future<void>.delayed(Duration.zero);
    expect(transport.calls, hasLength(1));
    const failed = HttpResult(statusCode: 0, networkError: true);
    transport.next = failed;
    while (transport.waiting.isNotEmpty) {
      transport.waiting.removeAt(0).complete(failed);
      await Future<void>.delayed(Duration.zero);
    }
    await first;
    await second;
    final before = transport.calls.length;
    await controller.requestRecommendation();
    expect(transport.calls.length, greaterThan(before));
  });

  test('C17 share intake branches', () async {
    final trie = LocalPoiTrie.fromJsonString(
      '{"v":1,"attribution":"x","pois":[{"id":"ig:1","t":"M","zh":"朗豪坊","en":"","a":[],"d":"油尖旺","la":22.3,"lo":114.1}]}',
    );
    final resolver = PoiNameResolver(trie: trie, typo: SttTypoCorpus(const {}));
    final platform = FakePlatform();
    final controller = TripController(
      platform: platform,
      api: ApiClient(HoldTransport(), sleeper: (_) async {}),
      store: TodayTripStore(MemoryPrefsPort(), hktDateOf: (_) => '2026-10-26'),
      sync: TodayTripSync(TodayTripStore(MemoryPrefsPort()), _NoSync(), _Online()),
      nowMs: () => 1,
      delay: (_) async {},
      resolver: resolver,
      sessionIdFactory: () => 'sess_share',
    );
    await controller.onShareIntake(title: '朗豪坊', lat: 22.3, lng: 114.1, hasUrl: true);
    expect(platform.starts, 1);
    expect(controller.tsm.destLat, 22.3);
    expect(controller.tsm.destinationPoiId, 'ig:1');
    await controller.onShareIntake(title: '唔存在', lat: null, lng: null, hasUrl: false);
    expect(controller.tsm.destLat, isNull);
    expect(platform.starts, 1);
    await controller.onShareIntake(title: null, lat: null, lng: null, hasUrl: true);
    expect(controller.state, TripState.alertBreathing);
  });

  test('C18 sync failure and stale session do not move tsm', () async {
    final transport = HoldTransport()..next = const HttpResult(statusCode: 500, networkError: true);
    final store = TodayTripStore(MemoryPrefsPort(), hktDateOf: (_) => '2026-10-26', nowMs: () => 1000);
    final api = ApiClient(transport, sleeper: (_) async {});
    expect(api.onNetworkEffects, isNull);
    final sync = TodayTripSync(store, _RecordingFail(api), _Online(), nowMs: () => 1000);
    final controller = TripController(
      platform: FakePlatform(),
      api: api,
      store: store,
      sync: sync,
      nowMs: () => 1000,
      delay: (_) async {},
      sessionIdFactory: () => 'sess_net',
    );
    await controller.start(StartupEntry.a2);
    store.upsertCandidate('other', 'cp', '場', 1000);
    store.markConfirmed('other', syncPending: true, atMs: 1000);
    await sync.flushPendingConfirms();
    expect(controller.state, TripState.cruising);
    expect(controller.tsm.networkDegraded, isFalse);
    final held = HoldTransport();
    final staleApi = ApiClient(held, sleeper: (_) async {});
    controller.api;
    final other = TripController(
      platform: FakePlatform(),
      api: staleApi,
      store: store,
      sync: sync,
      nowMs: () => 1000,
      delay: (_) async {},
      sessionIdFactory: () => 'sess_old',
    );
    await other.start(StartupEntry.a2);
    final pending = other.requestRecommendation();
    await Future<void>.delayed(Duration.zero);
    other.onOemKilled();
    const failed = HttpResult(statusCode: 0, networkError: true);
    held.next = failed;
    while (held.waiting.isNotEmpty) {
      held.waiting.removeAt(0).complete(failed);
      await Future<void>.delayed(Duration.zero);
    }
    await pending;
    expect(other.tsm.networkDegraded, isFalse);
  });

  test('C11 offline shutdown skips release', () async {
    final delay = ScriptDelay();
    final transport = HoldTransport()..next = const HttpResult(statusCode: 200);
    final net = TripNetwork(false);
    final controller = await boot(transport, FakePlatform(), delay, network: net);
    controller.lockDestinationAt('場', destLat, destLng);
    controller.onFix(LocFix(lat: destLat + delta1000 * 0.1, lng: destLng, accuracyM: 10, speedKmh: 1, timestampMs: 1000));
    final pending = controller.onEngineSignal(EngineSignal.power, disconnected: true);
    await Future<void>.delayed(Duration.zero);
    delay.finishNext();
    await Future<void>.delayed(Duration.zero);
    expect(controller.store.find('sess_test'), isNotNull);
    expect(transport.calls, isNot(contains(ApiClient.RELEASE)));
    delay.finishNext();
    expect(await pending, 'PARKED');
  });

  test('C18b stale session outcome does not grey the new session', () async {
    var n = 0;
    final held = HoldTransport();
    final controller = await boot(held, FakePlatform(), ScriptDelay(), sessionIdFactory: () => 'sess_${n++}');
    final oldSession = controller.sessionId;
    final pending = controller.requestRecommendation();
    await Future<void>.delayed(Duration.zero);
    await controller.start(StartupEntry.a2);
    expect(controller.sessionId, isNot(oldSession));
    expect(controller.tsm.fgsRunning, isTrue);
    const failed = HttpResult(statusCode: 0, networkError: true);
    held.next = failed;
    while (held.waiting.isNotEmpty) {
      held.waiting.removeAt(0).complete(failed);
      await Future<void>.delayed(Duration.zero);
    }
    await pending;
    expect(controller.sessionId, isNot(oldSession));
    expect(controller.tsm.fgsRunning, isTrue);
    expect(controller.tsm.networkDegraded, isFalse);
    expect(controller.state, TripState.cruising);
  });

  test('speech gate keeps approach muted silent unless driver asked', () async {
    final transport = HoldTransport()..next = ok(const []);
    final platform = FakePlatform();
    final controller = await boot(transport, platform, ScriptDelay());
    controller.tsm.applyVoiceCapability(speakingMode, true);
    controller.lockDestinationAt('場', destLat, destLng);
    for (var i = 0; i < 4; i++) {
      controller.onFix(LocFix(lat: destLat + delta1000 * 0.07, lng: destLng, accuracyM: 10, speedKmh: 5, timestampMs: i * 1000));
    }
    await Future<void>.delayed(const Duration(milliseconds: 1));
    expect(controller.state, TripState.approachMuted);
    expect(controller.tracker.approachMuted, isTrue);
    expect(platform.spoken, isEmpty);
    transport.next = null;
    final pending = controller.requestRecommendation();
    await Future<void>.delayed(Duration.zero);
    controller.tsm.onPlaceClarificationNeeded();
    expect(platform.spoken, isEmpty);
    transport.waiting.first.complete(ok([card('ASK', '朗豪坊')]));
    await pending;
    expect(platform.spoken, contains('去ASK'));
  });

  test('F1 teardown interrupts and stops after fade and on permission or oem', () async {
    final delay = ScriptDelay();
    final platform = FakePlatform();
    final transport = HoldTransport()..next = const HttpResult(statusCode: 200);
    final controller = await boot(transport, platform, delay);
    controller.lockDestinationAt('場', destLat, destLng);
    controller.onFix(LocFix(lat: destLat + delta1000 * 0.1, lng: destLng, accuracyM: 10, speedKmh: 1, timestampMs: 1000));
    final pending = controller.onEngineSignal(EngineSignal.power, disconnected: true);
    await Future<void>.delayed(Duration.zero);
    delay.finishNext();
    await Future<void>.delayed(Duration.zero);
    expect(platform.interrupts, 1);
    expect(platform.stops, isEmpty);
    delay.finishNext();
    await pending;
    expect(platform.stops, ['PARKED_CONFIRMED']);
    expect(platform.interrupts, 2);

    final revoked = FakePlatform();
    final revokedController = await boot(HoldTransport(), revoked, ScriptDelay());
    revokedController.onPermissionRevoked('RECORD_AUDIO');
    expect(revoked.stops, isEmpty);
    revokedController.onPermissionRevoked('ACCESS_FINE_LOCATION');
    expect(revoked.stops, ['PERMISSION_REVOKED']);
    expect(revoked.interrupts, 1);
    expect(revokedController.sessionId, isNull);

    final killed = FakePlatform();
    final killedController = await boot(HoldTransport(), killed, ScriptDelay());
    killedController.onOemKilled();
    expect(killed.stops, ['ABANDONED_OEM_KILL']);
    expect(killed.interrupts, 1);
    expect(killedController.sessionId, isNull);
  });

  test('F2 failed fgs is not accepted and share is dropped', () async {
    final bad = FakePlatform()..allow = false;
    final failed = await boot(HoldTransport(), bad, ScriptDelay(), startTrip: false);
    final outcome = await failed.start(StartupEntry.a2);
    expect(outcome.accepted, isFalse);
    expect(failed.sessionId, isNull);
    expect(failed.tsm.fgsRunning, isFalse);
    expect(bad.stops, ['BOOTSTRAP_TIMEOUT']);

    final sharePlatform = FakePlatform()..allow = false;
    final share = await boot(HoldTransport(), sharePlatform, ScriptDelay(), startTrip: false);
    await share.onShareIntake(title: '朗豪坊', lat: 22.3, lng: 114.1, hasUrl: true);
    expect(share.shareDroppedReason, 'SHARE_DROPPED');
    expect(share.tsm.destinationName, isNull);
    expect(share.tsm.fgsRunning, isFalse);
  });

  test('F3 parked id falls back from card then destination name', () async {
    final delay = ScriptDelay();
    final withCard = HoldTransport()..next = ok([card('CP1', '朗豪坊')]);
    final controller = await boot(withCard, FakePlatform(), delay);
    controller.lockDestinationAt('尖沙咀', destLat, destLng);
    controller.onFix(LocFix(lat: destLat + delta1000 * 0.1, lng: destLng, accuracyM: 10, speedKmh: 1, timestampMs: 1000));
    await Future<void>.delayed(const Duration(milliseconds: 1));
    expect(controller.cards.single.carparkId, 'CP1');
    final pending = controller.onEngineSignal(EngineSignal.power, disconnected: true);
    await Future<void>.delayed(Duration.zero);
    delay.finishNext();
    await Future<void>.delayed(Duration.zero);
    final releaseBody = jsonDecode(withCard.bodies.last) as Map<String, Object?>;
    expect(releaseBody['target_carpark_id'], 'CP1');
    expect(controller.store.find('sess_test')?.carparkId, 'CP1');
    delay.finishNext();
    await pending;

    final delay2 = ScriptDelay();
    final named = HoldTransport()..next = const HttpResult(statusCode: 200);
    final byName = await boot(named, FakePlatform(), delay2);
    byName.lockDestinationAt('尖沙咀', destLat, destLng);
    byName.onFix(LocFix(lat: destLat + delta1000 * 0.1, lng: destLng, accuracyM: 10, speedKmh: 1, timestampMs: 1000));
    final pendingName = byName.onEngineSignal(EngineSignal.power, disconnected: true);
    await Future<void>.delayed(Duration.zero);
    delay2.finishNext();
    await Future<void>.delayed(Duration.zero);
    final nameBody = jsonDecode(named.bodies.last) as Map<String, Object?>;
    expect(nameBody['target_carpark_id'], '尖沙咀');
    delay2.finishNext();
    await pendingName;
  });

  test('F4 recommend failure or empty result keeps cached cards', () async {
    final transport = HoldTransport()..next = ok([card('KEEP', '保留')]);
    final controller = await boot(transport, FakePlatform(), ScriptDelay());
    controller.lockDestinationAt('場', destLat, destLng);
    controller.onFix(LocFix(lat: destLat + delta1000 * 0.4, lng: destLng, accuracyM: 10, speedKmh: 10, timestampMs: 1000));
    await Future<void>.delayed(const Duration(milliseconds: 1));
    expect(controller.cards.single.carparkId, 'KEEP');
    transport.next = const HttpResult(statusCode: 0, networkError: true, timedOut: true);
    await controller.requestRecommendation();
    expect(controller.cards.single.carparkId, 'KEEP');
    transport.next = ok(const []);
    await controller.requestRecommendation();
    expect(controller.cards.single.carparkId, 'KEEP');
  });

  test('engine signal ignored when fgs is not running', () async {
    final controller = await boot(HoldTransport(), FakePlatform(), ScriptDelay(), startTrip: false);
    expect(await controller.onEngineSignal(EngineSignal.power, disconnected: true), 'IGNORED_NOT_RUNNING');
  });

  test('jit fires once per session even after relock', () async {
    final transport = HoldTransport()..next = ok([card('CP')]);
    final controller = await boot(transport, FakePlatform(), ScriptDelay());
    controller.lockDestinationAt('甲', destLat, destLng);
    controller.onFix(LocFix(lat: destLat + delta1000 * 0.9, lng: destLng, accuracyM: 10, speedKmh: 20, timestampMs: 1000));
    await Future<void>.delayed(Duration.zero);
    controller.lockDestinationAt('乙', destLat, destLng);
    controller.onFix(LocFix(lat: destLat + delta1000 * 0.8, lng: destLng, accuracyM: 10, speedKmh: 20, timestampMs: 2000));
    await Future<void>.delayed(Duration.zero);
    expect(transport.calls.where((path) => path == ApiClient.RECOMMEND), hasLength(1));
  });

  test('grey retry does not block a driver ask', () async {
    final transport = HoldTransport();
    final controller = await boot(transport, FakePlatform(), ScriptDelay());
    final grey = controller.retryFromGrey();
    await Future<void>.delayed(Duration.zero);
    final asked = controller.requestRecommendation();
    await Future<void>.delayed(Duration.zero);
    expect(transport.calls.where((path) => path == ApiClient.RECOMMEND), hasLength(2));
    const failed = HttpResult(statusCode: 0, networkError: true);
    transport.next = failed;
    while (transport.waiting.isNotEmpty) {
      transport.waiting.removeAt(0).complete(failed);
      await Future<void>.delayed(Duration.zero);
    }
    await grey;
    await asked;
  });

  test('old fade does not reset the new session', () async {
    final delay = ScriptDelay();
    var n = 0;
    final controller = await boot(HoldTransport(), FakePlatform(), delay, sessionIdFactory: () => 'sess_${n++}');
    final pending = controller.onEngineSignal(EngineSignal.power, disconnected: true);
    await Future<void>.delayed(Duration.zero);
    delay.finishNext();
    await Future<void>.delayed(Duration.zero);
    final started = await controller.start(StartupEntry.a2);
    expect(started.accepted, isTrue);
    final nextSession = controller.sessionId;
    delay.finishNext();
    await pending;
    expect(controller.sessionId, nextSession);
    expect(controller.tsm.fgsRunning, isTrue);
    expect(controller.state, TripState.cruising);
  });

  test('dispose closes the snapshot stream and cancels timers', () async {
    final delay = ScriptDelay();
    final platform = FakePlatform();
    final controller = await boot(HoldTransport(), platform, delay);
    final pending = controller.onEngineSignal(EngineSignal.power, disconnected: true);
    await Future<void>.delayed(Duration.zero);
    delay.finishNext();
    await Future<void>.delayed(Duration.zero);
    final done = expectLater(controller.changes, emitsDone);
    controller.dispose();
    if (delay.pending.isNotEmpty) delay.finishNext();
    await pending;
    await done;
    expect(platform.stops, isNot(contains('ENGINE_SHUTDOWN')));
  });
}

class _RecordingFail implements SyncApi {
  _RecordingFail(this.api);
  final ApiClient api;
  @override
  Future<CallOutcome> syncDriverConfirmed(String sessionId, int timestampSec) => api.syncDriverConfirmed(sessionId, timestampSec);
  @override
  Future<CallOutcome> submitTripReport({
    required String sessionId,
    required String carparkId,
    required String ratingTag,
    required String perkStatusTag,
    required String perkNotes,
    required int tripTimestampSec,
  }) => api.submitTripReport(sessionId: sessionId, carparkId: carparkId, ratingTag: ratingTag, perkStatusTag: perkStatusTag, perkNotes: perkNotes, tripTimestampSec: tripTimestampSec);
}
