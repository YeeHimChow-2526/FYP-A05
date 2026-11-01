import 'prefs_port.dart';

class ChannelPrefsPort implements PrefsPort {
  ChannelPrefsPort({Map<String, String?>? seed, this.onWrite}) : _mem = Map<String, String?>.from(seed ?? const {});

  final void Function(String key, String? value)? onWrite;
  final Map<String, String?> _mem;

  @override
  String? getString(String key) => _mem[key];

  @override
  void putString(String key, String value) {
    _mem[key] = value;
    onWrite?.call(key, value);
  }

  @override
  void remove(String key) {
    _mem.remove(key);
    onWrite?.call(key, null);
  }
}
