import 'package:copilot/core/nav/fake_nav_engine.dart';
import 'package:copilot/core/net/api_client.dart';
import 'package:copilot/core/net/http_result.dart';
import 'package:copilot/core/trip/trip_ports.dart';
import 'package:copilot/core/voice/zh_hk_templates.dart';
import 'package:copilot/core/trip/trip_controller.dart';
import 'package:copilot/core/trip/trip_types.dart';
import 'package:copilot/features/nav_shell/nav_shell_screen.dart' show navTargetLine;
import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';

import 'trip_controller_test.dart';

HttpResult _committed(int revision) =>
    HttpResult(statusCode: 200, bodyJson: '{"status":"COMMITTED","contract_version":2,"lock_revision":$revision,"destination_epoch":1}');

LocFix _fix(double lat, int ts) => LocFix(lat: lat, lng: destLng, accuracyM: 10, speedKmh: 20, timestampMs: ts);

Future<(TripController, FakeNavEngine)> _atA(HoldTransport t, FakePlatform p) async {
  final nav = FakeNavEngine();
  t.next = ok([card('CP_A', 'A場'), card('CP_B', 'B場'), card('CP_C', 'C場')]);
  final c = await boot(t, p, ScriptDelay(), navEngine: nav);
  c.lockDestinationAt('目的地X', destLat, destLng);
  c.onFix(_fix(destLat + delta1000 * 0.5, 1000));
  await Future<void>.delayed(Duration.zero);
  t.next = _committed(3);
  await c.confirmCandidate(0);
  expect(c.state, TripState.navToGate);
  expect(c.committedCarparkId, 'CP_A');
  return (c, nav);
}

int _commits(HoldTransport t) => t.calls.where((x) => x == ApiClient.COMMIT).length;

