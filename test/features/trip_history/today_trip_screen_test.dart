import 'dart:convert';

import 'package:copilot/core/platform/overlay_channel.dart';
import 'package:copilot/features/trip_history/today_trip_screen.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  final List<MethodCall> calls = [];
  List<Map<String, Object?>> trips = [];

  setUp(() {
    calls.clear();
    trips = [];
    OverlayChannel.resetForTest();
    OverlayChannel.attach();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(OverlayChannel.channel, (call) async {
      calls.add(call);
      switch (call.method) {
        case 'getTodayTrips':
          return trips;
        case 'confirmParkedCandidate':
          return null;
        case 'submitTripReport':
          return null;
        default:
          return null;
      }
    });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(OverlayChannel.channel, null);
    OverlayChannel.resetForTest();
  });

  testWidgets('step34_f01_todayTripScreenShowsEmpty', (tester) async {
    trips = [];
    await tester.pumpWidget(const MaterialApp(home: TodayTripScreen()));
    await tester.pumpAndSettle();
    expect(find.text('今日未有行程'), findsOneWidget);
  });

  testWidgets('step34_f02_candidateShowsConfirmCta', (tester) async {
    trips = [
      {
        'sessionId': 's1',
        'carparkId': 'cp1',
        'carparkName': '甲場',
        'parkedAtEpochMs': 1000,
        'status': 'CANDIDATE',
        'confirmSyncPending': false,
        'feedbackSubmitted': false,
      },
    ];
    await tester.pumpWidget(const MaterialApp(home: TodayTripScreen()));
    await tester.pumpAndSettle();
    expect(find.textContaining('可能已泊入'), findsOneWidget);
    expect(find.text('確認已泊妥'), findsOneWidget);
  });

  testWidgets('step34_f03_tapConfirmInvokesChannel', (tester) async {
    trips = [
      {
        'sessionId': 's1',
        'carparkId': 'cp1',
        'carparkName': '甲場',
        'parkedAtEpochMs': 1000,
        'status': 'CANDIDATE',
        'confirmSyncPending': false,
        'feedbackSubmitted': false,
      },
    ];
    await tester.pumpWidget(const MaterialApp(home: TodayTripScreen()));
    await tester.pumpAndSettle();
    await tester.tap(find.text('確認已泊妥'));
    await tester.pumpAndSettle();
    expect(
      calls.where((c) => c.method == 'confirmParkedCandidate').length,
      1,
    );
    expect(
      calls.firstWhere((c) => c.method == 'confirmParkedCandidate').arguments,
      {'sessionId': 's1'},
    );
  });

  testWidgets('step34_f04_confirmedShowsQuickFeedback', (tester) async {
    trips = [
      {
        'sessionId': 's1',
        'carparkId': 'cp1',
        'carparkName': '甲場',
        'parkedAtEpochMs': 2000,
        'status': 'CONFIRMED',
        'confirmSyncPending': false,
        'feedbackSubmitted': false,
      },
    ];
    await tester.pumpWidget(const MaterialApp(home: TodayTripScreen()));
    await tester.pumpAndSettle();
    expect(find.byKey(const ValueKey('feedback_s1')), findsOneWidget);
    expect(find.text('GOOD'), findsOneWidget);
    expect(find.text('NONE'), findsOneWidget);
    expect(find.text('提交反饋'), findsOneWidget);
  });

  testWidgets('step34_f05_submitBuildsPayload', (tester) async {
    trips = [
      {
        'sessionId': 's1',
        'carparkId': 'cp1',
        'carparkName': '甲場',
        'parkedAtEpochMs': 3000,
        'status': 'CONFIRMED',
        'confirmSyncPending': false,
        'feedbackSubmitted': false,
      },
    ];
    await tester.pumpWidget(const MaterialApp(home: TodayTripScreen()));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('rating_GOOD')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('perk_NONE')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const ValueKey('submit_feedback')));
    await tester.pumpAndSettle();
    final submit = calls.firstWhere((c) => c.method == 'submitTripReport');
    final args = submit.arguments as Map;
    final payloadJson = args['payloadJson'] as String;
    final payload = jsonDecode(payloadJson) as Map<String, dynamic>;
    expect(payload['rating_tag'], 'GOOD');
    expect(payload['perk_status_tag'], 'NONE');
    expect(payload['session_id'], 's1');
    expect(payload['carpark_id'], 'cp1');
    expect(payload['trip_timestamp'], 3);
    expect(payload.containsKey('photo_url'), isFalse);
  });

  testWidgets('step34_f06_onTodayTripsChangedRefreshes', (tester) async {
    trips = [];
    await tester.pumpWidget(const MaterialApp(home: TodayTripScreen()));
    await tester.pumpAndSettle();
    expect(find.text('今日未有行程'), findsOneWidget);
    final getCountBefore =
        calls.where((c) => c.method == 'getTodayTrips').length;

    trips = [
      {
        'sessionId': 's2',
        'carparkId': 'cp2',
        'carparkName': '乙場',
        'parkedAtEpochMs': 4000,
        'status': 'CANDIDATE',
        'confirmSyncPending': false,
        'feedbackSubmitted': false,
      },
    ];
    await _emit('onTodayTripsChanged', null);
    await tester.pumpAndSettle();

    expect(
      calls.where((c) => c.method == 'getTodayTrips').length,
      greaterThan(getCountBefore),
    );
    expect(find.textContaining('可能已泊入'), findsOneWidget);
  });
}

Future<void> _emit(String method, Object? arguments) {
  final codec = const StandardMethodCodec();
  return TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
      .handlePlatformMessage(
    OverlayChannel.name,
    codec.encodeMethodCall(MethodCall(method, arguments)),
    (_) {},
  );
}
