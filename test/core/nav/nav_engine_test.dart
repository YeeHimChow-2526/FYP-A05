import 'dart:convert';

import 'package:copilot/core/nav/fake_nav_engine.dart';
import 'package:copilot/core/nav/gate_coord_resolver.dart';
import 'package:copilot/core/net/recommendation_card.dart';
import 'package:copilot/core/poi/local_poi_trie.dart';
import 'package:flutter_test/flutter_test.dart';

RecommendationCard _card({
  String id = 'CP_X',
  String name = '場',
  double? gateLat,
  double? gateLng,
}) {
  return RecommendationCard(
    carparkId: id,
    name: name,
    type: 'INDOOR',
    vacancies: 1,
    distanceMeters: 10,
    feeSummary: '',
    spokenCue: '',
    isStale: false,
    gateLat: gateLat,
    gateLng: gateLng,
  );
}

void main() {
  test('N-T1 fake nav records guidance mute and stop in order', () async {
    final nav = FakeNavEngine();
    expect(await nav.ensureInitialized(), isTrue);
    await nav.syncUserLocation(22.3186, 114.1686);
    expect(await nav.startGuidanceTo(title: '朗豪坊', lat: 22.31822, lng: 114.16848), isTrue);
    await nav.setTurnByTurnAudioEnabled(false);
    await nav.showRouteOverview();
    await nav.stopGuidance();
    expect(nav.calls, [
      'ensureInitialized',
      'syncUserLocation',
      'setDestinations',
      'startGuidance',
      'mute',
      'showRouteOverview',
      'stopGuidance',
    ]);
    expect(nav.guidanceRunning, isFalse);
    expect(nav.turnByTurnAudioEnabled, isFalse);
    expect(nav.userLat, 22.3186);
  });

  test('gate resolver uses card then demo then trie then destination', () {
    final trie = LocalPoiTrie.fromJsonString(jsonEncode({
      'pois': [
        {
          'id': 'poi-1',
          't': 'C',
          'zh': '自訂場',
          'en': 'Custom',
          'a': <String>[],
          'd': 'YTM',
          'la': 22.2,
          'lo': 114.1,
        },
      ],
    }));
    final cardHit = GateCoordResolver.resolve(_card(gateLat: 22.1, gateLng: 114.2), trie: trie, fallbackLat: 1, fallbackLng: 2);
    expect(cardHit.layer, 'card');
    expect(cardHit.lat, 22.1);
    final demo = GateCoordResolver.resolve(_card(id: 'CP_LANGHAM', name: '自訂場'), trie: trie, fallbackLat: 1, fallbackLng: 2);
    expect(demo.layer, 'demo');
    expect(demo.lat, isNot(22.2));
    final named = GateCoordResolver.resolve(_card(id: 'UNKNOWN', name: '自訂場'), trie: trie, fallbackLat: 1, fallbackLng: 2);
    expect(named.layer, 'trie');
    expect(named.lat, 22.2);
    final dest = GateCoordResolver.resolve(_card(id: 'UNKNOWN', name: '冇'), trie: trie, fallbackLat: 3, fallbackLng: 4);
    expect(dest.layer, 'dest');
    expect(dest.lat, 3);
    expect(kDemoCarparkCoords['CP_LANGHAM'], isNotNull);
    expect(kDemoCarparkCoords.values.map((item) => '${item.lat},${item.lng}').toSet(), hasLength(kDemoCarparkCoords.length));
  });
}
