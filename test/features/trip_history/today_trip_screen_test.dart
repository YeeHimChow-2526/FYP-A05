import 'package:copilot/core/net/api_client.dart';
import 'package:copilot/core/trip/prefs_port.dart';
import 'package:copilot/core/trip/sync_api.dart';
import 'package:copilot/core/trip/today_trip_store.dart';
import 'package:copilot/core/trip/today_trip_sync.dart';
import 'package:copilot/features/trip_history/today_trip_screen.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

class _Online implements NetworkStatusProvider {
  @override
  bool isOnline() => true;
}

class _RecordingSync implements SyncApi {
  final reports = <Map<String, Object?>>[];

  @override
  Future<CallOutcome> syncDriverConfirmed(String sessionId, int timestampSec, {ParkedEventIdentity identity = ParkedEventIdentity.legacy}) async {
    return const CallOutcome(
      succeeded: true,
      httpStatus: 200,
      spokenCues: [],
      attempts: 1,
      waitIntervalsMs: [],
      shouldTurnGrey: false,
      shouldTurnReady: false,
    );
  }

  @override
  Future<CallOutcome> submitTripReport({
    required String sessionId,
    required String carparkId,
    required String ratingTag,
    required String perkStatusTag,
    required String perkNotes,
    required int tripTimestampSec,
  }) async {
    reports.add({
      'session_id': sessionId,
      'carpark_id': carparkId,
      'rating_tag': ratingTag,
      'perk_status_tag': perkStatusTag,
      'perk_notes': perkNotes,
      'trip_timestamp': tripTimestampSec,
    });
    return const CallOutcome(
      succeeded: true,
      httpStatus: 200,
      spokenCues: [],
      attempts: 1,
      waitIntervalsMs: [],
      shouldTurnGrey: false,
      shouldTurnReady: false,
    );
  }
}

class _FailingSync extends _RecordingSync {
  @override
  Future<CallOutcome> submitTripReport({
    required String sessionId,
    required String carparkId,
    required String ratingTag,
    required String perkStatusTag,
    required String perkNotes,
    required int tripTimestampSec,
  }) async {
    reports.add({'session_id': sessionId});
    return const CallOutcome(
      succeeded: false,
      httpStatus: 500,
      spokenCues: [],
      attempts: 1,
      waitIntervalsMs: [],
      shouldTurnGrey: false,
      shouldTurnReady: false,
    );
  }
}

TodayTripStore _store() => TodayTripStore(MemoryPrefsPort(), hktDateOf: (_) => '2026-10-26', nowMs: () => 5_000);

