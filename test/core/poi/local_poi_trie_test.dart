import 'dart:io';

import 'package:copilot/core/constants/native_config.dart';
import 'package:copilot/core/poi/local_poi_trie.dart';
import 'package:copilot/core/poi/poi.dart';
import 'package:copilot/core/poi/trie_result.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  final text = File('android/app/src/main/assets/hk_poi_master.json').readAsStringSync();
  final trie = LocalPoiTrie.fromJsonString(text);

  test('langham suffix and mong kok aliases', () {
    final hit = trie.lookup('朗豪坊') as Hit;
    expect(hit.poi.id, startsWith('ig:'));
    expect(hit.poi.type, 'M');
    expect(hit.poi.district, '油尖旺');
    final area = trie.lookup('旺角') as Hit;
    expect(area.poi.type, 'A');
    for (final query in ['MK', 'mk', 'ＭＫ', 'Mong Kok']) {
      expect((trie.lookup(query) as Hit).poi.id, area.poi.id, reason: query);
    }
  });

  test('suffix strip, miss, and inline rules', () {
    expect((trie.lookup('天晴邨第一期停車場') as Hit).poi.id, 'td:tdc166p1');
    expect((trie.lookup('天晴邨第一期') as Hit).poi.id, 'td:tdc166p1');
    expect(trie.lookup('火星停車場'), isA<Miss>());
    expect(trie.lookup(''), isA<Miss>());
    expect(LocalPoiTrie.normalize('朗豪坊停車場'), '朗豪坊');
    expect(LocalPoiTrie.normalize('Langham Place Carpark'), 'langhamplace');
    expect(LocalPoiTrie.normalize('停車場'), '停車場');

    final ambiguous = LocalPoiTrie.fromJsonString(_inline([
      _poi('pn:1', 'A', '太平', '西貢'),
      _poi('pn:2', 'A', '太平', '北'),
    ]));
    final rows = (ambiguous.lookup('太平') as Ambiguous).candidates;
    expect(rows.map((poi) => poi.district).toList(), ['北', '西貢']);
    expect((rows as dynamic), isNotNull);

    final areaBeats = LocalPoiTrie.fromJsonString(_inline([
      _poi('td:x1', 'C', '大角咀', '油尖旺'),
      _poi('pn:9', 'A', '大角咀', '油尖旺'),
    ]));
    expect((areaBeats.lookup('大角咀') as Hit).poi.id, 'pn:9');

    final smaller = LocalPoiTrie.fromJsonString(_inline([
      _poi('td:b', 'C', '海富停車場', '中西'),
      _poi('td:a', 'C', '海富停車場', '中西'),
    ]));
    expect((smaller.lookup('海富') as Hit).poi.id, 'td:a');

    final prefix = LocalPoiTrie.fromJsonString(_inline([
      _poi('pn:1', 'A', '旺角', '油尖旺'),
      _poi('pn:2', 'A', '旺', '油尖旺'),
    ]));
    expect((prefix.lookup('旺角道口') as Hit).poi.id, 'pn:1');
    expect(prefix.lookup('旺x'), isA<Miss>());
  });

  test('lookup stays inside budget', () {
    final started = DateTime.now();
    final fresh = LocalPoiTrie.fromJsonString(text);
    final loadMs = DateTime.now().difference(started).inMilliseconds;
    expect(loadMs, lessThan(1500));
    final queries = ['朗豪坊', '旺角', 'MK', '火星停車場'];
    final sw = Stopwatch()..start();
    for (var i = 0; i < 200; i++) {
      fresh.lookup(queries[i % queries.length]);
    }
    sw.stop();
    expect(sw.elapsedMilliseconds / 200, lessThan(NativeConfig.TRIE_LOOKUP_BUDGET_MS));
  });
}

String _poi(String id, String type, String zh, String district) =>
    '{"id":"$id","t":"$type","zh":"$zh","en":"","a":[],"d":"$district","la":22.3,"lo":114.1}';

String _inline(List<String> pois) => '{"v":1,"attribution":"x","districts":[],"pois":[${pois.join(',')}]}';
