enum EngineSignal { power, btCar }

class EngineShutdownDetector {
  EngineShutdownDetector({int Function()? nowMs}) : nowMs = nowMs ?? _systemNow;

  final int Function() nowMs;
  final Set<EngineSignal> _active = <EngineSignal>{};
  int _nextToken = 0;
  int? _activeToken;
  int? lastEventMs;

  static int _systemNow() => DateTime.now().millisecondsSinceEpoch;

  bool get isPending => _activeToken != null && _active.isNotEmpty;

  int? onDisconnected(EngineSignal signal) {
    lastEventMs = nowMs();
    final wasEmpty = _active.isEmpty;
    _active.add(signal);
    if (!wasEmpty) return null;
    final token = ++_nextToken;
    _activeToken = token;
    return token;
  }

  void onReconnected(EngineSignal signal) {
    lastEventMs = nowMs();
    _active.remove(signal);
    if (_active.isEmpty) _activeToken = null;
  }

  bool confirm(int token) {
    if (token != _activeToken || _active.isEmpty) return false;
    _activeToken = null;
    return true;
  }

  void reset() {
    _active.clear();
    _activeToken = null;
  }
}
