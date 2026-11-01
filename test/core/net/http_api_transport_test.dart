import 'dart:convert';
import 'dart:io';

import 'package:copilot/core/constants/native_config.dart';
import 'package:copilot/core/net/api_client.dart';
import 'package:copilot/core/net/http_api_transport.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('blank key is networkError and does not hit the server', () async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    var hits = 0;
    server.listen((request) async {
      hits += 1;
      request.response.statusCode = 200;
      await request.response.close();
    });
    final transport = HttpApiTransport(baseUrl: 'http://127.0.0.1:${server.port}', apiKey: ' ', allowLocalCleartext: true);
    final result = await transport.post(ApiClient.RECOMMEND, '{}');
    expect(result.networkError, isTrue);
    expect(result.statusCode, 0);
    expect(hits, 0);
    await server.close(force: true);
  });

  test('redirect is not followed', () async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    var hits = 0;
    server.listen((request) async {
      hits += 1;
      request.response.statusCode = 302;
      request.response.headers.set('location', 'http://127.0.0.1:1/elsewhere');
      await request.response.close();
    });
    final transport = HttpApiTransport(baseUrl: 'http://127.0.0.1:${server.port}', apiKey: 'k', allowLocalCleartext: true, timeoutMs: 1500);
    final client = ApiClient(transport, sleeper: (_) async {});
    final outcome = await client.syncDriverConfirmed('s-1', 1);
    expect(outcome.succeeded, isFalse);
    expect(outcome.httpStatus, 302);
    expect(hits, 1);
    await server.close(force: true);
  });

  test('oversized body is cancelled as networkError', () async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    server.listen((request) async {
      request.response.statusCode = 200;
      request.response.add(List<int>.filled(NativeConfig.MAX_RESPONSE_BYTES + 64, 0x61));
      await request.response.close();
    });
    final transport = HttpApiTransport(baseUrl: 'http://127.0.0.1:${server.port}', apiKey: 'k', allowLocalCleartext: true, timeoutMs: 3000);
    final result = await transport.post(ApiClient.SYNC, '{}');
    expect(result.networkError, isTrue);
    expect(result.statusCode, 0);
    await server.close(force: true);
  });

  test('slow server times out', () async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    server.listen((request) async {
      await Future<void>.delayed(const Duration(milliseconds: 800));
      request.response.statusCode = 200;
      request.response.write('{}');
      await request.response.close();
    });
    final transport = HttpApiTransport(baseUrl: 'http://127.0.0.1:${server.port}', apiKey: 'k', allowLocalCleartext: true, timeoutMs: 150);
    final result = await transport.post(ApiClient.SYNC, '{}');
    expect(result.timedOut, isTrue);
    expect(result.networkError, isFalse);
    await server.close(force: true);
  });

  test('recommend round trip does not log the key in the body', () async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    String? seenKey;
    server.listen((request) async {
      seenKey = request.headers.value(ApiClient.HEADER_API_KEY);
      request.response.statusCode = 200;
      request.response.write(jsonEncode({
        'status': 'SUCCESS',
        'cards': [
          {'carpark_id': 'CP_LANGHAM', 'name': '朗豪坊'}
        ],
      }));
      await request.response.close();
    });
    final transport = HttpApiTransport(baseUrl: 'http://127.0.0.1:${server.port}', apiKey: 'secret-key', allowLocalCleartext: true);
    final result = await transport.post(ApiClient.RECOMMEND, '{"dest_name":"朗豪坊"}');
    expect(seenKey, 'secret-key');
    expect(result.recommendResponse!.cards.single.name, '朗豪坊');
    expect(result.bodyJson.contains('secret-key'), isFalse);
    await server.close(force: true);
  });
}