void main() {
  testWidgets('step34_f01_todayTripScreenShowsEmpty', (tester) async {
    await tester.pumpWidget(MaterialApp(home: TodayTripScreen(store: _store(), sync: TodayTripSync(_store(), _RecordingSync(), _Online()))));
    await tester.pumpAndSettle();
    expect(find.text('今日未有行程'), findsOneWidget);
  });

  testWidgets('step34_f02_candidateShowsConfirmCta', (tester) async {
    final store = _store();
    store.upsertCandidate('s1', 'cp1', '甲場', 1000);
    await tester.pumpWidget(MaterialApp(home: TodayTripScreen(store: store, sync: TodayTripSync(store, _RecordingSync(), _Online()))));
    await tester.pumpAndSettle();
    expect(find.textContaining('可能已泊入'), findsOneWidget);
    expect(find.text('確認已泊妥'), findsOneWidget);
  });

  testWidgets('step34_f03_tapConfirmInvokesChannel', (tester) async {
    final store = _store();
    final api = _RecordingSync();
    store.upsertCandidate('s1', 'cp1', '甲場', 1000);
    await tester.pumpWidget(MaterialApp(home: TodayTripScreen(store: store, sync: TodayTripSync(store, api, _Online(), nowMs: () => 5_000))));
    await tester.pumpAndSettle();
    await tester.tap(find.text('確認已泊妥'));
    await tester.pumpAndSettle();
    expect(store.find('s1')?.status, TodayTripStore.statusConfirmed);
  });

  testWidgets('step34_f04_confirmedShowsQuickFeedback', (tester) async {
    final store = _store();
    store.upsertCandidate('s1', 'cp1', '甲場', 2000);
    store.markConfirmed('s1', syncPending: false, atMs: 2000);
    store.markSyncSent('s1');
    await tester.pumpWidget(MaterialApp(home: TodayTripScreen(store: store, sync: TodayTripSync(store, _RecordingSync(), _Online()))));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('feedback_s1')), findsOneWidget);
    expect(find.text('GOOD'), findsOneWidget);
    expect(find.text('NONE'), findsOneWidget);
    expect(find.text('提交反饋'), findsOneWidget);
  });

  testWidgets('step34_f05_submitBuildsPayload', (tester) async {
    final store = _store();
    final api = _RecordingSync();
    store.upsertCandidate('s1', 'cp1', '甲場', 3000);
    store.markConfirmed('s1', syncPending: false, atMs: 3000);
    store.markSyncSent('s1');
    await tester.pumpWidget(MaterialApp(home: TodayTripScreen(store: store, sync: TodayTripSync(store, api, _Online(), nowMs: () => 5_000))));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('rating_GOOD')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('perk_NONE')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('submit_feedback')));
    await tester.pumpAndSettle();
    expect(api.reports.single['rating_tag'], 'GOOD');
    expect(api.reports.single['perk_status_tag'], 'NONE');
    expect(api.reports.single['session_id'], 's1');
    expect(api.reports.single['carpark_id'], 'cp1');
    expect(api.reports.single['trip_timestamp'], 3);
    expect(api.reports.single.containsKey('photo_url'), isFalse);
  });

  testWidgets('step34_f06_onTodayTripsChangedRefreshes', (tester) async {
    final store = _store();
    await tester.pumpWidget(MaterialApp(home: TodayTripScreen(store: store, sync: TodayTripSync(store, _RecordingSync(), _Online()))));
    await tester.pumpAndSettle();
    expect(find.text('今日未有行程'), findsOneWidget);
    store.upsertCandidate('s2', 'cp2', '乙場', 4000);
    await tester.pumpAndSettle();
    expect(find.textContaining('可能已泊入'), findsOneWidget);
  });

  testWidgets('rejected confirm shows the failure and does not post', (tester) async {
    final store = _store();
    final api = _RecordingSync();
    store.upsertCandidate('s1', 'cp1', '甲場', 2000);
    store.markConfirmed('s1', syncPending: true, atMs: 2000);
    store.markSyncRejected('s1', 'HTTP_409');
    await tester.pumpWidget(MaterialApp(home: TodayTripScreen(store: store, sync: TodayTripSync(store, api, _Online()))));
    await tester.pumpAndSettle();
    expect(find.text('確認被拒，未提交反饋'), findsOneWidget);
    expect(find.text('已提交反饋'), findsNothing);
    expect(find.byKey(const ValueKey('submit_feedback')), findsNothing);
    expect(api.reports, isEmpty);
  });

  testWidgets('http failure stays on the form and does not say submitted', (tester) async {
    final store = _store();
    final api = _FailingSync();
    store.upsertCandidate('s1', 'cp1', '甲場', 3000);
    store.markConfirmed('s1', syncPending: false, atMs: 3000);
    store.markSyncSent('s1');
    await tester.pumpWidget(MaterialApp(home: TodayTripScreen(store: store, sync: TodayTripSync(store, api, _Online(), nowMs: () => 5_000))));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('rating_GOOD')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('perk_NONE')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('submit_feedback')));
    await tester.pumpAndSettle();
    expect(find.text('未能確認提交結果'), findsOneWidget);
    expect(find.text('已提交反饋'), findsNothing);
    expect(store.find('s1')!.feedbackSubmitted, isFalse);
    expect(api.reports, hasLength(1));
  });
}
