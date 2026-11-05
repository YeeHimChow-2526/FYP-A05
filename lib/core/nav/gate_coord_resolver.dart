import '../net/recommendation_card.dart';
import '../poi/local_poi_trie.dart';
import '../poi/trie_result.dart';

class GateCoord {
  const GateCoord(this.lat, this.lng, this.layer);

  final double lat;
  final double lng;
  final String layer;
}

const Map<String, GateCoord> kDemoCarparkCoords = {
  'CP_LANGHAM': GateCoord(22.31822, 114.16848, 'demo'),
  'CP_APM': GateCoord(22.31255, 114.22512, 'demo'),
  'CP_INDOOR_A': GateCoord(22.28120, 114.15540, 'demo'),
  'CP_INDOOR_B': GateCoord(22.33710, 114.17480, 'demo'),
  'CP_SCARCE_ONLY': GateCoord(22.29840, 114.17220, 'demo'),
  'CP_SCARCE_EXPANDED': GateCoord(22.30510, 114.18860, 'demo'),
};

class GateCoordResolver {
  static GateCoord resolve(
    RecommendationCard card, {
    LocalPoiTrie? trie,
    double? fallbackLat,
    double? fallbackLng,
  }) {
    final gateLat = card.gateLat;
    final gateLng = card.gateLng;
    if (gateLat != null && gateLng != null) {
      return GateCoord(gateLat, gateLng, 'card');
    }
    final demo = kDemoCarparkCoords[card.carparkId];
    if (demo != null) return demo;
    final name = card.name.trim();
    if (trie != null && name.isNotEmpty) {
      final hit = trie.lookup(name);
      if (hit is Hit) return GateCoord(hit.poi.lat, hit.poi.lng, 'trie');
    }
    return GateCoord(fallbackLat ?? 0, fallbackLng ?? 0, 'dest');
  }
}
