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
  void speak(String text) {}
  @override
  void interruptSpeech() {}
  @override
  void launchGateNavigation(String label, {double? lat, double? lng}) {}
}

class _NoSync implements SyncApi {
  @override
  Future<CallOutcome> syncDriverConfirmed(String sessionId, int timestampSec) async => const CallOutcome(
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
          store: TodayTripStore(MemoryPrefsPort(), hktDateOf: (_) => '2026-10-26'),
          sync: TodayTripSync(TodayTripStore(MemoryPrefsPort()), _NoSync(), _Online()),
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
  Future<HttpResult> post(String path, String bodyJson) async => const HttpResult(statusCode: 500);
}

class _Online implements NetworkStatusProvider {
  @override
  bool isOnline() => true;
}

void main() {
  testWidgets('N-T5 cards strip shows a card and confirm fires', (tester) async {
    var confirmed = -1;
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: RecommendCardsStrip(
          cards: [_card()],
          onConfirm: (index) => confirmed = index,
        ),
      ),
    ));
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
    await tester.pumpWidget(MaterialApp(home: NavShellScreen(controller: controller, navEngine: nav)));
    expect(find.byKey(const Key('fake_nav_view')), findsOneWidget);
    await tester.enterText(find.byKey(const Key('destination_field')), '朗豪坊');
    await tester.tap(find.byKey(const Key('destination_submit')));
    await tester.pump();
    expect(controller.submitted, ['朗豪坊']);
  });
}
