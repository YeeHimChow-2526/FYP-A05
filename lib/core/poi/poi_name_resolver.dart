import 'jyutping.dart';
import 'local_poi_trie.dart';
import 'poi.dart';
import 'stt_typo.dart';
import 'trie_result.dart';

class PoiNameResolver {
  PoiNameResolver({required this.trie, required this.typo, JyutpingTable? table}) : table = table ?? JyutpingTable.generated;

  final LocalPoiTrie trie;
  final SttTypoCorpus typo;
  final JyutpingTable table;

  static const List<String> _prefixes = ['導航去', '去', '搵'];
  static const List<String> _typePriority = ['A', 'M', 'C', 'D'];

  TrieResult resolve(String raw) {
    var query = LocalPoiTrie.normalize(raw);
    query = _stripLeading(query);
    final corrected = typo.apply(query);
    final direct = trie.lookup(corrected);
    if (direct is Hit) return direct;
    if (direct is Ambiguous) return Ambiguous(direct.candidates, isFuzzy: false);
    if (_hanCount(query) < 2) return trieMiss;
    final syllables = LazySound.syllables(query, table);
    if (syllables == null || syllables.isEmpty) return trieMiss;
    final exact = <Poi>[];
    final near = <Poi>[];
    for (final entry in trie.exactKeys.entries) {
      if (!_allHan(entry.key)) continue;
      final keySyl = LazySound.syllables(entry.key, table);
      if (keySyl == null) continue;
      final distance = _edit(syllables, keySyl);
      if (distance == 0) {
        exact.addAll(entry.value);
      } else if (syllables.length >= 2 && distance <= 1) {
        near.addAll(entry.value);
      }
    }
    if (exact.isNotEmpty) return _pick(exact, fuzzy: true);
    if (syllables.length >= 2 && near.isNotEmpty) return _pick(near, fuzzy: true);
    return trieMiss;
  }

  TrieResult _pick(List<Poi> candidates, {required bool fuzzy}) {
    final unique = <String, Poi>{};
    for (final poi in candidates) {
      unique[poi.id] = poi;
    }
    final rows = unique.values.toList();
    final bestType = _typePriority.firstWhere((type) => rows.any((poi) => poi.type == type), orElse: () => rows.first.type);
    final group = rows.where((poi) => poi.type == bestType).toList()..sort((a, b) => a.id.compareTo(b.id));
    if (group.length == 1) return Hit(group.first);
    return Ambiguous(group.take(2).toList(), isFuzzy: fuzzy);
  }

  static String _stripLeading(String query) {
    for (final prefix in _prefixes) {
      if (query.startsWith(prefix) && query.length > prefix.length) {
        return query.substring(prefix.length);
      }
    }
    return query;
  }

  static int _hanCount(String query) => query.runes.where((rune) => rune >= 0x4E00 && rune <= 0x9FFF).length;

  static bool _allHan(String key) {
    if (key.isEmpty) return false;
    return key.runes.every((rune) => rune >= 0x4E00 && rune <= 0x9FFF);
  }

  static int _edit(List<String> a, List<String> b) {
    final rows = List.generate(a.length + 1, (_) => List<int>.filled(b.length + 1, 0));
    for (var i = 0; i <= a.length; i++) {
      rows[i][0] = i;
    }
    for (var j = 0; j <= b.length; j++) {
      rows[0][j] = j;
    }
    for (var i = 1; i <= a.length; i++) {
      for (var j = 1; j <= b.length; j++) {
        final cost = a[i - 1] == b[j - 1] ? 0 : 1;
        final del = rows[i - 1][j] + 1;
        final ins = rows[i][j - 1] + 1;
        final sub = rows[i - 1][j - 1] + cost;
        rows[i][j] = del < ins ? (del < sub ? del : sub) : (ins < sub ? ins : sub);
      }
    }
    return rows[a.length][b.length];
  }
}
