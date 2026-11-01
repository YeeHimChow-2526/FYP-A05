import 'dart:async';

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
  void interruptSpeech() {}
  @override
  void launchGateNavigation(String label) => nav.add(label);
}

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

Future<TripController> boot(HoldTransport transport, FakePlatform platform, ScriptDelay delay, {PoiNameResolver? resolver, bool online = true}) async {
  final api = ApiClient(transport, sleeper: (_) async {});
  final store = TodayTripStore(MemoryPrefsPort(), hktDateOf: (_) => '2026-10-26', nowMs: () => 1_000);
  final sync = TodayTripSync(store, _NoSync(), online ? _Online() : _Offline(), nowMs: () => 1_000);
  final controller = TripController(
    platform: platform,
    api: api,
    store: store,
    sync: sync,
    nowMs: () => 5_000,
    delay: delay.call,
    resolver: resolver,
    sessionIdFactory: () => 'sess_test',
  );
  await controller.start(StartupEntry.a2);
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
    expect(transport.bodies.single, contains('"origin_lat":0.0'));
    expect(transport.bodies.single, contains('"preference":"SMART_HYBRID"'));
  });

  test('C5 background failure is silent then success leaves grey', () async {
    final transport = HoldTransport()..next = const HttpResult(statusCode: 0, networkError: true, timedOut: true);
    final platform = FakePlatform();
    final controller = await boot(transport, platform, ScriptDelay());
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
    controller.onFix(LocFix(lat: destLat + delta1000 * 0.2, lng: destLng, accuracyM: 10, speedKmh: 10, timestampMs: 2000));
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
    controller.lockDestinationAt('甲', destLat, destLng);
    controller.onFix(LocFix(lat: destLat + delta1000 * 0.4, lng: destLng, accuracyM: 10, speedKmh: 10, timestampMs: 1000));
    await Future<void>.delayed(Duration.zero);
    controller.lockDestinationAt('乙', destLat, destLng);
    transport.waiting.first.complete(ok([card('OLD')]));
    await Future<void>.delayed(Duration.zero);
    expect(controller.cards, isEmpty);
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

  test('C14 two signals shut down once and C15 drops fade results', () async {
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

  test('C16 grey retry is single flight', () async {
    final transport = HoldTransport();
    final controller = await boot(transport, FakePlatform(), ScriptDelay());
    final first = controller.requestRecommendation();
    final second = controller.requestRecommendation();
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
