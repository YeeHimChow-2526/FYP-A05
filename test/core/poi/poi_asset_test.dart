import 'dart:convert';
import 'dart:io';

import 'package:copilot/core/poi/local_poi_trie.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('asset is capped, byte-equal, and has 1199 pois', () {
    final flutter = File('assets/poi/hk_poi_master.json');
    final android = File('android/app/src/main/assets/hk_poi_master.json');
    final flutterBytes = flutter.readAsBytesSync();
    final androidBytes = android.readAsBytesSync();
    expect(flutterBytes.length, lessThanOrEqualTo(153600));
    expect(flutterBytes, androidBytes);
    final doc = jsonDecode(utf8.decode(flutterBytes)) as Map<String, Object?>;
    expect(doc['v'], 1);
    expect((doc['attribution'] as String).isNotEmpty, isTrue);
    expect((doc['districts'] as List).length, 18);
    expect((doc['pois'] as List).length, 1199);
    final trie = LocalPoiTrie.fromJsonString(utf8.decode(flutterBytes));
    expect(trie.size, 1199);
    expect(trie.attribution, doc['attribution']);
  });
}
