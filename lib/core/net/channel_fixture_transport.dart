import 'api_responses.dart';
import 'api_transport.dart';
import 'http_result.dart';

class ChannelFixtureTransport implements ApiTransport {
  ChannelFixtureTransport(this.remote);

  final Future<Map<String, Object?>> Function(String path, String bodyJson) remote;

  @override
  Future<HttpResult> post(String path, String bodyJson) async {
    final raw = await remote(path, bodyJson);
    final code = (raw['statusCode'] as num?)?.toInt() ?? 0;
    final body = raw['body'] as String? ?? '';
    final timedOut = raw['timedOut'] == true;
    final networkError = raw['networkError'] == true;
    final retryAfter = (raw['retryAfterS'] as num?)?.toInt();
    // 通道已標明逾時或網絡失敗：保持原樣，唔當推薦 body 去解析。
    if (timedOut || networkError) {
      return HttpResult(
        statusCode: code,
        bodyJson: body,
        timedOut: timedOut,
        networkError: networkError,
        retryAfterSeconds: retryAfter,
      );
    }
    // 與 HttpApiTransport 同一套 buildResult：有效卡、真正空卡、格式錯誤各走原規則。
    final built = ApiResponses.buildResult(path, code, body, const {});
    return HttpResult(
      statusCode: built.statusCode,
      bodyJson: built.bodyJson,
      headers: built.headers,
      retryAfterSeconds: built.retryAfterSeconds ?? retryAfter,
      networkError: built.networkError,
      recommendResponse: built.recommendResponse,
      spokenCue: built.spokenCue,
    );
  }
}
