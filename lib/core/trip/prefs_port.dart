abstract class PrefsPort {
  String? getString(String key);
  void putString(String key, String value);
  void remove(String key);
}

class MemoryPrefsPort implements PrefsPort {
  final Map<String, String> _map = {};

  @override
  String? getString(String key) => _map[key];

  @override
  void putString(String key, String value) {
    _map[key] = value;
  }

  @override
  void remove(String key) {
    _map.remove(key);
  }
}
