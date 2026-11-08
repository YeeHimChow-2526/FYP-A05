import 'package:copilot/core/constants/app_config.dart';
import 'package:copilot/core/nav/fake_nav_engine.dart';
import 'package:copilot/core/net/api_client.dart';
import 'package:copilot/core/net/api_transport.dart';
import 'package:copilot/core/net/http_result.dart';
import 'package:copilot/core/net/recommendation_card.dart';
import 'package:copilot/core/trip/prefs_port.dart';
import 'package:copilot/core/trip/sync_api.dart';
import 'package:copilot/core/trip/today_trip_store.dart';
import 'package:copilot/core/trip/today_trip_sync.dart';
import 'package:copilot/core/trip/trip_controller.dart';
import 'package:copilot/core/trip/trip_ports.dart';
import 'package:copilot/core/trip/trip_types.dart';
import 'package:copilot/features/nav_shell/nav_shell_screen.dart';
import 'package:copilot/features/nav_shell/recommend_cards_strip.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

RecommendationCard _card() => const RecommendationCard(
  carparkId: 'CP',
  name: '朗豪坊',
  type: 'INDOOR',
  vacancies: 12,
  distanceMeters: 180,
  feeSummary: '首小時 32',
  spokenCue: '去',
  isStale: false,
);

class _Platform implements TripPlatform {
  @override
  Future<bool> startForeground(StartupEntry entry) async => true;
  @override
  Future<void> stopForeground(String reason) async {}
  @override
  void applySamplingTier(SamplingTier tier) {}
  @override
  void setNotificationText(String text) {}
  @override
  void speak(String text, {String? utteranceId}) {}
  @override
  void interruptSpeech() {}
  @override
  void launchGateNavigation(String label, {double? lat, double? lng}) {}
  @override
  void startListening({bool hotword = false}) {}
  @override
  void stopListening() {}
}

class _NoSync implements SyncApi {
  @override
  Future<CallOutcome> syncDriverConfirmed(
    String sessionId,
    int timestampSec, {
    ParkedEventIdentity identity = ParkedEventIdentity.legacy,
  }) async => const CallOutcome(
    succeeded: false,
    httpStatus: 500,
    spokenCues: [],
    attempts: 1,
    waitIntervalsMs: [],
    shouldTurnGrey: false,
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
  }) async => const CallOutcome(
    succeeded: false,
    httpStatus: 500,
    spokenCues: [],
    attempts: 1,
    waitIntervalsMs: [],
    shouldTurnGrey: false,
    shouldTurnReady: false,
  );
}

class _RecordingController extends TripController {
  _RecordingController()
    : super(
        platform: _Platform(),
        api: ApiClient(_DeadTransport(), sleeper: (_) async {}),
        store: TodayTripStore(
          MemoryPrefsPort(),
          hktDateOf: (_) => '2026-10-26',
        ),
        sync: TodayTripSync(
          TodayTripStore(MemoryPrefsPort()),
          _NoSync(),
          _Online(),
        ),
        nowMs: () => 1,
        delay: (_) async {},
      );

  final submitted = <String>[];

  @override
  Future<void> submitDestinationText(String raw) async {
    submitted.add(raw);
  }
}

class _DeadTransport implements ApiTransport {
  @override
  Future<HttpResult> post(String path, String bodyJson) async =>
      const HttpResult(statusCode: 500);
}

class _Online implements NetworkStatusProvider {
  @override
  bool isOnline() => true;
}

