import 'jyutping_table.g.dart';

class JyutpingTable {
  JyutpingTable(this._readings);

  final Map<String, String> _readings;

  static final JyutpingTable generated = JyutpingTable(_parsePacked(kJyutpingPacked));

  int get length => _readings.length;

  String? reading(String char) => _readings[char];

  bool contains(String char) => _readings.containsKey(char);

  static Map<String, String> _parsePacked(String packed) {
    final map = <String, String>{};
    for (final line in packed.split('\n')) {
      if (line.isEmpty) continue;
      final chars = line.runes.toList();
      if (chars.isEmpty) continue;
      final ch = String.fromCharCode(chars.first);
      map[ch] = String.fromCharCodes(chars.skip(1));
    }
    return map;
  }
}

class LazySound {
  // 真機調校前預設
  static const String nToL = 'n-→l-';
  static const String ngInitialDrop = 'ng-→';
  static const String gwToG = 'gw-→g-';
  static const String kwToK = 'kw-→k-';
  static const String ngFinalToN = '-ng→-n';
  static const String kFinalToT = '-k→-t';

  static String normalizeSyllable(String raw) {
    var syl = raw;
    if (syl == 'ng') return 'ng';
    if (syl.startsWith('ng')) {
      syl = syl.substring(2);
    } else if (syl.startsWith('n')) {
      syl = 'l${syl.substring(1)}';
    }
    if (syl.startsWith('gw')) syl = 'g${syl.substring(2)}';
    if (syl.startsWith('kw')) syl = 'k${syl.substring(2)}';
    if (syl.endsWith('ng') && syl != 'ng') syl = '${syl.substring(0, syl.length - 2)}n';
    if (syl.endsWith('k')) syl = '${syl.substring(0, syl.length - 1)}t';
    return syl;
  }

  static List<String>? syllables(String hans, JyutpingTable table) {
    final out = <String>[];
    for (final rune in hans.runes) {
      final ch = String.fromCharCode(rune);
      if (!_isHan(rune)) continue;
      final reading = table.reading(ch);
      if (reading == null || reading.isEmpty) return null;
      out.add(normalizeSyllable(reading));
    }
    return out;
  }

  static bool _isHan(int rune) => rune >= 0x4E00 && rune <= 0x9FFF;
}
