import 'dart:convert';

import 'local_poi_trie.dart';

class SttTypoCorpus {
  SttTypoCorpus(Map<String, String> entries) : entries = Map.unmodifiable(entries);

  final Map<String, String> entries;

  static SttTypoCorpus fromJsonString(String text) {
    final decoded = jsonDecode(text);
    if (decoded is! List) return SttTypoCorpus(const {});
    final map = <String, String>{};
    for (final raw in decoded) {
      if (raw is! Map) continue;
      final from = raw['from'];
      final to = raw['to'];
      if (from is! String || to is! String) continue;
      map[LocalPoiTrie.normalize(from)] = LocalPoiTrie.normalize(to);
    }
    return SttTypoCorpus(map);
  }

  SttTypoCorpus without(String from) {
    final next = Map<String, String>.from(entries);
    next.remove(LocalPoiTrie.normalize(from));
    return SttTypoCorpus(next);
  }

  String apply(String normalized) => entries[normalized] ?? normalized;

  List<String> expandOrCorrect(String raw) {
    final normalized = LocalPoiTrie.normalize(raw);
    final corrected = apply(normalized);
    if (corrected == normalized) return [normalized];
    return [normalized, corrected];
  }
}
