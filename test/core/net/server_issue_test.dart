import 'package:copilot/core/constants/native_config.dart';
import 'package:copilot/core/net/api_client.dart';
import 'package:copilot/core/net/api_transport.dart';
import 'package:copilot/core/net/http_result.dart';
import 'package:copilot/core/net/server_issue.dart';
import 'package:copilot/core/voice/zh_hk_templates.dart';
import 'package:flutter_test/flutter_test.dart';

class _Seq implements ApiTransport {
  _Seq(this.results);
  final List<HttpResult> results;
  final bodies = <String>[];
  @override
  Future<HttpResult> post(String path, String bodyJson) async {
    bodies.add(bodyJson);
    return results.length > 1 ? results.removeAt(0) : results.first;
  }
}

void main() {
  test('S1 classification of typed contract responses', () {
    ServerIssueKind? k(int code, String body) => ServerIssue.from(code, body)?.kind;
    expect(k(409, '{"status":"CAPACITY_UNKNOWN"}'), ServerIssueKind.capacityUnavailable);
    expect(k(409, '{"status":"CAPACITY_NOT_READY"}'), ServerIssueKind.capacityUnavailable);
    expect(k(409, '{"status":"STALE_PARKED"}'), ServerIssueKind.staleState);
    expect(k(409, '{"status":"SESSION_ID_REUSED"}'), ServerIssueKind.sessionInvalid);
    expect(k(404, '{"status":"SESSION_NOT_FOUND"}'), ServerIssueKind.sessionEnded);
    expect(k(503, '{"status":"CONTENTION"}'), ServerIssueKind.contention);
    expect(k(422, '{"detail":[]}'), ServerIssueKind.unsupportedBackend);
    expect(k(404, ''), ServerIssueKind.unsupportedBackend);
    expect(ServerIssue.from(409, '{"status":"LOCK_FULL"}'), isNull, reason: 'LOCK_FULL 行原有下一張卡流程');
    expect(ServerIssue.from(503, '{"status":"SESSION_CLOSED"}'), isNull);
    expect(ServerIssue.from(500, ''), isNull);
    expect(ServerIssue.from(200, '{"status":"STALE_EPOCH"}'), isNull);
  });

  test('S2 typed 409 on recommend is not a network failure (no grey, no network cue, no retry)', () async {
    final t = _Seq([const HttpResult(statusCode: 409, bodyJson: '{"status":"SESSION_START_EXPIRED"}')]);
    final out = await ApiClient(t, sleeper: (_) async {}).recommendAndRoute(
      const RecommendRequest(sessionId: 's', originLat: 0, originLng: 0, destLat: 0, destLng: 0, destName: 'A', preference: 'SMART_HYBRID'),
      QueryTriggerMode.driverActive,
    );
    expect(out.shouldTurnGrey, isFalse);
    expect(out.spokenCues, isNot(contains(ZhHkTemplates.NETWORK_FAILED)));
    expect(t.bodies, hasLength(1));
    expect(out.serverIssue?.code, 'SESSION_START_EXPIRED');
  });

  test('S3 CONTENTION 503 retried a bounded number of times with the identical body (same idempotency key)', () async {
    final t = _Seq([const HttpResult(statusCode: 503, bodyJson: '{"status":"CONTENTION"}')]);
    final out = await ApiClient(t, sleeper: (_) async {}).commitSession('s', 'CP', destinationEpoch: 1, idempotencyKey: 'c1-CP000');
    expect(t.bodies, hasLength(NativeConfig.API_MAX_ATTEMPTS));
    expect(t.bodies.toSet(), hasLength(1));
    expect(out.serverIssue?.kind, ServerIssueKind.contention);
    expect(out.shouldTurnGrey, isFalse);
    final ok = _Seq([const HttpResult(statusCode: 503, bodyJson: '{"status":"CONTENTION"}'), const HttpResult(statusCode: 200, bodyJson: '{"status":"COMMITTED","lock_revision":1}')]);
    expect((await ApiClient(ok, sleeper: (_) async {}).commitSession('s', 'CP', destinationEpoch: 1, idempotencyKey: 'c1-CP000')).succeeded, isTrue);
  });
}
