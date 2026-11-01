import 'recommend_response.dart';

class HttpResult {
  const HttpResult({
    this.statusCode = 200,
    this.bodyJson = '',
    this.headers = const {},
    this.retryAfterSeconds,
    this.timedOut = false,
    this.networkError = false,
    this.recommendResponse,
    this.spokenCue,
  });

  final int statusCode;
  final String bodyJson;
  final Map<String, String> headers;
  final int? retryAfterSeconds;
  final bool timedOut;
  final bool networkError;
  final RecommendResponse? recommendResponse;
  final String? spokenCue;

  int? get resolvedRetryAfterSeconds {
    if (retryAfterSeconds != null) return retryAfterSeconds;
    for (final entry in headers.entries) {
      if (entry.key.toLowerCase() == 'retry-after') {
        return int.tryParse(entry.value.trim());
      }
    }
    return null;
  }
}