void main() {
  navFailedBannerTests();
  testWidgets('N-T5 cards strip shows a card and confirm fires', (
    tester,
  ) async {
    var confirmed = -1;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: RecommendCardsStrip(
            cards: [_card()],
            onConfirm: (index) => confirmed = index,
          ),
        ),
      ),
    );
    expect(find.text('朗豪坊'), findsOneWidget);
    expect(find.text('空位 12'), findsOneWidget);
    expect(find.text('180 m'), findsOneWidget);
    expect(find.text('首小時 32'), findsOneWidget);
    await tester.tap(find.text('確認去呢個'));
    expect(confirmed, 0);
  });

  testWidgets('N-T6 destination submit and fake nav view', (tester) async {
    final controller = _RecordingController();
    final nav = FakeNavEngine();
    await tester.pumpWidget(
      MaterialApp(
        home: NavShellScreen(controller: controller, navEngine: nav),
      ),
    );
    expect(find.byKey(const Key('fake_nav_view')), findsOneWidget);
    await tester.enterText(find.byKey(const Key('destination_field')), '朗豪坊');
    await tester.tap(find.byKey(const Key('destination_submit')));
    await tester.pump();
    expect(controller.submitted, ['朗豪坊']);
  });

  testWidgets('F-T2 in-trip circular chrome has no label bar', (tester) async {
    final controller = _RecordingController();
    final nav = FakeNavEngine();
    await tester.pumpWidget(
      MaterialApp(
        home: NavShellScreen(controller: controller, navEngine: nav),
      ),
    );
    expect(find.byKey(const Key('pre_trip_bottom_bar')), findsNothing);
    expect(find.byKey(const Key('chrome_park')), findsOneWidget);
    expect(find.byKey(const Key('chrome_exit')), findsOneWidget);
    expect(find.text('羅盤'), findsNothing);
    expect(find.text('搵位'), findsNothing);
    expect(find.text('車場'), findsNothing);
    expect(find.text('結束'), findsNothing);
  });

  testWidgets('F-T4 circular chrome sits above the native ETA footer slot', (
    tester,
  ) async {
    final controller = _RecordingController();
    final nav = FakeNavEngine();
    await tester.pumpWidget(
      MaterialApp(
        home: NavShellScreen(controller: controller, navEngine: nav),
      ),
    );
    await tester.pump();
    final clearance = find.byKey(const Key('nav_sdk_footer_clearance'));
    expect(
      tester.getSize(clearance).height,
      AppConfig.inTripNavFooterClearance,
    );
    expect(
      tester.getRect(find.byKey(const Key('chrome_park'))).bottom,
      lessThanOrEqualTo(tester.getRect(clearance).top),
    );
    expect(
      tester.getRect(find.byKey(const Key('chrome_exit'))).bottom,
      lessThanOrEqualTo(tester.getRect(clearance).top),
    );
    final closed = tester
        .getSize(find.byKey(const Key('in_trip_bottom_stack')))
        .height;
    expect(nav.mapPadding.bottom, closed);
    controller.cards.add(_card());
    controller.toggleCards();
    await tester.pump();
    final opened = tester
        .getSize(find.byKey(const Key('in_trip_bottom_stack')))
        .height;
    expect(opened, greaterThan(closed));
    expect(nav.mapPadding.bottom, opened);
    expect(
      tester.getRect(find.byKey(const Key('chrome_park'))).bottom,
      lessThanOrEqualTo(tester.getRect(clearance).top),
    );
  });

  testWidgets('F-T3 recommend strip hidden until cards exist', (tester) async {
    final controller = _RecordingController();
    final nav = FakeNavEngine();
    await tester.pumpWidget(
      MaterialApp(
        home: NavShellScreen(controller: controller, navEngine: nav),
      ),
    );
    expect(find.byKey(const Key('recommend_cards_strip')), findsNothing);
    expect(find.byType(RecommendCardsStrip), findsNothing);
  });

  testWidgets(
    'F-T3b cards default collapsed; 車場卡 button opens and closes strip',
    (tester) async {
      final controller = _RecordingController();
      controller.cards.add(_card());
      final nav = FakeNavEngine();
      await tester.pumpWidget(
        MaterialApp(
          home: NavShellScreen(controller: controller, navEngine: nav),
        ),
      );
      expect(
        find.byKey(const Key('recommend_cards_strip')),
        findsNothing,
        reason: 'Phase 3：卡片預設收起，唔自動彈',
      );
      await tester.tap(find.byKey(const Key('chrome_cards')));
      await tester.pump();
      expect(find.byKey(const Key('recommend_cards_strip')), findsOneWidget);
      expect(find.byType(RecommendCardsStrip), findsOneWidget);
      await tester.tap(find.byKey(const Key('chrome_cards')));
      await tester.pump();
      expect(find.byKey(const Key('recommend_cards_strip')), findsNothing);
    },
  );

  testWidgets('third-switch consent uses the existing parking card', (tester) async {
    final controller = _RecordingController();
    controller.cards.add(_card());
    controller.pendingSwitchCarparkId = 'CP';
    controller.pendingSwitchName = '朗豪坊';
    final nav = FakeNavEngine();
    await tester.pumpWidget(
      MaterialApp(
        home: NavShellScreen(controller: controller, navEngine: nav),
      ),
    );
    await tester.tap(find.byKey(const Key('chrome_cards')));
    await tester.pump();
    expect(find.byKey(const Key('recommend_cards_strip')), findsOneWidget);
    expect(find.byKey(const Key('switch_consent_bar')), findsNothing);
    expect(find.byKey(const Key('switch_consent_confirm')), findsOneWidget);
    expect(find.byKey(const Key('switch_consent_cancel')), findsOneWidget);
    expect(find.text('確認去呢個'), findsNothing);
    expect(find.text('而家確認去朗豪坊嗎？未確認唔會轉線'), findsNothing);
  });

  testWidgets(
    'P3-UI status line separates 1 km readiness from vacancy freshness',
    (tester) async {
      final controller = _RecordingController();
      final nav = FakeNavEngine();
      await tester.pumpWidget(
        MaterialApp(
          home: NavShellScreen(controller: controller, navEngine: nav),
        ),
      );
      expect(find.text('未到 1 km 預備區｜空位：未有數據'), findsOneWidget);
      controller.cards.add(_card());
      controller.toggleCards();
      controller.toggleCards();
      await tester.pump();
      expect(
        find.text('未到 1 km 預備區｜空位：資料未更新'),
        findsOneWidget,
        reason: '有舊卡但未有新鮮結果唔可以扮有空位',
      );
    },
  );
}

class _FailedRouteNav extends FakeNavEngine {
  @override
  String? get lastRouteStatus => 'networkError';
}

void navFailedBannerTests() {
  testWidgets(
    'R6-B route failed shows visible banner with status; guiding hides it',
    (tester) async {
      final controller = _RecordingController();
      final nav = _FailedRouteNav();
      controller.navTarget = const NavTarget(
        kind: NavTargetKind.carpark,
        title: '朗豪坊停車場',
        lat: 22.31,
        lng: 114.16,
        state: NavTargetState.failed,
      );
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: InTripOverlay(controller: controller, navEngine: nav),
          ),
        ),
      );
      expect(find.byKey(const Key('route_failed_banner')), findsOneWidget);
      expect(find.textContaining('朗豪坊停車場'), findsWidgets);
      expect(find.textContaining('networkError'), findsOneWidget);
      controller.navTarget = controller.navTarget!.withState(
        NavTargetState.guiding,
      );
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: InTripOverlay(
              key: UniqueKey(),
              controller: controller,
              navEngine: nav,
            ),
          ),
        ),
      );
      expect(find.byKey(const Key('route_failed_banner')), findsNothing);
    },
  );
}
