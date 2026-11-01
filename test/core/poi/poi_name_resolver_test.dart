import 'dart:io';

import 'package:copilot/core/poi/jyutping.dart';
import 'package:copilot/core/poi/local_poi_trie.dart';
import 'package:copilot/core/poi/poi_name_resolver.dart';
import 'package:copilot/core/poi/stt_typo.dart';
import 'package:copilot/core/poi/trie_result.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  final trie = LocalPoiTrie.fromJsonString(File('assets/poi/hk_poi_master.json').readAsStringSync());
  final typo = SttTypoCorpus.fromJsonString(File('assets/poi/stt_typo_corpus.json').readAsStringSync());

  test('strips leading go and typo-corrects langham', () {
    final resolver = PoiNameResolver(trie: trie, typo: typo);
    expect((resolver.resolve('去朗豪坊') as Hit).poi.id, 'ig:560878');
    expect((resolver.resolve('導航去朗豪坊') as Hit).poi.id, 'ig:560878');
    expect((resolver.resolve('朗豪方') as Hit).poi.id, 'ig:560878');
  });

  test('jyutping path hits langham after dropping typo entry', () {
    final resolver = PoiNameResolver(trie: trie, typo: typo.without('狼毫坊'));
    final result = resolver.resolve('狼毫坊');
    expect(result, isA<Hit>());
    expect((result as Hit).poi.id, 'ig:560878');
    expect(result, isNot(isA<Ambiguous>()));
  });

  test('single han never fuzzy', () {
    final resolver = PoiNameResolver(trie: trie, typo: SttTypoCorpus(const {}));
    final result = resolver.resolve('坊');
    expect(result is Ambiguous && (result as Ambiguous).isFuzzy, isFalse);
  });

  test('nfkc and punctuation strip before lookup', () {
    expect((trie.lookup('「海港城」') as Hit).poi.id, 'ig:530031');
    expect((trie.lookup('ＭＫ') as Hit).poi.type, 'A');
  });

  test('fuzzy top2 is ambiguous', () {
    final tiny = LocalPoiTrie.fromJsonString(
      '{"v":1,"attribution":"x","districts":[],"pois":['
      '{"id":"a","t":"M","zh":"甲乙","en":"","a":[],"d":"甲","la":22.3,"lo":114.1},'
      '{"id":"b","t":"M","zh":"丙丁","en":"","a":[],"d":"乙","la":22.3,"lo":114.1}'
      ']}',
    );
    final table = JyutpingTable({'甲': 'aa', '乙': 'bb', '丙': 'aa', '丁': 'bb', '戊': 'aa', '己': 'xx'});
    final resolver = PoiNameResolver(trie: tiny, typo: SttTypoCorpus(const {}), table: table);
    final result = resolver.resolve('戊己');
    expect(result, isA<Ambiguous>());
    final ambiguous = result as Ambiguous;
    expect(ambiguous.isFuzzy, isTrue);
    expect(ambiguous.candidates, hasLength(2));
  });
}
