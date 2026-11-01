import 'dart:io';

import 'package:copilot/core/poi/local_poi_trie.dart';
import 'package:copilot/core/poi/poi_name_resolver.dart';
import 'package:copilot/core/poi/stt_typo.dart';
import 'package:copilot/core/poi/trie_result.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  final trie = LocalPoiTrie.fromJsonString(File('assets/poi/hk_poi_master.json').readAsStringSync());
  final typo = SttTypoCorpus.fromJsonString(File('assets/poi/stt_typo_corpus.json').readAsStringSync());
  final resolver = PoiNameResolver(trie: trie, typo: typo);

  test('normalize already resolves spaced latin', () {
    expect((trie.lookup('A P M') as Hit).poi.id, 'ig:558653');
    expect((trie.lookup('I F C') as Hit).poi.id, 'ig:548350');
    expect((trie.lookup('ＡＰＭ') as Hit).poi.id, 'ig:558653');
  });

  test('required seeds hit expected ids', () {
    const expected = {
      '太古成': 'ig:533687',
      '朗豪方': 'ig:560878',
      '狼毫坊': 'ig:560878',
      '海港成': 'ig:530031',
      '時代廣塲': 'ig:529300',
      '時代講場': 'ig:529300',
      '又一成': 'ig:530276',
      '原方': 'ig:541705',
      '玄方': 'ig:541705',
      'k一一': 'ig:21137900',
      'k十一': 'ig:21137900',
    };
    for (final entry in expected.entries) {
      final result = resolver.resolve(entry.key);
      expect(result, isA<Hit>(), reason: entry.key);
      expect((result as Hit).poi.id, entry.value, reason: entry.key);
    }
    expect(typo.entries.length, greaterThanOrEqualTo(11));
  });
}
