import 'local_poi_trie.dart';

abstract class PoiTrieProvider {
  LocalPoiTrie? get();
}

class AssetPoiTrieProvider implements PoiTrieProvider {
  AssetPoiTrieProvider(this.open);

  final String Function() open;
  LocalPoiTrie? _cached;
  bool _failed = false;

  @override
  LocalPoiTrie? get() {
    if (_failed) return null;
    final cached = _cached;
    if (cached != null) return cached;
    try {
      final trie = LocalPoiTrie.fromJsonString(open());
      _cached = trie;
      return trie;
    } catch (_) {
      _failed = true;
      return null;
    }
  }
}
