import '../trip/prefs_port.dart';

class ChannelPrefsPort implements PrefsPort {
  ChannelPrefsPort({this.onWrite});

  final void Function(String key, String? value)? onWrite;
  final Map<String, String?> _mem = {};
  bool _hydrated = false;

  bool get hydrated => _hydrated;

  void hydrate(Map<String, String?> seed) {
    _mem
      ..clear()
      ..addAll(seed);
    _hydrated = true;
  }

  @override
  String? getString(String key) => _mem[key];

  @override
  void putString(String key, String value) {
    if (!_hydrated) return;
    _mem[key] = value;
    onWrite?.call(key, value);
  }

  @override
  void remove(String key) {
    if (!_hydrated) return;
    _mem.remove(key);
    onWrite?.call(key, null);
  }
}
