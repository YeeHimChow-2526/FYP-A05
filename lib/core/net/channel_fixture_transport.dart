import 'api_transport.dart';
import 'http_result.dart';

class ChannelFixtureTransport implements ApiTransport {
  ChannelFixtureTransport(this.remote);

  final Future<Map<String, Object?>> Function(String path, String bodyJson) remote;

  @override
  Future<HttpResult> post(String path, String bodyJson) async {
    final raw = await remote(path, bodyJson);
    return HttpResult(
      statusCode: (raw['statusCode'] as num?)?.toInt() ?? 0,
      bodyJson: raw['body'] as String? ?? '',
      timedOut: raw['timedOut'] == true,
      networkError: raw['networkError'] == true,
      retryAfterSeconds: (raw['retryAfterS'] as num?)?.toInt(),
    );
  }
}
