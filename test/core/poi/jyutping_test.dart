import 'dart:convert';
import 'dart:io';

import 'package:copilot/core/poi/jyutping.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('poi hans are mapped and table stays under 60KB', () {
    final file = File('lib/core/poi/jyutping_table.g.dart');
    expect(file.lengthSync(), lessThanOrEqualTo(60 * 1024));
    final table = JyutpingTable.generated;
    expect(table.length, greaterThanOrEqualTo(3000));
    expect(table.reading('狼'), isNotNull);
    expect(table.reading('毫'), isNotNull);
    final doc = jsonDecode(File('assets/poi/hk_poi_master.json').readAsStringSync()) as Map<String, Object?>;
    final hans = <String>{};
    for (final raw in doc['pois']! as List) {
      final poi = raw as Map<String, Object?>;
      final blob = '${poi['zh']}${poi['en']}${poi['d']}${(poi['a'] as List).join()}';
      for (final rune in blob.runes) {
        if (rune >= 0x4E00 && rune <= 0x9FFF) hans.add(String.fromCharCode(rune));
      }
    }
    final missing = hans.where((ch) => !table.contains(ch)).toList();
    expect(missing, isEmpty);
  });

  test('lazy sound rules', () {
    expect(LazySound.normalizeSyllable('naa'), 'laa');
    expect(LazySound.normalizeSyllable('ng'), 'ng');
    expect(LazySound.normalizeSyllable('ngo'), 'o');
    expect(LazySound.normalizeSyllable('gwaa'), 'gaa');
    expect(LazySound.normalizeSyllable('kwaa'), 'kaa');
    expect(LazySound.normalizeSyllable('saang'), 'saan');
    expect(LazySound.normalizeSyllable('baak'), 'baat');
  });
}
