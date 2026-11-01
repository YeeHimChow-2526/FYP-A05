class Poi {
  const Poi({
    required this.id,
    required this.type,
    required this.zh,
    required this.en,
    required this.aliases,
    required this.district,
    required this.lat,
    required this.lng,
  });

  final String id;
  final String type;
  final String zh;
  final String en;
  final List<String> aliases;
  final String district;
  final double lat;
  final double lng;
}
