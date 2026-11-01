import 'http_result.dart';

abstract class ApiTransport {
  Future<HttpResult> post(String path, String bodyJson);
}
