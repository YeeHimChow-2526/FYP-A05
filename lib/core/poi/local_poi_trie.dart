import 'dart:convert';

import 'poi.dart';
import 'trie_result.dart';

class _Node {
  final Map<String, _Node> children = {};
  List<Poi>? pois;
}

class LocalPoiTrie {
  LocalPoiTrie._(this._root, this.size, this.attribution, this.exactKeys);

  final _Node _root;
  final int size;
  final String attribution;
  final Map<String, List<Poi>> exactKeys;

  static const int _minPrefixKeyLen = 2;
  static const int _maxAmbiguous = 3;
  static const List<String> _typePriority = ['A', 'M', 'C', 'D'];
  static const List<String> _suffixes = [
    '停車場',
    '停車塲',
    '購物中心',
    'shoppingcentre',
    'shoppingmall',
    'carparks',
    'carpark',
    'parking',
    '商場',
    'mall',
  ];

  static final RegExp _punct = RegExp(r'[\p{P}\p{Z}\p{S}]', unicode: true);

  TrieResult lookup(String query) {
    final key = normalize(query);
    if (key.isEmpty) return trieMiss;
    var node = _root;
    List<Poi>? best;
    final chars = key.characters;
    for (var i = 0; i < chars.length; i++) {
      final next = node.children[chars[i]];
      if (next == null) break;
      node = next;
      final depth = i + 1;
      final here = node.pois;
      if (here != null && (depth == chars.length || depth >= _minPrefixKeyLen)) best = here;
    }
    if (best == null) return trieMiss;
    return _resolve(best);
  }

  TrieResult _resolve(List<Poi> candidates) {
    final bestType = _typePriority.firstWhere((type) => candidates.any((poi) => poi.type == type));
    final group = candidates.where((poi) => poi.type == bestType).toList();
    final byDistrict = <String, List<Poi>>{};
    for (final poi in group) {
      byDistrict.putIfAbsent(poi.district, () => []).add(poi);
    }
    if (byDistrict.length == 1) {
      group.sort((a, b) => a.id.compareTo(b.id));
      return Hit(group.first);
    }
    final districts = byDistrict.keys.toList()..sort();
    final picks = <Poi>[];
    for (final district in districts) {
      final rows = byDistrict[district]!..sort((a, b) => a.id.compareTo(b.id));
      picks.add(rows.first);
      if (picks.length == _maxAmbiguous) break;
    }
    return Ambiguous(picks);
  }

  static String normalize(String input) {
    final folded = _foldWidth(input).toLowerCase();
    final stripped = folded.replaceAll(_punct, '');
    for (final suffix in _suffixes) {
      if (stripped.endsWith(suffix)) {
        final next = stripped.substring(0, stripped.length - suffix.length);
        return next.isEmpty ? stripped : next;
      }
    }
    return stripped;
  }

  static String _foldWidth(String input) {
    final out = StringBuffer();
    for (final rune in input.runes) {
      if (rune >= 0xFF01 && rune <= 0xFF5E) {
        out.writeCharCode(rune - 0xFEE0);
      } else if (rune == 0x3000) {
        out.writeCharCode(0x20);
      } else {
        out.writeCharCode(rune);
      }
    }
    return out.toString();
  }

  static LocalPoiTrie fromJsonString(String text) {
    final decoded = jsonDecode(text);
    if (decoded is! Map) {
      throw ArgumentError('hk_poi_master.json: invalid JSON');
    }
    final attribution = decoded['attribution'] is String ? decoded['attribution'] as String : '';
    final rawPois = decoded['pois'];
    if (rawPois is! List) throw ArgumentError('hk_poi_master.json: missing pois');
    final root = _Node();
    final exact = <String, List<Poi>>{};
    var count = 0;
    for (final raw in rawPois) {
      if (raw is! Map) throw ArgumentError('hk_poi_master.json: poi not an object');
      final id = raw['id'];
      final type = raw['t'];
      if (id is! String || id.isEmpty) throw ArgumentError('poi without id');
      if (type is! String || type.isEmpty) throw ArgumentError('poi without t');
      final poi = Poi(
        id: id,
        type: type.substring(0, 1),
        zh: raw['zh'] is String ? raw['zh'] as String : '',
        en: raw['en'] is String ? raw['en'] as String : '',
        aliases: raw['a'] is List ? (raw['a'] as List).whereType<String>().toList() : const [],
        district: raw['d'] is String ? raw['d'] as String : '',
        lat: (raw['la'] as num).toDouble(),
        lng: (raw['lo'] as num).toDouble(),
      );
      count += 1;
      final keys = <String>{};
      for (final rawKey in [poi.zh, poi.en, ...poi.aliases]) {
        if (rawKey.isEmpty) continue;
        final key = normalize(rawKey);
        if (key.isNotEmpty) keys.add(key);
      }
      for (final key in keys) {
        _insert(root, key, poi);
        final bucket = exact.putIfAbsent(key, () => []);
        if (bucket.every((item) => item.id != poi.id)) bucket.add(poi);
      }
    }
    return LocalPoiTrie._(root, count, attribution, exact);
  }

  static void _insert(_Node root, String key, Poi poi) {
    var node = root;
    for (final ch in key.characters) {
      node = node.children.putIfAbsent(ch, _Node.new);
    }
    final list = node.pois ??= <Poi>[];
    if (list.every((item) => item.id != poi.id)) list.add(poi);
  }
}

extension on String {
  List<String> get characters {
    final out = <String>[];
    for (final rune in runes) {
      out.add(String.fromCharCodes([rune]));
    }
    return out;
  }
}
