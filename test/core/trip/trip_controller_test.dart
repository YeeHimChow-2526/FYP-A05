import 'dart:async';
import 'dart:convert';

import 'package:copilot/core/nav/fake_nav_engine.dart';
import 'package:copilot/core/nav/gate_coord_resolver.dart';
import 'package:copilot/core/constants/native_config.dart';
import 'package:copilot/core/net/api_client.dart';
import 'package:copilot/core/net/api_transport.dart';
import 'package:copilot/core/net/http_result.dart';
import 'package:copilot/core/net/recommend_response.dart';
import 'package:copilot/core/net/server_issue.dart';
import 'package:copilot/core/net/recommendation_card.dart';
import 'package:copilot/core/poi/local_poi_trie.dart';
import 'package:copilot/core/poi/poi_name_resolver.dart';
import 'package:copilot/core/poi/stt_typo.dart';
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
  final utteranceIds = <String?>[];

  @override
  void speak(String text, {String? utteranceId}) {
    spoken.add(text);
    utteranceIds.add(utteranceId);
  }

  @override
  void interruptSpeech() => interrupts += 1;
  @override
  void launchGateNavigation(String label, {double? lat, double? lng}) => nav.add(label);
  final listening = <String>[];
  @override
  void startListening({bool hotword = false}) => listening.add(hotword ? 'hotword' : 'followup');
  @override
  void stopListening() => listening.add('stop');
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

PoiNameResolver _langhamResolver() {
  final trie = LocalPoiTrie.fromJsonString(
    '{"pois":[{"id":"ig:560878","t":"M","zh":"朗豪坊商場","en":"Langham","a":["朗豪坊"],"d":"YTM","la":22.31801,"lo":114.16864}]}',
  );
  return PoiNameResolver(trie: trie, typo: SttTypoCorpus(const {}));
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
  ApiTransport transport,
  FakePlatform platform,
  ScriptDelay delay, {
  PoiNameResolver? resolver,
  FakeNavEngine? navEngine,
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
    navEngine: navEngine,
    sessionIdFactory: sessionIdFactory ?? () => 'sess_test',
  );
  if (startTrip) await controller.start(StartupEntry.a2);
  return controller;
}

