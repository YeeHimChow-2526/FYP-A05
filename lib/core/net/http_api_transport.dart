import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import '../constants/native_config.dart';
import 'api_client.dart';
import 'api_endpoint_policy.dart';
import 'api_responses.dart';
import 'api_transport.dart';
import 'http_result.dart';

class HttpApiTransport implements ApiTransport {
  HttpApiTransport({
    this.baseUrl = '',
    this.apiKey = ApiClient.INTERNAL_API_KEY,
    this.allowLocalCleartext = false,
    this.timeoutMs = NativeConfig.API_TIMEOUT_MS,
  });

  final String baseUrl;
  final String apiKey;
  final bool allowLocalCleartext;
  final int timeoutMs;

  EndpointVerdict get verdict => ApiEndpointPolicy.check(baseUrl, apiKey, allowLocalCleartext);

  @override
  Future<HttpResult> post(String path, String bodyJson) async {
    final current = verdict;
    if (!current.usable) {
      return const HttpResult(statusCode: 0, networkError: true);
    }
    final fullUrl = ApiEndpointPolicy.joinUrl(current.normalizedBaseUrl, path, allowLocalCleartext);
    if (fullUrl == null) return const HttpResult(statusCode: 0, networkError: true);
    final client = HttpClient();
    try {
      return await _send(client, fullUrl, path, bodyJson).timeout(
        Duration(milliseconds: timeoutMs),
        onTimeout: () {
          client.close(force: true);
          return const HttpResult(statusCode: 0, timedOut: true);
        },
      );
    } on TimeoutException {
      return const HttpResult(statusCode: 0, timedOut: true);
    } on SocketException {
      return const HttpResult(statusCode: 0, networkError: true);
    } on HttpException {
      return const HttpResult(statusCode: 0, networkError: true);
    } catch (_) {
      return const HttpResult(statusCode: 0, networkError: true);
    } finally {
      client.close(force: true);
    }
  }

  Future<HttpResult> _send(HttpClient client, String fullUrl, String path, String bodyJson) async {
    final request = await client.postUrl(Uri.parse(fullUrl));
    request.followRedirects = false;
    request.headers.set(ApiClient.HEADER_API_KEY, apiKey);
    request.headers.set(ApiClient.HEADER_CONTENT_TYPE, ApiClient.JSON_CONTENT_TYPE);
    request.headers.set(HttpHeaders.acceptHeader, ApiClient.JSON_CONTENT_TYPE);
    request.add(utf8.encode(bodyJson));
    final response = await request.close();
    final code = response.statusCode;
    final bytes = await _readCapped(response);
    if (bytes == null) return const HttpResult(statusCode: 0, networkError: true);
    final body = utf8.decode(bytes);
    final retryAfter = response.headers.value('retry-after');
    final headers = retryAfter == null ? const <String, String>{} : {'Retry-After': retryAfter};
    return ApiResponses.buildResult(path, code, body, headers);
  }

  Future<List<int>?> _readCapped(HttpClientResponse response) {
    final completer = Completer<List<int>?>();
    final out = BytesBuilder(copy: false);
    var total = 0;
    late final StreamSubscription<List<int>> sub;
    sub = response.listen(
      (chunk) {
        total += chunk.length;
        if (total > NativeConfig.MAX_RESPONSE_BYTES) {
          sub.cancel();
          if (!completer.isCompleted) completer.complete(null);
          return;
        }
        out.add(chunk);
      },
      onDone: () {
        if (!completer.isCompleted) completer.complete(out.takeBytes());
      },
      onError: (Object error, StackTrace stack) {
        if (!completer.isCompleted) completer.completeError(error, stack);
      },
      cancelOnError: true,
    );
    return completer.future;
  }
}
