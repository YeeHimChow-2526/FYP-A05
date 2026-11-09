import 'dart:convert';
import 'dart:io';

import 'package:copilot/core/net/api_client.dart';
import 'package:copilot/core/net/fake_api_transport.dart';
import 'package:copilot/core/net/http_result.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('encoder json is the payload checked by cloud 15aab20', () async {
    final transport = FakeApiTransport([const HttpResult(statusCode: 200, bodyJson: '{}')]);
    final client = ApiClient(transport, sleeper: (_) async {});
    await client.submitTripReport(
      sessionId: 'sess-fake-0001',
      carparkId: 'CP_FAKE_001',
      ratingTag: 'GOOD',
      perkStatusTag: 'VALID',
      perkNotes: '測試備註😀',
      tripTimestampSec: 1792995600,
    );
    final appBody = transport.calls.single.body;
    const flatBody = '{"carpark_id":"CP_FAKE_001","rating_tag":"GOOD","perk_status_tag":"VALID","perk_notes":"","session_id":"sess-fake-0001","trip_timestamp":1792995600}';
    final dir = Directory.systemTemp.createTempSync('hkp-trip-report-');
    final file = File('${dir.path}${Platform.pathSeparator}payload.json');
    final resultFile = File('${dir.path}${Platform.pathSeparator}result.json');
    file.writeAsStringSync(jsonEncode({'app_body': appBody, 'flat_body': flatBody}));
    const script = 'test/core/net/trip_report_cloud_contract.py';
    const server = r'C:\Users\kenny\HKP-local\r9-cloud-read-15aab20\server';
    var result = await Process.run('python', [script, file.path, server, resultFile.path]);
    if (result.exitCode != 0) {
      result = await Process.run('py', ['-3', script, file.path, server, resultFile.path]);
    }
    expect(result.exitCode, 0, reason: '${result.stdout}\n${result.stderr}');
    final report = jsonDecode(resultFile.readAsStringSync()) as Map<String, Object?>;
    final sent = jsonDecode(appBody) as Map<String, Object?>;
    expect(report['sha'], '15aab205615ea56948d9f76105c2ce0ec2b5c82c');
    expect(report['stored'], sent['feedback']);
    expect(report['success'], 200);
    expect(report['flat_status'], 422);
    expect((report['flat_error_count'] as num) > 0, isTrue);
    expect(report['duplicate_statuses'], [200, 200]);
    expect(report['duplicate_writes'], 2);
    expect(report['bad_enum_status'], 200);
    expect(report['midnight_status'], 200);
  });
}
