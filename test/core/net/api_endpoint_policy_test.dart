import 'package:copilot/core/net/api_endpoint_policy.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  const key = 'k';

  EndpointReason reason(String url, bool debug, {String k = key}) => ApiEndpointPolicy.check(url, k, debug).reason;

  test('https only except debug loopback', () {
    expect(reason('https://api.example.hk', false), EndpointReason.ok);
    expect(reason('https://api.example.hk/base/', true), EndpointReason.ok);
    expect(ApiEndpointPolicy.check('https://api.example.hk/base/', key, false).normalizedBaseUrl, 'https://api.example.hk/base');
    expect(reason('http://api.example.hk', true), EndpointReason.cleartextNotAllowed);
    expect(reason('http://192.168.1.5:8080', true), EndpointReason.cleartextNotAllowed);
    expect(reason('http://127.0.0.1:8080', false), EndpointReason.cleartextNotAllowed);
    expect(reason('http://127.0.0.1:8080', true), EndpointReason.ok);
    expect(reason('http://localhost:8080', true), EndpointReason.ok);
    expect(reason('http://10.0.2.2:8080', true), EndpointReason.ok);
    expect(reason('ftp://api.example.hk', true), EndpointReason.unsupportedScheme);
    expect(reason('https://api.example.hk?x=1', false), EndpointReason.badUrl);
    expect(reason('https://user:pw@api.example.hk', false), EndpointReason.badUrl);
    expect(reason('', true), EndpointReason.blankUrl);
    expect(reason('https://api.example.hk', false, k: ' '), EndpointReason.blankKey);
  });

  test('host normalize and path join', () {
    expect(ApiEndpointPolicy.check('http://LOCALHOST:8787', key, true).reason, EndpointReason.ok);
    expect(ApiEndpointPolicy.check('http://LOCALHOST:8787', key, true).host, 'localhost');
    expect(ApiEndpointPolicy.check('http://localhost.:8787', key, true).reason, EndpointReason.cleartextNotAllowed);
    expect(ApiEndpointPolicy.check('http://evil.localhost:8787', key, true).reason, EndpointReason.cleartextNotAllowed);
    expect(ApiEndpointPolicy.check('http://127.0.0.1.nip.io:8787', key, true).reason, EndpointReason.cleartextNotAllowed);
    const base = 'http://127.0.0.1:8787/';
    expect(ApiEndpointPolicy.joinUrl(base, '/v1/recommend-and-route', true), 'http://127.0.0.1:8787/v1/recommend-and-route');
    expect(ApiEndpointPolicy.joinUrl(base, '/v1/x?q=1', true), isNull);
    expect(ApiEndpointPolicy.joinUrl(base, '/v1/x#frag', true), isNull);
    expect(ApiEndpointPolicy.joinUrl(base, 'v1/no-leading', true), isNull);
    expect(ApiEndpointPolicy.joinUrl(base, '/api/v1/x', true), isNull);
  });
}