class _NoSync implements SyncApi {
  @override
  Future<CallOutcome> syncDriverConfirmed(String sessionId, int timestampSec, {ParkedEventIdentity identity = ParkedEventIdentity.legacy}) async => const CallOutcome(
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

void main() {
  _phase2OnSiteTests();
  _phase3DestinationTests();
  _phase3bReleaseAndVoiceTests();

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
    expect(body['origin_lat'], closeTo(destLat + delta1000 * 0.9, 1e-9));
    expect(body['origin_lng'], destLng);
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

  test('C6 nav to gate keeps tracker updates but skips destination distance', () async {
    final transport = HoldTransport()..next = ok([card('CP', '朗豪坊')]);
    final platform = FakePlatform();
    final controller = await boot(transport, platform, ScriptDelay());
    controller.lockDestinationAt('朗豪坊', destLat, destLng);
    await controller.requestRecommendation();
    await controller.confirmCandidate(0);
    expect(controller.state, TripState.navToGate);
    expect(platform.nav, isEmpty);
    expect(controller.tsm.jitTriggered, isFalse);
    final recommends = transport.calls.where((path) => path == ApiClient.RECOMMEND).length;
    controller.onFix(LocFix(lat: destLat + delta1000 * 0.9, lng: destLng, accuracyM: 10, speedKmh: 20, timestampMs: 1000));
    final firstDistance = controller.tracker.lastDistanceM;
    expect(firstDistance, isNotNull);
    controller.onFix(LocFix(lat: destLat + delta1000 * 0.1, lng: destLng, accuracyM: 10, speedKmh: 20, timestampMs: 2000));
    expect(controller.tracker.lastDistanceM, isNot(firstDistance));
    expect(controller.tsm.jitTriggered, isFalse);
    expect(transport.calls.where((path) => path == ApiClient.RECOMMEND).length, recommends);
    expect(controller.state, TripState.navToGate);
  });

  test('commit within 150m then shutdown parks and releases', () async {
    final delay = ScriptDelay();
    final transport = HoldTransport()..next = ok([card('CP', '朗豪坊')]);
    final controller = await boot(transport, FakePlatform(), delay);
    controller.lockDestinationAt('朗豪坊', destLat, destLng);
    await controller.requestRecommendation();
    await controller.confirmCandidate(0);
    expect(controller.state, TripState.navToGate);
    controller.onFix(LocFix(lat: destLat + delta1000 * 0.1, lng: destLng, accuracyM: 10, speedKmh: 1, timestampMs: 1000));
    expect(controller.tracker.lastDistanceM, lessThanOrEqualTo(NativeConfig.APPROACH_RADIUS_M));
    expect(controller.state, TripState.navToGate);
    final pending = controller.onEngineSignal(EngineSignal.power, disconnected: true);
    await Future<void>.delayed(Duration.zero);
    delay.finishNext();
    await Future<void>.delayed(Duration.zero);
    expect(controller.store.find('sess_test'), isNotNull);
    expect(transport.calls, contains(ApiClient.RELEASE));
    delay.finishNext();
    expect(await pending, 'PARKED');
  });

  test('nav to gate can enter approach muted', () async {
    final transport = HoldTransport()..next = ok([card('CP', '朗豪坊')]);
    final controller = await boot(transport, FakePlatform(), ScriptDelay());
    controller.lockDestinationAt('朗豪坊', destLat, destLng);
    await controller.requestRecommendation();
    await controller.confirmCandidate(0);
    expect(controller.state, TripState.navToGate);
    for (var i = 0; i < 4; i++) {
      controller.onFix(LocFix(lat: destLat + delta1000 * 0.07, lng: destLng, accuracyM: 10, speedKmh: 5, timestampMs: i * 1000));
    }
    expect(controller.state, TripState.approachMuted);
    expect(controller.tracker.approachMuted, isTrue);
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
    expect(controller.tsm.tripVoiceMode, TripVoiceMode.announceManual);
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

  test('C16 requestRecommendation in grey is single flight', () async {
    final transport = HoldTransport()..next = const HttpResult(statusCode: 0, networkError: true, timedOut: true);
    final controller = await boot(transport, FakePlatform(), ScriptDelay());
    controller.lockDestinationAt('場', destLat, destLng);
    controller.onFix(LocFix(lat: destLat + delta1000 * 0.4, lng: destLng, accuracyM: 10, speedKmh: 10, timestampMs: 1000));
    await Future<void>.delayed(const Duration(milliseconds: 1));
    expect(controller.state, TripState.greyBreathing);
    transport.next = null;
    final before = transport.calls.length;
    final first = controller.requestRecommendation();
    final second = controller.requestRecommendation();
    await Future<void>.delayed(Duration.zero);
    expect(transport.calls.length, before + 1);
    const failed = HttpResult(statusCode: 0, networkError: true);
    transport.next = failed;
    while (transport.waiting.isNotEmpty) {
      transport.waiting.removeAt(0).complete(failed);
      await Future<void>.delayed(Duration.zero);
    }
    await first;
    await second;
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
    await Future<void>.delayed(Duration.zero);
    expect(revoked.stops, ['PERMISSION_REVOKED']);
    expect(revoked.interrupts, 1);
    expect(revokedController.sessionId, isNull);

    final killed = FakePlatform();
    final killedController = await boot(HoldTransport(), killed, ScriptDelay());
    killedController.onOemKilled();
    await Future<void>.delayed(Duration.zero);
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

  test('oem kill without a trip does not teardown', () async {
    final platform = FakePlatform();
    final controller = await boot(HoldTransport(), platform, ScriptDelay(), startTrip: false);
    controller.onOemKilled();
    expect(platform.stops, isEmpty);
    expect(platform.interrupts, 0);
    expect(controller.sessionId, isNull);
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

  test('N-T2 confirmCandidate starts guidance once', () async {
    final nav = FakeNavEngine();
    final transport = HoldTransport()..next = ok([card('CP_LANGHAM', '朗豪坊商場停車場')]);
    final platform = FakePlatform();
    final controller = await boot(transport, platform, ScriptDelay(), navEngine: nav);
    controller.lockDestinationAt('朗豪坊', destLat, destLng);
    await controller.requestRecommendation();
    await controller.confirmCandidate(0);
    expect(controller.state, TripState.navToGate);
    // t1245：「去」先導航去用戶目的地，成功 commit 先改去車場。
    expect(nav.calls.where((call) => call == 'startGuidance'), ['startGuidance', 'startGuidance']);
    expect(controller.navTarget?.kind, NavTargetKind.carpark);
    expect(platform.nav, isEmpty);
    expect(nav.activeLat, kDemoCarparkCoords['CP_LANGHAM']!.lat);
    expect(nav.activeLng, kDemoCarparkCoords['CP_LANGHAM']!.lng);
  });

  test('N-T2b committed confirm closes cards and restores SDK audio after speech', () async {
    final nav = FakeNavEngine();
    final transport = HoldTransport()..next = ok([card('CP_LANGHAM', '朗豪坊商場停車場')]);
    final platform = FakePlatform();
    final controller = await boot(transport, platform, ScriptDelay(), navEngine: nav);
    controller.tsm.applyVoiceCapability(speakingMode, true);
    controller.lockDestinationAt('朗豪坊', destLat, destLng);
    await controller.requestRecommendation();
    expect(controller.snapshot.cardsOpen, isTrue);
    await controller.confirmCandidate(0);
    expect(controller.snapshot.cardsOpen, isFalse);
    expect(nav.audioSuspended, isTrue);
    expect(platform.spoken.last, '已確認朗豪坊商場停車場，正在計算路線');
    controller.onSpeechDone(platform.utteranceIds.last!, played: false);
    expect(nav.audioSuspended, isFalse);
    expect(nav.turnByTurnAudioEnabled, isTrue);
    controller.toggleCards();
    expect(controller.snapshot.cardsOpen, isTrue);
    await controller.confirmCandidate(0);
    expect(controller.snapshot.cardsOpen, isFalse);
    expect(nav.calls.where((call) => call == 'suspendAudio'), ['suspendAudio']);
  });

  test('N-T2c failed confirm keeps the cards and the SDK audio', () async {
    final nav = FakeNavEngine();
    final transport = HoldTransport()..next = ok([card('CP_LANGHAM', '朗豪坊商場停車場')]);
    final platform = FakePlatform();
    final controller = await boot(transport, platform, ScriptDelay(), navEngine: nav);
    controller.tsm.applyVoiceCapability(speakingMode, true);
    controller.lockDestinationAt('朗豪坊', destLat, destLng);
    await controller.requestRecommendation();
    transport.next = const HttpResult(statusCode: 500);
    await controller.confirmCandidate(0);
    expect(controller.snapshot.cardsOpen, isTrue);
    expect(nav.audioSuspended, isFalse);
    expect(nav.calls, isNot(contains('suspendAudio')));
  });

  test('N-T2d third distinct carpark waits; cancel keeps the route; consent commits once', () async {
    final nav = FakeNavEngine();
    final transport = HoldTransport()
      ..next = ok([
        card('CP_A', '海富停車場'),
        card('CP_B', '朗豪坊停車場'),
        card('CP_C', 'MOKO新世紀廣場停車場'),
      ]);
    final platform = FakePlatform();
    final controller = await boot(transport, platform, ScriptDelay(), navEngine: nav);
    controller.tsm.applyVoiceCapability(speakingMode, true);
    controller.lockDestinationAt('朗豪坊', destLat, destLng);
    await controller.requestRecommendation();
    await controller.confirmCandidate(0);
    await controller.confirmCandidate(1);
    final switches = platform.spoken.where((line) => line.startsWith('已確認') || line.startsWith('而家確認')).toList();
    expect(switches, [
      '已確認海富停車場，正在計算路線',
      '已確認朗豪坊停車場，正在計算路線',
    ]);
    final commits = transport.calls.where((path) => path == ApiClient.COMMIT).length;
    final routes = nav.calls.where((call) => call == 'setDestinations').length;
    await controller.confirmCandidate(2);
    expect(controller.snapshot.pendingSwitchName, 'MOKO新世紀廣場停車場');
    expect(controller.committedCarparkId, 'CP_B');
    expect(transport.calls.where((path) => path == ApiClient.COMMIT), hasLength(commits));
    expect(nav.calls.where((call) => call == 'setDestinations'), hasLength(routes));
    expect(platform.spoken, contains('而家確認去MOKO新世紀廣場停車場嗎？'));
    expect(platform.spoken, isNot(contains('請拍一拍膠囊答我')));
    controller.rejectPendingSwitch();
    expect(controller.snapshot.pendingSwitchName, isNull);
    expect(controller.committedCarparkId, 'CP_B');
    expect(nav.calls.where((call) => call == 'setDestinations'), hasLength(routes));
    await controller.confirmCandidate(2);
    await controller.acceptPendingSwitch();
    expect(controller.committedCarparkId, 'CP_C');
    expect(controller.navTarget?.title, 'MOKO新世紀廣場停車場');
    expect(platform.spoken.where((line) => line.startsWith('已確認')), hasLength(3));
    final after = transport.calls.where((path) => path == ApiClient.COMMIT).length;
    await controller.acceptPendingSwitch();
    expect(transport.calls.where((path) => path == ApiClient.COMMIT), hasLength(after));
    controller.toggleCards();
    await controller.confirmCandidate(2);
    expect(controller.committedCarparkId, 'CP_C');
    expect(transport.calls.where((path) => path == ApiClient.COMMIT), hasLength(after));
  });

  test('N-T2e failed third does not count; next new carpark still asks', () async {
    final transport = HoldTransport()
      ..next = ok([
        card('CP_A', '海富停車場'),
        card('CP_B', '朗豪坊停車場'),
        card('CP_C', 'MOKO新世紀廣場停車場'),
        card('CP_D', '旺角中心停車場'),
      ]);
    final platform = FakePlatform();
    final controller = await boot(transport, platform, ScriptDelay());
    controller.tsm.applyVoiceCapability(speakingMode, true);
    controller.lockDestinationAt('朗豪坊', destLat, destLng);
    await controller.requestRecommendation();
    await controller.confirmCandidate(0);
    await controller.confirmCandidate(1);
    transport.next = const HttpResult(statusCode: 500);
    await controller.confirmCandidate(2);
    expect(controller.committedCarparkId, 'CP_B');
    expect(platform.spoken, contains('而家確認去MOKO新世紀廣場停車場嗎？'));
    await controller.acceptPendingSwitch();
    expect(platform.spoken.last, '網絡唔順，遲啲再試');
    expect(platform.spoken, isNot(contains('已確認MOKO新世紀廣場停車場，正在計算路線')));
    expect(controller.committedCarparkId, 'CP_B');
    await controller.confirmCandidate(3);
    expect(platform.spoken, contains('而家確認去旺角中心停車場嗎？'));
    expect(controller.committedCarparkId, 'CP_B');
    transport.next = ok([
      card('CP_A', '海富停車場'),
      card('CP_B', '朗豪坊停車場'),
      card('CP_C', 'MOKO新世紀廣場停車場'),
      card('CP_D', '旺角中心停車場'),
    ]);
    await controller.acceptPendingSwitch();
    expect(controller.committedCarparkId, 'CP_D');
  });

  test('N-T2f destination change keeps the count; a new trip clears it', () async {
    final transport = HoldTransport()
      ..next = ok([
        card('CP_A', '海富停車場'),
        card('CP_B', '朗豪坊停車場'),
        card('CP_C', '戲曲中心停車場'),
      ]);
    final platform = FakePlatform();
    final controller = await boot(transport, platform, ScriptDelay());
    controller.tsm.applyVoiceCapability(speakingMode, true);
    controller.lockDestinationAt('朗豪坊', destLat, destLng);
    await controller.requestRecommendation();
    await controller.confirmCandidate(0);
    await controller.confirmCandidate(1);
    controller.lockDestinationAt('海港城', destLat + 0.01, destLng);
    transport.next = ok([card('CP_C', '戲曲中心停車場')]);
    await controller.requestRecommendation();
    await controller.confirmCandidate(0);
    expect(platform.spoken, contains('而家確認去戲曲中心停車場嗎？'));
    expect(controller.snapshot.pendingSwitchName, '戲曲中心停車場');
    expect(transport.calls.where((path) => path == ApiClient.COMMIT), hasLength(2));
    await controller.abortTripByUser();
    transport.next = ok([card('CP_E', '海富停車場')]);
    await controller.start(StartupEntry.a2);
    controller.tsm.applyVoiceCapability(speakingMode, true);
    controller.lockDestinationAt('朗豪坊', destLat, destLng);
    await controller.requestRecommendation();
    final before = platform.spoken.length;
    await controller.confirmCandidate(0);
    expect(platform.spoken.sublist(before), ['已確認海富停車場，正在計算路線']);
  });

  test('N-T2g timeout and injected yes do not double commit', () async {
    final delay = ScriptDelay();
    final transport = HoldTransport()
      ..next = ok([
        card('CP_A', '海富停車場'),
        card('CP_B', '朗豪坊停車場'),
        card('CP_C', 'MOKO新世紀廣場停車場'),
      ]);
    final platform = FakePlatform();
    final controller = await boot(transport, platform, delay);
    controller.tsm.applyVoiceCapability(
      const CapabilitySnapshot(
        stt: CapabilityStatus.available,
        tts: CapabilityStatus.available,
        mode: TripVoiceMode.fullVoice,
        voiceInputEnabled: true,
        canListen: true,
      ),
      true,
    );
    controller.lockDestinationAt('朗豪坊', destLat, destLng);
    await controller.requestRecommendation();
    await controller.confirmCandidate(0);
    await controller.confirmCandidate(1);
    await controller.confirmCandidate(2);
    expect(platform.spoken.last, '而家確認去MOKO新世紀廣場停車場嗎？${ZhHkTemplates.TAP_CAPSULE}');
    expect(delay.pending, isNotEmpty);
    delay.finishNext();
    await Future<void>.delayed(Duration.zero);
    expect(controller.snapshot.pendingSwitchName, isNull);
    expect(controller.committedCarparkId, 'CP_B');
    await controller.confirmCandidate(2);
    expect(controller.onVoiceUtterance(code: 'CONFIRM_TARGET', confirmed: true), 'CONFIRM_TARGET');
    for (var i = 0; i < 5; i++) {
      await Future<void>.delayed(Duration.zero);
    }
    expect(controller.committedCarparkId, 'CP_C');
    final commits = transport.calls.where((path) => path == ApiClient.COMMIT).length;
    await controller.acceptPendingSwitch();
    expect(transport.calls.where((path) => path == ApiClient.COMMIT), hasLength(commits));
  });

  test('N-T2h normal mode hides demonstration supply but keeps a stale real card committable', () async {
    final transport = HoldTransport()
      ..next = ok([
        card('CP_LANGHAM', '朗豪坊停車場').copyWith(),
        RecommendationCard(
          carparkId: 'tdc14p8428',
          name: '海富停車場',
          type: 'INDOOR',
          vacancies: 30,
          distanceMeters: 400,
          feeSummary: '',
          spokenCue: '去海富',
          isStale: true,
          availability: CardAvailability.staleEstimate,
          commitAllowed: true,
        ),
      ]);
    final platform = FakePlatform();
    final controller = await boot(transport, platform, ScriptDelay());
    controller.isolateDemonstrationSupply = true;
    controller.lockDestinationAt('朗豪坊', destLat, destLng);
    await controller.requestRecommendation();
    expect(controller.cards.first.demonstrationSupply, isTrue);
    expect(controller.cards.first.commitAllowed, isFalse);
    expect(controller.cards[1].demonstrationSupply, isFalse);
    expect(controller.cards[1].commitAllowed, isTrue);
    expect(controller.snapshot.vacancyFresh, isFalse);
    await controller.confirmCandidate(0);
    expect(controller.committedCarparkId, isNull);
    expect(transport.calls.where((path) => path == ApiClient.COMMIT), isEmpty);
    await controller.confirmCandidate(1);
    expect(controller.committedCarparkId, 'tdc14p8428');
  });

  test('N-T3 recommend success emits cards on the snapshot', () async {
    final transport = HoldTransport()..next = ok([card('CP', '朗豪坊')]);
    final controller = await boot(transport, FakePlatform(), ScriptDelay());
    final seen = <TripSnapshot>[];
    final sub = controller.changes.listen(seen.add);
    controller.lockDestinationAt('朗豪坊', destLat, destLng);
    await controller.requestRecommendation();
    await sub.cancel();
    expect(controller.snapshot.cards, isNotEmpty);
    expect(controller.snapshot.destinationName, '朗豪坊');
    expect(seen.any((item) => item.cards.isNotEmpty), isTrue);
  });

  test('N-T4 origin uses last fix and missing fix does not throw', () async {
    final transport = HoldTransport()..next = ok([card('CP')]);
    final controller = await boot(transport, FakePlatform(), ScriptDelay());
    controller.onFix(const LocFix(lat: 22.32, lng: 114.17, accuracyM: 8, speedKmh: 10, timestampMs: 1000));
    controller.lockDestinationAt('場', destLat, destLng);
    await controller.requestRecommendation();
    final body = jsonDecode(transport.bodies.last) as Map<String, Object?>;
    expect(body['origin_lat'], 22.32);
    expect(body['origin_lng'], 114.17);

    final bareTransport = HoldTransport()..next = ok([card('CP')]);
    final barePlatform = FakePlatform();
    final bare = await boot(bareTransport, barePlatform, ScriptDelay());
    bare.tsm.applyVoiceCapability(speakingMode, true);
    await bare.requestRecommendation();
    final bareBody = jsonDecode(bareTransport.bodies.single) as Map<String, Object?>;
    expect(bareBody['origin_lat'], 0.0);
    expect(bareBody['origin_lng'], 0.0);
    expect(barePlatform.spoken, [ZhHkTemplates.ON_SITE_LOCATION_MISSED, '去CP']);
    await bare.requestRecommendation();
    expect(barePlatform.spoken.where((line) => line == ZhHkTemplates.ON_SITE_LOCATION_MISSED), hasLength(1));
  });

  test('N-T7 fix then destination text prefetches without a second fix', () async {
    final transport = HoldTransport()..next = ok([card('CP_LANGHAM', '朗豪坊商場停車場')]);
    final controller = await boot(transport, FakePlatform(), ScriptDelay(), resolver: _langhamResolver());
    controller.onFix(const LocFix(lat: 22.3186, lng: 114.1686, accuracyM: 8, speedKmh: 20, timestampMs: 5000));
    expect(transport.calls, isEmpty);
    await controller.submitDestinationText('朗豪坊');
    expect(transport.calls.where((path) => path == ApiClient.RECOMMEND), hasLength(1));
    expect(controller.snapshot.cards, isNotEmpty);
    expect(controller.snapshot.lastOriginLat, 22.3186);
  });

  test('N-T8 guidance failure keeps nav to gate and falls back once', () async {
    final nav = FakeNavEngine()..shouldFailGuidance = true;
    final transport = HoldTransport()..next = ok([card('CP', '朗豪坊')]);
    final platform = FakePlatform();
    final controller = await boot(transport, platform, ScriptDelay(), navEngine: nav);
    controller.lockDestinationAt('朗豪坊', destLat, destLng);
    await controller.requestRecommendation();
    await controller.confirmCandidate(0);
    expect(controller.state, TripState.navToGate);
    expect(platform.nav, ['朗豪坊']);
    // 目的地一次（失敗）＋車場一次（失敗 → 外部導航後備一次）
    expect(nav.calls.where((call) => call == 'startGuidance'), ['startGuidance', 'startGuidance']);
    expect(controller.navTarget?.state, NavTargetState.failed);
  });
}

class _RecordingFail implements SyncApi {
  _RecordingFail(this.api);
  final ApiClient api;
  @override
  Future<CallOutcome> syncDriverConfirmed(String sessionId, int timestampSec, {ParkedEventIdentity identity = ParkedEventIdentity.legacy}) => api.syncDriverConfirmed(sessionId, timestampSec, identity: identity);
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

void _phase2OnSiteTests() {
  group('Phase2 現場即泊 onSite', () {
    test('P2-OS1 startOnSite opens fgs as onSite, waits for a fix, no recommend yet', () async {
      final transport = HoldTransport()..next = ok([card('CP_HERE')]);
      final platform = FakePlatform();
      final controller = await boot(transport, platform, ScriptDelay(), startTrip: false);
      final outcome = await controller.startOnSite();
      expect(outcome.accepted, isTrue);
      expect(platform.starts, 1);
      expect(controller.state, TripState.cruising);
      expect(controller.tsm.onSitePendingFix, isTrue);
      expect(transport.calls, isEmpty);
    });

    test('P2-OS2 first accurate fix locks current coords and fires one immediate recommend', () async {
      final transport = HoldTransport()..next = ok([card('CP_HERE')]);
      final controller = await boot(transport, FakePlatform(), ScriptDelay(), startTrip: false);
      await controller.startOnSite();
      controller.onFix(const LocFix(lat: 22.30, lng: 114.17, accuracyM: 100, speedKmh: 0, timestampMs: 1000));
      expect(controller.tsm.onSitePendingFix, isFalse);
      expect(controller.tsm.destLat, 22.30);
      expect(controller.tsm.destLng, 114.17);
      expect(controller.tsm.destinationName, TripController.onSiteDestinationName);
      expect(controller.tsm.jitTriggered, isTrue);
      expect(transport.calls.where((p) => p == ApiClient.RECOMMEND), hasLength(1));
      final body = jsonDecode(transport.bodies.single) as Map<String, Object?>;
      expect(body['dest_lat'], 22.30);
      expect(body['origin_lat'], 22.30);
      controller.onFix(const LocFix(lat: 22.3001, lng: 114.17, accuracyM: 5, speedKmh: 0, timestampMs: 2000));
      expect(transport.calls.where((p) => p == ApiClient.RECOMMEND), hasLength(1));
      expect(controller.tsm.destLat, 22.30);
    });

    test('P2-OS3 inaccurate fix (>${NativeConfig.ON_SITE_GPS_ACCURACY_MAX_M} m) does not lock; speaks once', () async {
      final transport = HoldTransport()..next = ok([card('CP_HERE')]);
      final platform = FakePlatform();
      final controller = await boot(transport, platform, ScriptDelay(), startTrip: false);
      await controller.startOnSite(voice: speakingMode);
      controller.onFix(const LocFix(lat: 22.30, lng: 114.17, accuracyM: 100.5, speedKmh: 0, timestampMs: 1000));
      controller.onFix(const LocFix(lat: 22.30, lng: 114.17, accuracyM: 250, speedKmh: 0, timestampMs: 2000));
      expect(controller.tsm.onSitePendingFix, isTrue);
      expect(controller.tsm.destLat, isNull);
      expect(transport.calls, isEmpty);
      expect(platform.spoken.where((t) => t == ZhHkTemplates.ON_SITE_LOCATION_MISSED), hasLength(1));
      controller.onFix(const LocFix(lat: 22.31, lng: 114.18, accuracyM: 30, speedKmh: 0, timestampMs: 3000));
      expect(controller.tsm.destLat, 22.31);
      expect(transport.calls.where((p) => p == ApiClient.RECOMMEND), hasLength(1));
    });

    test('P2-OS4 onSite without awaitOnSiteFix still requires accuracy (unchanged contract)', () async {
      final platform = FakePlatform();
      final controller = await boot(HoldTransport(), platform, ScriptDelay(), startTrip: false);
      final outcome = await controller.start(StartupEntry.onSite);
      expect(outcome.accepted, isFalse);
      expect(platform.starts, 0);
      final ok2 = await controller.start(StartupEntry.onSite, gpsAccuracyM: 50);
      expect(ok2.accepted, isTrue);
      expect(controller.tsm.onSitePendingFix, isFalse);
    });

    test('P2-OS5 a2 trip never auto-locks current position', () async {
      final transport = HoldTransport()..next = ok([card('CP')]);
      final controller = await boot(transport, FakePlatform(), ScriptDelay());
      controller.onFix(const LocFix(lat: 22.30, lng: 114.17, accuracyM: 5, speedKmh: 0, timestampMs: 1000));
      expect(controller.tsm.destLat, isNull);
      expect(transport.calls, isEmpty);
    });

    test('P2-PREF recommend uses controller.preferenceCode', () async {
      final transport = HoldTransport()..next = ok([card('CP')]);
      final controller = await boot(transport, FakePlatform(), ScriptDelay());
      controller.preferenceCode = 'INDOOR_ONLY';
      controller.lockDestinationAt('尖沙咀', destLat, destLng);
      controller.onFix(LocFix(lat: destLat + delta1000 * 0.5, lng: destLng, accuracyM: 10, speedKmh: 20, timestampMs: 1000));
      final body = jsonDecode(transport.bodies.single) as Map<String, Object?>;
      expect(body['preference'], 'INDOOR_ONLY');
    });

    RecommendationCard ref(String id, String availability) => RecommendationCard(
          carparkId: id, name: id, type: 'INDOOR', vacancies: 0, distanceMeters: 5, feeSummary: '',
          spokenCue: '$id只供參考', isStale: availability == CardAvailability.lastKnownZero,
          availability: availability, commitAllowed: false);

    test('T1228 P voice reads actual viable lead, not raw cards[0] reference', () async {
      final platform = FakePlatform();
      final transport = HoldTransport()..next = ok([ref('FULL', CardAvailability.noAvailability), card('OK')]);
      final controller = await boot(transport, platform, ScriptDelay());
      controller.lockDestinationAt('尖沙咀', destLat, destLng);
      controller.tsm.applyVoiceCapability(
        const CapabilitySnapshot(stt: CapabilityStatus.unsupported, tts: CapabilityStatus.available, mode: TripVoiceMode.announceManual, voiceInputEnabled: false),
        true,
      );
      await controller.requestRecommendation();
      expect(controller.leadIndex, 1);
      expect(platform.spoken, contains('去OK'));
      expect(platform.spoken, isNot(contains('FULL只供參考')));
      controller.showCardDetails();
      expect(platform.spoken.last, '去OK');
    });

    test('T1228 reference card confirm is refused in App (no commit request sent)', () async {
      final platform = FakePlatform();
      final transport = HoldTransport()..next = ok([ref('S0', CardAvailability.lastKnownZero), card('OK')]);
      final controller = await boot(transport, platform, ScriptDelay());
      controller.lockDestinationAt('尖沙咀', destLat, destLng);
      controller.tsm.applyVoiceCapability(
        const CapabilitySnapshot(stt: CapabilityStatus.unsupported, tts: CapabilityStatus.available, mode: TripVoiceMode.announceManual, voiceInputEnabled: false),
        true,
      );
      await controller.requestRecommendation();
      await controller.confirmCandidate(0);
      expect(transport.calls, isNot(contains(ApiClient.COMMIT)));
      expect(platform.spoken.last, ZhHkTemplates.REFERENCE_NOT_COMMITTABLE);
      expect(controller.committedCarparkId, isNull);
    });

    test('T1228 all reference: speaks server expand prompt, cards shown, details read reference honestly', () async {
      final platform = FakePlatform();
      final transport = HoldTransport()
        ..next = const HttpResult(
          statusCode: 200,
          recommendResponse: RecommendResponse(status: 'SUCCESS', spokenCue: '要唔要搵遠少少？', awaitVoiceReply: true, cards: [
            RecommendationCard(carparkId: 'F', name: 'F', type: 'INDOOR', vacancies: 0, distanceMeters: 5, feeSummary: '',
                spokenCue: 'F暫時冇位只供參考', isStale: false, availability: CardAvailability.noAvailability, commitAllowed: false),
          ]),
        );
      final controller = await boot(transport, platform, ScriptDelay());
      controller.lockDestinationAt('尖沙咀', destLat, destLng);
      controller.tsm.applyVoiceCapability(
        const CapabilitySnapshot(stt: CapabilityStatus.unsupported, tts: CapabilityStatus.available, mode: TripVoiceMode.announceManual, voiceInputEnabled: false),
        true,
      );
      await controller.requestRecommendation();
      expect(controller.leadIndex, isNull);
      expect(platform.spoken, contains('要唔要搵遠少少？'));
      expect(controller.cards.single.carparkId, 'F');
      controller.showCardDetails();
      expect(platform.spoken.last, 'F暫時冇位只供參考');
    });

    test('T1229 unconfirmed lead: P says nothing reservable; voice confirm refuses without committing another card', () async {
      final platform = FakePlatform();
      const unk = RecommendationCard(carparkId: 'U', name: 'U', type: 'INDOOR', vacancies: -1, distanceMeters: 5, feeSummary: '',
          spokenCue: 'U未確認唔可以預留', isStale: false, availability: CardAvailability.unconfirmed, commitAllowed: false);
      final transport = HoldTransport()..next = ok([unk]);
      final controller = await boot(transport, platform, ScriptDelay());
      controller.lockDestinationAt('尖沙咀', destLat, destLng);
      controller.tsm.applyVoiceCapability(
        const CapabilitySnapshot(stt: CapabilityStatus.unsupported, tts: CapabilityStatus.available, mode: TripVoiceMode.announceManual, voiceInputEnabled: false),
        true,
      );
      await controller.requestRecommendation();
      expect(platform.spoken, containsAllInOrder([ZhHkTemplates.NO_RESERVABLE_OPTION, 'U未確認唔可以預留']));
      await controller.confirmCandidate(controller.leadIndex!);
      expect(transport.calls, isNot(contains(ApiClient.COMMIT)));
      expect(platform.spoken.last, ZhHkTemplates.REFERENCE_NOT_COMMITTABLE);
    });
  });
}

LocFix _fixAt(double lat, int ts, {double speed = 20}) => LocFix(lat: lat, lng: destLng, accuracyM: 10, speedKmh: speed, timestampMs: ts);

int _recommendCalls(HoldTransport t) => t.calls.where((p) => p == ApiClient.RECOMMEND).length;

const CapabilitySnapshot _fullVoice = CapabilitySnapshot(stt: CapabilityStatus.available, tts: CapabilityStatus.available, mode: TripVoiceMode.fullVoice, voiceInputEnabled: true);

void _phase3DestinationTests() {
  group('Phase3 改目的地／1 km latch／卡片', () {
    test('P3-D1 A 900m fetch → B 2000m clears everything → B 900m second fetch', () async {
      final transport = HoldTransport()..next = ok([card('CP_A').copyWith(vacancies: 12)]);
      final controller = await boot(transport, FakePlatform(), ScriptDelay());
      controller.lockDestinationAt('A', destLat, destLng);
      final here = destLat + delta1000 * 0.9;
      controller.onFix(_fixAt(here, 1000));
      await Future<void>.delayed(Duration.zero);
      expect(_recommendCalls(transport), 1);
      expect(controller.snapshot.readinessLatched, isTrue);
      expect(controller.snapshot.vacancyPip, StatusPip.green);
      expect(controller.cards.single.carparkId, 'CP_A');
      controller.fullCarparkIds.add('CP_FULL_A');

      final bLat = here + delta1000 * 2;
      controller.lockDestinationAt('B', bLat, destLng);
      final snap = controller.snapshot;
      expect(snap.cards, isEmpty);
      expect(snap.readinessLatched, isFalse);
      expect(snap.vacancyPip, StatusPip.none);
      expect(snap.vacancyFresh, isFalse);
      expect(snap.cardsOpen, isFalse);
      expect(controller.fullCarparkIds, isEmpty);
      expect(controller.tsm.jitTriggered, isFalse);
      expect(controller.tsm.recommend, isFalse);
      expect(controller.state, TripState.cruising);
      expect(_recommendCalls(transport), 1);

      transport.next = ok([card('CP_B')]);
      controller.onFix(_fixAt(bLat - delta1000 * 0.9, 2000));
      await Future<void>.delayed(Duration.zero);
      expect(_recommendCalls(transport), 2);
      final body = jsonDecode(transport.bodies.last) as Map<String, Object?>;
      expect(body['dest_lat'], bLat);
      expect(body['dest_name'], 'B');
      expect(controller.snapshot.readinessLatched, isTrue);
      expect(controller.cards.single.carparkId, 'CP_B');
    });

    test('P3-D2 same point re-lock (name only) is not a destination change', () async {
      final transport = HoldTransport()..next = ok([card('CP')]);
      final controller = await boot(transport, FakePlatform(), ScriptDelay());
      controller.lockDestinationAt('甲', destLat, destLng);
      controller.onFix(_fixAt(destLat + delta1000 * 0.9, 1000));
      await Future<void>.delayed(Duration.zero);
      controller.lockDestinationAt('甲商場', destLat, destLng);
      expect(controller.snapshot.readinessLatched, isTrue);
      expect(controller.cards, isNotEmpty);
    });

    test('P3-D3 change from navToGate stops old guidance and re-triggers for new destination', () async {
      final nav = FakeNavEngine();
      final transport = HoldTransport()..next = ok([card('CP', '朗豪坊')]);
      final controller = await boot(transport, FakePlatform(), ScriptDelay(), navEngine: nav);
      controller.lockDestinationAt('朗豪坊', destLat, destLng);
      await controller.requestRecommendation();
      await controller.confirmCandidate(0);
      expect(controller.state, TripState.navToGate);
      expect(nav.guidanceRunning, isTrue);
      final here = destLat + delta1000 * 0.1;
      controller.onFix(_fixAt(here, 1000));
      final before = _recommendCalls(transport);
      controller.lockDestinationAt('新地', here + delta1000 * 0.8, destLng);
      await Future<void>.delayed(Duration.zero);
      // t1245：改目的地 → 車場路線取消，即刻導航去新嘅用戶目的地。
      expect(nav.activeTitle, '新地');
      expect(controller.navTarget?.kind, NavTargetKind.destination);
      expect(controller.committedCarparkId, isNull);
      expect(controller.state, isNot(TripState.navToGate));
      expect(_recommendCalls(transport), before + 1, reason: '新目的地 800 m 內即時再觸發 JIT');
      expect(controller.snapshot.readinessLatched, isTrue);
    });

    test('P3-D4 change from approachMuted leaves muted and re-triggers', () async {
      final transport = HoldTransport()..next = ok([card('CP', '朗豪坊')]);
      final controller = await boot(transport, FakePlatform(), ScriptDelay());
      controller.lockDestinationAt('朗豪坊', destLat, destLng);
      await controller.requestRecommendation();
      await controller.confirmCandidate(0);
      for (var i = 0; i < 4; i++) {
        controller.onFix(_fixAt(destLat + delta1000 * 0.07, i * 1000, speed: 5));
      }
      expect(controller.state, TripState.approachMuted);
      final before = _recommendCalls(transport);
      controller.lockDestinationAt('新地', destLat + delta1000 * 0.5, destLng);
      await Future<void>.delayed(Duration.zero);
      expect(controller.state, isNot(TripState.approachMuted));
      expect(controller.tracker.approachMuted, isFalse);
      expect(_recommendCalls(transport), before + 1);
    });

    test('P3-D5 rapid A→B→A: late A1 recommend result is dropped, A3 result kept', () async {
      final transport = HoldTransport();
      final controller = await boot(transport, FakePlatform(), ScriptDelay());
      final here = destLat + delta1000 * 0.9;
      controller.lockDestinationAt('A', destLat, destLng);
      controller.onFix(_fixAt(here, 1000));
      expect(transport.waiting, hasLength(1));
      controller.lockDestinationAt('B', here + delta1000 * 2, destLng);
      controller.lockDestinationAt('A', destLat, destLng);
      expect(transport.waiting, hasLength(2), reason: '返 A 係新確認，要重新觸發');
      transport.waiting[0].complete(ok([card('OLD_A1')]));
      await Future<void>.delayed(Duration.zero);
      expect(controller.cards, isEmpty);
      transport.waiting[1].complete(ok([card('NEW_A3')]));
      await Future<void>.delayed(Duration.zero);
      expect(controller.cards.single.carparkId, 'NEW_A3');
    });

    test('P3-D6 commit result arriving after destination change does not navigate', () async {
      final nav = FakeNavEngine();
      final transport = HoldTransport()..next = ok([card('CP_A')]);
      final controller = await boot(transport, FakePlatform(), ScriptDelay(), navEngine: nav);
      controller.lockDestinationAt('A', destLat, destLng);
      await controller.requestRecommendation();
      transport.next = null;
      final commit = controller.confirmCandidate(0);
      await Future<void>.delayed(Duration.zero);
      controller.lockDestinationAt('B', destLat + delta1000 * 3, destLng);
      // Phase 3b：commit 仲喺途中都即時送 DESTINATION_CHANGED（冇 lock_revision；伺服器靠 epoch 刪舊鎖／拒遲到 commit）。
      expect(transport.calls.last, ApiClient.RELEASE);
      final release = jsonDecode(transport.bodies.last) as Map<String, Object?>;
      expect(release['reason'], 'DESTINATION_CHANGED');
      expect(release.containsKey('lock_revision'), isFalse);
      expect(release['new_destination_epoch'], controller.destinationEpoch);
      transport.waiting.first.complete(const HttpResult(statusCode: 200, bodyJson: '{"status":"COMMITTED","lock_revision":1}'));
      await commit;
      expect(controller.heldLock, isNull);
      expect(controller.state, isNot(TripState.navToGate));
      expect(controller.committedCarparkId, isNull);
      await Future<void>.delayed(Duration.zero);
      expect(nav.activeTitle, 'B', reason: '遲到 commit 唔會改路線去車場，仍然係用戶目的地 B');
    });

    test('P3-L1 1 km latch survives moving away and network failure; resets on end', () async {
      final transport = HoldTransport()..next = ok([card('CP')]);
      final controller = await boot(transport, FakePlatform(), ScriptDelay());
      controller.lockDestinationAt('A', destLat, destLng);
      controller.onFix(_fixAt(destLat + delta1000 * 0.95, 1000));
      await Future<void>.delayed(Duration.zero);
      expect(controller.snapshot.readinessLatched, isTrue);
      expect(controller.snapshot.vacancyPip, StatusPip.red, reason: 'vacancies 1 ≤ LOW_WATERMARK_VACANCIES');
      expect(controller.snapshot.vacancyFresh, isTrue);
      controller.onFix(_fixAt(destLat + delta1000 * 1.5, 2000));
      expect(controller.snapshot.readinessLatched, isTrue);
      transport.next = const HttpResult(statusCode: 0, networkError: true, timedOut: true);
      await controller.requestRecommendation();
      expect(controller.state, TripState.greyBreathing);
      expect(controller.snapshot.readinessLatched, isTrue);
      expect(controller.snapshot.vacancyFresh, isFalse, reason: '網絡失敗後唔可以當新鮮空位');
      await controller.abortTripByUser();
      expect(controller.snapshot.readinessLatched, isFalse);
    });

    test('P3-N1 network recovery restores a real pip (no StatusPip.none trap)', () async {
      final transport = HoldTransport()..next = const HttpResult(statusCode: 0, networkError: true, timedOut: true);
      final controller = await boot(transport, FakePlatform(), ScriptDelay());
      controller.lockDestinationAt('A', destLat, destLng);
      controller.onFix(_fixAt(destLat + delta1000 * 0.5, 1000));
      await Future<void>.delayed(Duration.zero);
      expect(controller.state, TripState.greyBreathing);
      transport.next = ok([card('CP').copyWith(vacancies: 20)]);
      await controller.requestRecommendation();
      expect(controller.tsm.networkDegraded, isFalse);
      expect(controller.state, TripState.nearbyReady);
      expect(controller.snapshot.vacancyPip, StatusPip.green);
      expect(controller.snapshot.vacancyFresh, isTrue);
      // commit 成功唔再洗走 pip
      transport.next = const HttpResult(statusCode: 200);
      await controller.confirmCandidate(0);
      expect(controller.snapshot.vacancyPip, StatusPip.green);
    });

    test('P3-N2 stale or unknown lead card never shows fresh capacity', () async {
      final stale = RecommendationCard(carparkId: 'M1', name: '咪錶', type: 'M', vacancies: -1, distanceMeters: 10, feeSummary: '', spokenCue: '去M1', isStale: true);
      final transport = HoldTransport()..next = ok([stale]);
      final controller = await boot(transport, FakePlatform(), ScriptDelay());
      controller.lockDestinationAt('A', destLat, destLng);
      controller.onFix(_fixAt(destLat + delta1000 * 0.5, 1000));
      await Future<void>.delayed(Duration.zero);
      expect(controller.snapshot.vacancyPip, StatusPip.none);
      expect(controller.snapshot.vacancyFresh, isFalse);
      expect(controller.snapshot.readinessLatched, isTrue);
    });

    test('P3-C1 JIT is silent and never opens cards', () async {
      final platform = FakePlatform();
      final transport = HoldTransport()..next = ok([card('CP')]);
      final controller = await boot(transport, platform, ScriptDelay());
      controller.tsm.applyVoiceCapability(speakingMode, false);
      controller.lockDestinationAt('A', destLat, destLng);
      controller.onFix(_fixAt(destLat + delta1000 * 0.5, 1000));
      await Future<void>.delayed(Duration.zero);
      expect(controller.cards, isNotEmpty);
      expect(controller.snapshot.cardsOpen, isFalse);
      expect(platform.spoken, isEmpty);
      controller.toggleCards();
      expect(controller.snapshot.cardsOpen, isTrue);
      controller.toggleCards();
      expect(controller.snapshot.cardsOpen, isFalse);
    });

    test('P3-C2 full voice: P speaks lead only; 詳細啲 opens cards and reads', () async {
      final platform = FakePlatform();
      final transport = HoldTransport()..next = ok([card('CP')]);
      final controller = await boot(transport, platform, ScriptDelay());
      controller.tsm.applyVoiceCapability(_fullVoice, false);
      controller.lockDestinationAt('A', destLat, destLng);
      controller.onFix(_fixAt(destLat + delta1000 * 2, 500));
      await controller.requestRecommendation();
      expect(platform.spoken, ['去CP']);
      expect(controller.snapshot.cardsOpen, isFalse);
      controller.showCardDetails();
      expect(controller.snapshot.cardsOpen, isTrue);
      expect(platform.spoken, ['去CP', '去CP']);
    });

    test('P3-C3 STT unavailable (announce) and manual-only: P opens cards', () async {
      for (final mode in [speakingMode, const CapabilitySnapshot(stt: CapabilityStatus.unsupported, tts: CapabilityStatus.unsupported, mode: TripVoiceMode.manualOnly, voiceInputEnabled: false)]) {
        final platform = FakePlatform();
        final transport = HoldTransport()..next = ok([card('CP')]);
        final controller = await boot(transport, platform, ScriptDelay());
        controller.tsm.applyVoiceCapability(mode, false);
        controller.lockDestinationAt('A', destLat, destLng);
        controller.onFix(_fixAt(destLat + delta1000 * 2, 500));
        await controller.requestRecommendation();
        expect(controller.snapshot.cardsOpen, isTrue, reason: '$mode');
        expect(platform.spoken, mode.mode == TripVoiceMode.manualOnly ? isEmpty : ['去CP']);
      }
    });

    test('P3-C4 destination change collapses cards', () async {
      final transport = HoldTransport()..next = ok([card('CP')]);
      final controller = await boot(transport, FakePlatform(), ScriptDelay());
      controller.lockDestinationAt('A', destLat, destLng);
      await controller.requestRecommendation();
      controller.toggleCards();
      controller.lockDestinationAt('B', destLat + delta1000 * 3, destLng);
      expect(controller.snapshot.cardsOpen, isFalse);
    });
  });
}

HttpResult _committed(int revision) =>
    HttpResult(statusCode: 200, bodyJson: '{"status":"COMMITTED","contract_version":2,"lock_revision":$revision,"destination_epoch":1}');

Map<String, Object?> _lastBody(HoldTransport t, String path) {
  final i = t.calls.lastIndexWhere((p) => p == path);
  return jsonDecode(t.bodies[i]) as Map<String, Object?>;
}

/// 已確認目的地 A、拎咗卡、commit 成功（lock_revision＝[revision]）。
Future<TripController> _committedAtA(HoldTransport transport, FakePlatform platform, {int revision = 3, FakeNavEngine? nav, TripNetwork? network}) async {
  transport.next = ok([card('CP_A')]);
  final controller = await boot(transport, platform, ScriptDelay(), navEngine: nav, network: network);
  controller.lockDestinationAt('A', destLat, destLng);
  controller.onFix(_fixAt(destLat + delta1000 * 0.5, 1000));
  await Future<void>.delayed(Duration.zero);
  transport.next = _committed(revision);
  await controller.confirmCandidate(0);
  expect(controller.state, TripState.navToGate);
  expect(controller.heldLock?.lockRevision, revision);
  return controller;
}

void _phase3bReleaseAndVoiceTests() {
  group('Phase3b 改目的地釋放舊鎖', () {
    test('P3b-R1 commit/recommend carry epoch + idempotency key', () async {
      final transport = HoldTransport();
      final controller = await _committedAtA(transport, FakePlatform());
      final rec = _lastBody(transport, ApiClient.RECOMMEND);
      final commit = _lastBody(transport, ApiClient.COMMIT);
      expect(rec['destination_epoch'], controller.destinationEpoch);
      expect(commit['destination_epoch'], controller.destinationEpoch);
      expect(rec.containsKey('session_started_at'), isTrue);
      expect(commit['idempotency_key'], matches(RegExp('^c${controller.destinationEpoch}-CP_A')));
    });

    test('P3b-R2 change to a far destination (>1 km) releases immediately with revision; trip stays active', () async {
      final transport = HoldTransport();
      final nav = FakeNavEngine();
      final controller = await _committedAtA(transport, FakePlatform(), nav: nav);
      final recommendsBefore = _recommendCalls(transport);
      transport.next = const HttpResult(statusCode: 200, bodyJson: '{"status":"ACTIVE","released":true,"result":"RELEASED"}');
      controller.lockDestinationAt('遠方', destLat + delta1000 * 5, destLng);
      await Future<void>.delayed(Duration.zero);
      final release = _lastBody(transport, ApiClient.RELEASE);
      expect(release['reason'], 'DESTINATION_CHANGED');
      expect(release['lock_revision'], 3);
      expect(release['new_destination_epoch'], controller.destinationEpoch);
      expect(controller.snapshot.lockRelease, LockReleaseState.none);
      expect(controller.heldLock, isNull);
      expect(controller.sessionId, 'sess_test');
      expect(controller.state, TripState.cruising);
      expect(nav.activeTitle, '遠方');
      expect(_recommendCalls(transport), recommendsBefore, reason: '新目的地 >1 km：唔即刻 recommend');
    });

    test('P3b-R3 release network failure keeps retry state; reconnect retries; success clears', () async {
      final transport = HoldTransport();
      final network = TripNetwork(true);
      final controller = await _committedAtA(transport, FakePlatform(), network: network);
      transport.next = const HttpResult(statusCode: 0, networkError: true, timedOut: true);
      controller.lockDestinationAt('B', destLat + delta1000 * 3, destLng);
      await Future<void>.delayed(Duration.zero);
      expect(controller.snapshot.lockRelease, LockReleaseState.failed);
      expect(controller.pendingLockRelease?.lockRevision, 3);
      expect(controller.state, isNot(TripState.greyBreathing), reason: '釋放失敗唔扮網絡全斷，亦唔假裝成功');
      transport.next = const HttpResult(statusCode: 200, bodyJson: '{"status":"ACTIVE","released":true}');
      await controller.onNetworkChanged(true);
      expect(controller.snapshot.lockRelease, LockReleaseState.none);
      expect(transport.calls.where((p) => p == ApiClient.RELEASE), hasLength(2));
    });

    test('P3b-R4 old backend: 422 on DESTINATION_CHANGED is reported unsupported, not retried as success', () async {
      final transport = HoldTransport();
      final controller = await _committedAtA(transport, FakePlatform());
      transport.next = const HttpResult(statusCode: 422, bodyJson: '{"detail":"reason must be PARKED_CONFIRMED"}');
      controller.lockDestinationAt('B', destLat + delta1000 * 3, destLng);
      await Future<void>.delayed(Duration.zero);
      expect(controller.snapshot.lockRelease, LockReleaseState.unsupported);
      await controller.retryLockRelease();
      expect(transport.calls.where((p) => p == ApiClient.RELEASE), hasLength(1));
    });

    test('P3b-R5 legacy commit response (no lock_revision) → change marks unsupported without sending', () async {
      final transport = HoldTransport()..next = ok([card('CP_A')]);
      final controller = await boot(transport, FakePlatform(), ScriptDelay());
      controller.lockDestinationAt('A', destLat, destLng);
      await controller.requestRecommendation();
      transport.next = const HttpResult(statusCode: 200, bodyJson: '{"status":"COMMITTED","session_id":"sess_test"}');
      await controller.confirmCandidate(0);
      controller.lockDestinationAt('B', destLat + delta1000 * 3, destLng);
      expect(controller.snapshot.lockRelease, LockReleaseState.unsupported);
      expect(transport.calls.where((p) => p == ApiClient.RELEASE), isEmpty);
    });

    test('P3b-R6 409 STALE_EPOCH / SESSION_CLOSED on release clears (superseded / ended)', () async {
      for (final status in ['STALE_EPOCH', 'SESSION_CLOSED']) {
        final transport = HoldTransport();
        final controller = await _committedAtA(transport, FakePlatform());
        transport.next = HttpResult(statusCode: 409, bodyJson: '{"status":"$status"}');
        controller.lockDestinationAt('B', destLat + delta1000 * 3, destLng);
        await Future<void>.delayed(Duration.zero);
        expect(controller.snapshot.lockRelease, LockReleaseState.none, reason: status);
      }
    });

    test('P3b-R7 rapid A→B→A while release in flight: newest epoch is sent after the first returns', () async {
      final transport = HoldTransport();
      final controller = await _committedAtA(transport, FakePlatform());
      transport.next = null;
      controller.lockDestinationAt('B', destLat + delta1000 * 3, destLng);
      final epochB = controller.destinationEpoch;
      controller.lockDestinationAt('A', destLat, destLng);
      final epochA2 = controller.destinationEpoch;
      expect(transport.calls.where((p) => p == ApiClient.RELEASE), hasLength(1), reason: '同一時間只送一筆');
      expect(_lastBody(transport, ApiClient.RELEASE)['new_destination_epoch'], epochB);
      transport.next = const HttpResult(statusCode: 200, bodyJson: '{"status":"ACTIVE"}');
      transport.waiting.first.complete(const HttpResult(statusCode: 200, bodyJson: '{"status":"ACTIVE"}'));
      await Future<void>.delayed(Duration.zero);
      await Future<void>.delayed(Duration.zero);
      await Future<void>.delayed(Duration.zero);
      final last = _lastBody(transport, ApiClient.RELEASE);
      expect(last['new_destination_epoch'], epochA2);
      expect(last['lock_revision'], 3);
      expect(controller.snapshot.lockRelease, LockReleaseState.none);
    });

    test('P3b-R8 commit 409 STALE_EPOCH/SESSION_CLOSED is not treated as LOCK_FULL', () async {
      final platform = FakePlatform();
      final transport = HoldTransport()..next = ok([card('CP_A')]);
      final controller = await boot(transport, platform, ScriptDelay());
      controller.lockDestinationAt('A', destLat, destLng);
      await controller.requestRecommendation();
      transport.next = const HttpResult(statusCode: 409, bodyJson: '{"status":"SESSION_CLOSED"}');
      await controller.confirmCandidate(0);
      expect(controller.fullCarparkIds, isEmpty);
      expect(controller.lastCommitConflict, 'SESSION_CLOSED');
      expect(controller.state, isNot(TripState.navToGate));
    });

    test('P3b-R9 abort sends pending DESTINATION_CHANGED then terminal USER_ABORTED with revision', () async {
      final transport = HoldTransport();
      final controller = await _committedAtA(transport, FakePlatform());
      transport.next = const HttpResult(statusCode: 200, bodyJson: '{"status":"USER_ABORTED"}');
      await controller.abortTripByUser();
      await Future<void>.delayed(Duration.zero);
      final release = _lastBody(transport, ApiClient.RELEASE);
      expect(release['reason'], 'USER_ABORTED');
      expect(release['lock_revision'], 3);
      expect(controller.heldLock, isNull);
    });

    test('P3b-R10 parked release carries lock_revision', () async {
      final delay = ScriptDelay();
      final transport = HoldTransport()..next = ok([card('CP_A')]);
      final controller = await boot(transport, FakePlatform(), delay);
      controller.lockDestinationAt('A', destLat, destLng);
      await controller.requestRecommendation();
      transport.next = _committed(5);
      await controller.confirmCandidate(0);
      controller.onFix(_fixAt(destLat + delta1000 * 0.05, 1000, speed: 1));
      final pending = controller.onEngineSignal(EngineSignal.power, disconnected: true);
      await Future<void>.delayed(Duration.zero);
      delay.finishNext();
      await Future<void>.delayed(Duration.zero);
      final release = _lastBody(transport, ApiClient.RELEASE);
      expect(release['reason'], 'PARKED_CONFIRMED');
      expect(release['lock_revision'], 5);
      final record = controller.store.find(release['session_id'] as String)!;
      expect((record.contractVersion, record.lockRevision, record.destinationEpoch), (2, 5, controller.destinationEpoch),
          reason: 'outbox 事件身份喺 engine-off 嗰刻鎖低');
      delay.finishNext();
      expect(await pending, 'PARKED');
    });
  });

  group('Phase3b 語音指令派發', () {
    Future<TripController> voiceTrip(FakePlatform platform, CapabilitySnapshot voice, {List<RecommendationCard>? withCards}) async {
      final transport = HoldTransport()..next = ok(withCards ?? [card('CP')]);
      final controller = await boot(transport, platform, ScriptDelay(), startTrip: false);
      await controller.start(StartupEntry.a2, voice: voice);
      controller.lockDestinationAt('A', destLat, destLng);
      controller.onFix(_fixAt(destLat + delta1000 * 2, 500));
      return controller;
    }

    test('P3b-V1 full voice: no always-on mic at start; one-shot after P; 詳細啲 opens cards and reads lead', () async {
      final platform = FakePlatform();
      final controller = await voiceTrip(platform, _fullVoice);
      expect(platform.listening, isEmpty, reason: 'Android SpeechRecognizer：唔長開熱詞');
      expect(controller.onVoiceUtterance(code: 'SHOW_DETAILS', text: '詳細啲'), 'NO_CARDS');
      await controller.requestRecommendation();
      expect(platform.listening, isEmpty, reason: 'TTS 未講完唔開咪');
      expect(controller.listenPendingOnSpeech, isTrue);
      controller.onSpeechDone(platform.utteranceIds.last!, played: true);
      expect(platform.listening, ['followup'], reason: 'TTS 講完（onSpeechDone）先 one-shot 聽追問');
      expect(controller.snapshot.cardsOpen, isFalse);
      expect(controller.onVoiceUtterance(code: 'SHOW_DETAILS', text: '詳細啲'), 'SHOW_DETAILS');
      expect(controller.snapshot.cardsOpen, isTrue);
      expect(platform.spoken.last, '去CP');
      expect(controller.onVoiceUtterance(code: 'CANCEL', text: '唔使'), 'CANCEL');
      expect(controller.snapshot.cardsOpen, isFalse);
    });

    test('P3b-V2 STT unavailable / manual: utterances ignored, mic never opened, P still opens cards', () async {
      for (final voice in [speakingMode, const CapabilitySnapshot(stt: CapabilityStatus.unsupported, tts: CapabilityStatus.unsupported, mode: TripVoiceMode.manualOnly, voiceInputEnabled: false)]) {
        final platform = FakePlatform();
        final controller = await voiceTrip(platform, voice);
        await controller.requestRecommendation();
        expect(platform.listening, isEmpty);
        expect(controller.onVoiceUtterance(code: 'SHOW_DETAILS', text: '詳細啲'), 'IGNORED_NO_STT');
        expect(controller.snapshot.cardsOpen, isTrue);
      }
    });

    test('P3b-V3 low confidence / self-hearing ignored; NEXT_OPTION reported unsupported; other codes dispatch', () async {
      final platform = FakePlatform();
      final controller = await voiceTrip(platform, _fullVoice);
      await controller.requestRecommendation();
      expect(controller.onVoiceUtterance(code: 'SHOW_DETAILS', lowConfidence: true), 'IGNORED_LOW_CONFIDENCE');
      expect(controller.onVoiceUtterance(code: 'SHOW_DETAILS', blockedAsSelfHearing: true), 'IGNORED_LOW_CONFIDENCE');
      expect(controller.onVoiceUtterance(code: 'NEXT_OPTION'), 'UNSUPPORTED');
      expect(controller.onVoiceUtterance(code: 'CONFIRM_TARGET'), 'IGNORED_UNCONFIRMED');
      expect(controller.onVoiceUtterance(code: 'SET_DESTINATION'), 'IGNORED_NO_DESTINATION');
      expect(controller.onVoiceUtterance(code: 'FIND_PARKING'), 'FIND_PARKING');
      expect(controller.snapshot.cardsOpen, isFalse);
    });

    test('P3b-V4 lifecycle: not running ignores; abort stops mic', () async {
      final platform = FakePlatform();
      final controller = await voiceTrip(platform, _fullVoice);
      await controller.abortTripByUser();
      expect(platform.listening.last, 'stop');
      expect(controller.onVoiceUtterance(code: 'SHOW_DETAILS'), 'IGNORED_NOT_RUNNING');
    });
  });

  group('Phase3d typed 伺服器回應', () {
    test('P3d-S1 commit CAPACITY_UNKNOWN: card excluded, status shown, no auto second commit, not grey', () async {
      final transport = HoldTransport()..next = ok([card('CP_A'), card('CP_B')]);
      final controller = await boot(transport, FakePlatform(), ScriptDelay());
      controller.lockDestinationAt('A', destLat, destLng);
      await controller.requestRecommendation();
      transport.next = const HttpResult(statusCode: 409, bodyJson: '{"status":"CAPACITY_UNKNOWN"}');
      await controller.confirmCandidate(0);
      expect(controller.fullCarparkIds, contains('CP_A'));
      expect(controller.snapshot.serverIssue?.kind, ServerIssueKind.capacityUnavailable);
      expect(transport.calls.where((p) => p == ApiClient.COMMIT), hasLength(1), reason: '唔自動 commit 第二個');
      expect(controller.state, isNot(TripState.navToGate));
      expect(controller.state, isNot(TripState.greyBreathing));
    });

    test('P3d-S2 recommend SESSION_START_EXPIRED: action status, same session, no grey, no auto new session', () async {
      final transport = HoldTransport()..next = const HttpResult(statusCode: 409, bodyJson: '{"status":"SESSION_START_EXPIRED"}');
      final controller = await boot(transport, FakePlatform(), ScriptDelay());
      final session = controller.sessionId;
      controller.lockDestinationAt('A', destLat, destLng);
      await controller.requestRecommendation();
      expect(controller.snapshot.serverIssue?.kind, ServerIssueKind.sessionInvalid);
      expect(controller.sessionId, session);
      expect(controller.state, isNot(TripState.greyBreathing));
      expect(transport.calls.where((p) => p == ApiClient.RECOMMEND), hasLength(1));
      transport.next = ok([card('CP')]);
      await controller.requestRecommendation();
      expect(controller.snapshot.serverIssue, isNull, reason: '成功後清走');
    });

    test('P3d-S3 LOCK_FULL keeps existing next-card behaviour without auto-commit', () async {
      final transport = HoldTransport()..next = ok([card('CP_A'), card('CP_B')]);
      final controller = await boot(transport, FakePlatform(), ScriptDelay());
      controller.lockDestinationAt('A', destLat, destLng);
      await controller.requestRecommendation();
      transport.next = const HttpResult(statusCode: 409, bodyJson: '{"status":"LOCK_FULL","spoken_cue":"滿咗"}');
      await controller.confirmCandidate(0);
      expect(controller.fullCarparkIds, ['CP_A']);
      expect(controller.snapshot.serverIssue, isNull);
      expect(transport.calls.where((p) => p == ApiClient.COMMIT), hasLength(1));
    });
  });

  group('Phase3d TTS 完結先開咪', () {
    Future<(TripController, FakePlatform)> spoken() async {
      final platform = FakePlatform();
      final transport = HoldTransport()..next = ok([card('CP')]);
      final controller = await boot(transport, platform, ScriptDelay(), startTrip: false);
      await controller.start(StartupEntry.a2, voice: _fullVoice);
      controller.lockDestinationAt('A', destLat, destLng);
      controller.onFix(_fixAt(destLat + delta1000 * 2, 500));
      await controller.requestRecommendation();
      return (controller, platform);
    }

    test('P3d-T1 TTS error/stop → no mic, cards open (manual fallback)', () async {
      final (controller, platform) = await spoken();
      controller.onSpeechDone(platform.utteranceIds.last!, played: false);
      expect(platform.listening, isEmpty);
      expect(controller.snapshot.cardsOpen, isTrue);
    });

    test('P3d-T2 destination change while TTS pending: late completion never opens mic', () async {
      final (controller, platform) = await spoken();
      final id = platform.utteranceIds.last!;
      controller.lockDestinationAt('B', destLat + delta1000 * 3, destLng);
      controller.onSpeechDone(id, played: true);
      expect(platform.listening, isEmpty);
    });

    test('P3d-T3 wrong/duplicate id ignored; a newer utterance supersedes; abort clears', () async {
      final (controller, platform) = await spoken();
      final id = platform.utteranceIds.last!;
      controller.onSpeechDone('other', played: true);
      expect(platform.listening, isEmpty);
      controller.showCardDetails(); // 新一句（driverAsked）取代「講完開咪」
      controller.onSpeechDone(id, played: true);
      expect(platform.listening, isEmpty);
      await controller.requestRecommendation();
      final id2 = platform.utteranceIds.last!;
      controller.onSpeechDone(id2, played: true);
      controller.onSpeechDone(id2, played: true);
      expect(platform.listening, ['followup'], reason: '重複完成只開一次');
      await controller.requestRecommendation();
      final id3 = platform.utteranceIds.last!;
      await controller.abortTripByUser();
      controller.onSpeechDone(id3, played: true);
      expect(platform.listening.where((e) => e == 'followup'), hasLength(1), reason: '停程後唔開咪');
    });
  });

  group('Phase3c 原生語音錯誤', () {
    test('P3c-V6 permanent NO_PERMISSION blocks voice for the trip and falls back to manual cards', () async {
      final platform = FakePlatform();
      final transport = HoldTransport()..next = ok([card('CP')]);
      final controller = await boot(transport, platform, ScriptDelay(), startTrip: false);
      await controller.start(StartupEntry.a2, voice: _fullVoice);
      controller.lockDestinationAt('A', destLat, destLng);
      controller.onFix(_fixAt(destLat + delta1000 * 2, 500));
      controller.onVoiceError('NO_MATCH');
      expect(controller.snapshot.voiceInputBlocked, isNull, reason: '暫時性錯誤唔停用');
      controller.onVoiceError('NO_PERMISSION', permanent: true);
      expect(controller.snapshot.voiceInputBlocked, 'NO_PERMISSION');
      await controller.requestRecommendation();
      expect(controller.snapshot.cardsOpen, isTrue, reason: '語音停用 → P 直接出卡');
      expect(platform.listening, isEmpty, reason: '唔再開咪');
      expect(controller.onVoiceUtterance(code: 'SHOW_DETAILS'), 'IGNORED_NO_STT');
      await controller.abortTripByUser();
      await controller.start(StartupEntry.a2, voice: _fullVoice);
      expect(controller.snapshot.voiceInputBlocked, isNull, reason: '新行程重設');
    });
  });

  group('Review t1235 commit in flight at abort', () {
    test('R1 abort while commit in flight: late success is released with its revision (no orphan lock)', () async {
      final transport = HoldTransport()..next = ok([card('CP_A')]);
      final controller = await boot(transport, FakePlatform(), ScriptDelay());
      controller.lockDestinationAt('A', destLat, destLng);
      await controller.requestRecommendation();
      transport.next = null; // hold the commit
      final commit = controller.confirmCandidate(0);
      await Future<void>.delayed(Duration.zero);
      expect(transport.calls.last, ApiClient.COMMIT);
      await controller.abortTripByUser();
      transport.next = const HttpResult(statusCode: 200, bodyJson: '{"status":"COMMITTED","lock_revision":5}');
      transport.waiting.first.complete(transport.next!);
      await commit;
      await Future<void>.delayed(Duration.zero);
      final releases = [
        for (var i = 0; i < transport.calls.length; i++)
          if (transport.calls[i] == ApiClient.RELEASE) transport.bodies[i]
      ];
      expect(releases.where((b) => b.contains('USER_ABORTED') && b.contains('"lock_revision":5')), hasLength(1));
      expect(controller.state, isNot(TripState.navToGate));
    });
  });

  group('t1245 導航目標：目的地先，commit 成功先改車場', () {
    test('G1 去 navigates to the user destination immediately, before any recommend (>1 km)', () async {
      final nav = FakeNavEngine();
      final transport = HoldTransport()..next = ok([card('CP_LANGHAM')]);
      final controller = await boot(transport, FakePlatform(), ScriptDelay(), navEngine: nav);
      controller.onFix(_fixAt(destLat + delta1000 * 2.2, 1000));
      controller.lockDestinationAt('朗豪坊商場', destLat, destLng);
      await Future<void>.delayed(Duration.zero);
      expect(nav.activeTitle, '朗豪坊商場');
      expect((nav.activeLat, nav.activeLng), (destLat, destLng));
      expect(controller.navTarget?.state, NavTargetState.guiding);
      expect(_recommendCalls(transport), 0, reason: '2.2 km：未到 1 km 唔 recommend');
    });

    test('G2 refused / failed / unknown commits keep the destination route', () async {
      final nav = FakeNavEngine();
      final transport = HoldTransport()..next = ok([card('CP_A'), card('CP_B')]);
      final controller = await boot(transport, FakePlatform(), ScriptDelay(), navEngine: nav);
      controller.lockDestinationAt('A', destLat, destLng);
      await controller.requestRecommendation();
      for (final r in const [
        HttpResult(statusCode: 409, bodyJson: '{"status":"LOCK_FULL"}'),
        HttpResult(statusCode: 409, bodyJson: '{"status":"CAPACITY_UNKNOWN"}'),
        HttpResult(statusCode: 0, networkError: true, timedOut: true),
      ]) {
        transport.next = r;
        await controller.confirmCandidate(1);
        await Future<void>.delayed(Duration.zero);
        expect(nav.activeTitle, 'A');
        expect(controller.navTarget?.kind, NavTargetKind.destination);
      }
      expect(nav.calls.where((c) => c == 'startGuidance'), hasLength(1));
    });

    test('G3 A then B while A is still starting: A is never issued late, B wins', () async {
      final nav = _SlowNav();
      final controller = await boot(HoldTransport(), FakePlatform(), ScriptDelay(), navEngine: nav);
      controller.lockDestinationAt('A', destLat, destLng);
      await Future<void>.delayed(Duration.zero);
      controller.lockDestinationAt('B', destLat + delta1000 * 3, destLng);
      controller.lockDestinationAt('C', destLat + delta1000 * 4, destLng);
      nav.gates.first.complete(true); // A finishes late
      await Future<void>.delayed(Duration.zero);
      await Future<void>.delayed(Duration.zero);
      expect(nav.titles, ['A', 'C'], reason: 'B 被 C 取代前未發出；之後唔會再發 A／B');
      expect(controller.navTarget?.title, 'C');
      expect(controller.navTarget?.state, NavTargetState.starting, reason: 'C 未回覆唔可以話已開始');
      nav.gates.last.complete(false);
      await Future<void>.delayed(Duration.zero);
      expect(controller.navTarget?.state, NavTargetState.failed);
    });

    test('G4 abort stops guidance and clears the target; a new trip starts clean', () async {
      final nav = FakeNavEngine();
      final controller = await boot(HoldTransport(), FakePlatform(), ScriptDelay(), navEngine: nav);
      controller.lockDestinationAt('A', destLat, destLng);
      await Future<void>.delayed(Duration.zero);
      await controller.abortTripByUser();
      expect(nav.guidanceRunning, isFalse);
      expect(controller.navTarget, isNull);
    });

    test('G5 on-site (現在位置) does not start a zero-distance route', () async {
      final nav = FakeNavEngine();
      final controller = await boot(HoldTransport(), FakePlatform(), ScriptDelay(), navEngine: nav);
      controller.lockDestinationAt(TripController.onSiteDestinationName, destLat, destLng);
      await Future<void>.delayed(Duration.zero);
      expect(nav.calls.where((c) => c == 'startGuidance'), isEmpty);
    });

    test('G6 same destination confirmed again does not wipe the car-park route', () async {
      final nav = FakeNavEngine();
      final transport = HoldTransport()..next = ok([card('CP_LANGHAM', '朗豪坊商場停車場')]);
      final controller = await boot(transport, FakePlatform(), ScriptDelay(), navEngine: nav);
      controller.lockDestinationAt('朗豪坊', destLat, destLng);
      await controller.requestRecommendation();
      await controller.confirmCandidate(0);
      controller.lockDestinationAt('朗豪坊', destLat, destLng);
      await Future<void>.delayed(Duration.zero);
      expect(nav.activeTitle, '朗豪坊商場停車場');
    });
  });
}

class _SlowNav extends FakeNavEngine {
  final gates = <Completer<bool>>[];
  final titles = <String>[];

  @override
  Future<bool> startGuidanceTo({required String title, required double lat, required double lng}) {
    titles.add(title);
    calls.add('startGuidance');
    final gate = Completer<bool>();
    gates.add(gate);
    return gate.future;
  }
}
