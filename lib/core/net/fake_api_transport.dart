import 'api_transport.dart';
import 'http_result.dart';

class FakeApiTransport implements ApiTransport {
  FakeApiTransport(this.script);

  final List<HttpResult> script;
  final List<({String path, String body})> calls = [];
  int _index = 0;

  @override
  Future<HttpResult> post(String path, String bodyJson) async {
    calls.add((path: path, body: bodyJson));
    return script[_index++];
  }
}