void main() {
  group('r7 confirmCandidate switching', () {
    test('R7-1 A and B commit; C waits, then one explicit consent switches once', () async {
      final t = HoldTransport();
      final (c, nav) = await _atA(t, FakePlatform());
      expect(nav.activeTitle, 'A場');
      t.next = _committed(4);
      await c.confirmCandidate(1);
      expect(c.committedCarparkId, 'CP_B');
      expect(c.heldLock?.lockRevision, 4);
      expect(nav.activeTitle, 'B場');
      expect(c.navTarget?.kind, NavTargetKind.carpark);
      expect(c.navTarget?.state, NavTargetState.guiding);
      final commitsAtB = _commits(t);
      final routesAtB = nav.calls.where((call) => call == 'setDestinations').length;
      expect(commitsAtB, 2);
      t.next = _committed(5);
      await c.confirmCandidate(2);
      expect(c.pendingSwitchCarparkId, 'CP_C');
      expect(c.committedCarparkId, 'CP_B');
      expect(c.heldLock?.lockRevision, 4);
      expect(nav.activeTitle, 'B場');
      expect(c.navTarget?.title, 'B場');
      expect(c.state, TripState.navToGate);
      expect(c.confirmNote, contains('未確認'));
      expect(c.confirmNote, contains('C場'));
      expect(_commits(t), commitsAtB);
      expect(nav.calls.where((call) => call == 'setDestinations'), hasLength(routesAtB));

      await c.acceptPendingSwitch();
      expect(c.pendingSwitchCarparkId, isNull);
      expect(c.committedCarparkId, 'CP_C');
      expect(c.heldLock?.lockRevision, 5);
      expect(nav.activeTitle, 'C場');
      expect(c.navTarget?.title, 'C場');
      expect(c.state, TripState.navToGate);
      expect(_commits(t), 3);
      expect(nav.calls.where((call) => call == 'setDestinations'), hasLength(routesAtB + 1));
      final keys = [for (var i = 0; i < t.calls.length; i++) if (t.calls[i] == ApiClient.COMMIT) (jsonDecode(t.bodies[i]) as Map)['idempotency_key']];
      expect(keys.toSet(), hasLength(3));
      expect(keys[1], contains('CP_B'));
      expect(keys[2], contains('CP_C'));
      expect(c.confirmNote, contains('已確認'));
      expect(c.confirmNote, contains('C場'));

      final commitsAfter = _commits(t);
      final routesAfter = nav.calls.where((call) => call == 'setDestinations').length;
      await c.acceptPendingSwitch();
      await c.confirmCandidate(2);
      expect(c.committedCarparkId, 'CP_C');
      expect(nav.activeTitle, 'C場');
      expect(_commits(t), commitsAfter);
      expect(nav.calls.where((call) => call == 'setDestinations'), hasLength(routesAfter));
    });

    test('R7-2 B fails → A kept (lock, route, target)', () async {
      final t = HoldTransport();
      final p = FakePlatform();
      final (c, nav) = await _atA(t, p);
      t.next = const HttpResult(statusCode: 500, bodyJson: '{}');
      await c.confirmCandidate(1);
      expect(c.committedCarparkId, 'CP_A');
      expect(c.heldLock?.lockRevision, 3);
      expect(nav.activeTitle, 'A場');
      expect(c.navTarget?.title, 'A場');
      expect(c.state, TripState.navToGate);
      expect(c.confirmNote, contains('唔成功'));
      expect(navTargetLine(c), contains('導航緊去停車場「A場」'));
      expect(c.confirmNote, contains('A場'));
    });

    test('R7-3 same carpark is idempotent with feedback', () async {
      final t = HoldTransport();
      final (c, _) = await _atA(t, FakePlatform());
      await c.confirmCandidate(0);
      expect(_commits(t), 1);
      expect(c.confirmNote, contains('已經確認'));
    });

    test('R7-4 busy while commit in flight → explicit note, no 2nd commit', () async {
      final t = HoldTransport();
      final (c, nav) = await _atA(t, FakePlatform());
      t.next = null;
      final pending = c.confirmCandidate(1);
      await Future<void>.delayed(Duration.zero);
      await c.confirmCandidate(2);
      expect(c.confirmNote, contains('請等等'));
      expect(_commits(t), 2);
      expect(nav.activeTitle, 'A場'); // 成功前唔改路線
      t.waiting.last.complete(_committed(4));
      await pending;
      expect(nav.activeTitle, 'B場');
    });

    test('R7-5 stale epoch result does not reroute', () async {
      final t = HoldTransport();
      final (c, nav) = await _atA(t, FakePlatform());
      t.next = null;
      final pending = c.confirmCandidate(1);
      await Future<void>.delayed(Duration.zero);
      c.lockDestinationAt('新目的地', destLat + delta1000 * 3, destLng);
      for (final w in t.waiting) {
        if (!w.isCompleted) w.complete(_committed(4));
      }
      await pending;
      expect(nav.activeTitle, isNot('B場'));
      expect(c.committedCarparkId, isNot('CP_B'));
    });

    test('R7-6 abort while commit in flight → no reroute', () async {
      final t = HoldTransport();
      final (c, nav) = await _atA(t, FakePlatform());
      t.next = null;
      final pending = c.confirmCandidate(1);
      await Future<void>.delayed(Duration.zero);
      t.next = const HttpResult(statusCode: 200, bodyJson: '{"status":"RELEASED"}');
      await c.abortTripByUser();
      t.waiting.first.complete(_committed(4));
      await pending;
      expect(nav.activeTitle, isNot('B場'));
    });

    test('R7-7 status line: destination vs carpark target + note; spoken confirm', () async {
      final t = HoldTransport();
      final p = FakePlatform();
      final (c, _) = await _atA(t, p);
      c.tsm.tripVoiceMode = TripVoiceMode.announceManual;
      t.next = _committed(4);
      await c.confirmCandidate(1);
      expect(p.spoken, contains('已確認B場，正在計算路線'));
      final line = navTargetLine(c);
      expect(line, contains('目的地：目的地X'));
      expect(line, contains('導航緊去停車場「B場」'));
      expect(line, contains('已確認「B場」'));
    });

    test('R7-8 manual-only voice → no confirm speech', () async {
      final t = HoldTransport();
      final p = FakePlatform();
      t.next = ok([card('CP_A', 'A場')]);
      final c = await boot(t, p, ScriptDelay(), navEngine: FakeNavEngine());
      c.tsm.tripVoiceMode = TripVoiceMode.manualOnly; // default too
      c.lockDestinationAt('目的地X', destLat, destLng);
      c.onFix(_fix(destLat + delta1000 * 0.5, 1000));
      await Future<void>.delayed(Duration.zero);
      t.next = _committed(3);
      await c.confirmCandidate(0);
      expect(p.spoken, isEmpty);
    });

    test('R7-9 commit ok but route fails → no guiding claim', () async {
      final t = HoldTransport();
      final (c, nav) = await _atA(t, FakePlatform());
      nav.shouldFailGuidance = true;
      t.next = _committed(4);
      await c.confirmCandidate(1);
      expect(c.navTarget?.state, NavTargetState.failed);
      final line = navTargetLine(c);
      expect(line, contains('路線失敗'));
      expect(line, isNot(contains('導航緊')));
    });
  });
}
