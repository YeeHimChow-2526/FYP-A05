class ParsedShare {
  const ParsedShare({
    required this.title,
    required this.lat,
    required this.lng,
    required this.hasUrl,
  });

  final String? title;
  final double? lat;
  final double? lng;
  final bool hasUrl;

  bool get isEmpty => title == null && lat == null && !hasUrl;
  bool get hasPin => lat != null && lng != null;
}

class ShareTextParser {
  static final RegExp _url = RegExp(r'https?://\S+', caseSensitive: false);
  static final RegExp _coord = RegExp(r'(-?\d+\.\d{3,})\s*,\s*(-?\d+\.\d{3,})');

  static ParsedShare parse(String? text, {String? subject}) {
    final rawText = text ?? '';
    final rawSubject = subject ?? '';
    final hasUrl = _url.hasMatch(rawText) || _url.hasMatch(rawSubject);
    final pin = _firstValidCoord(rawText) ?? _firstValidCoord(rawSubject);
    final title = _extractTitle(rawText) ?? _extractTitle(rawSubject);
    return ParsedShare(title: title, lat: pin?.$1, lng: pin?.$2, hasUrl: hasUrl);
  }

  static (double, double)? _firstValidCoord(String source) {
    for (final match in _coord.allMatches(source)) {
      final lat = double.tryParse(match.group(1)!);
      final lng = double.tryParse(match.group(2)!);
      if (lat == null || lng == null) continue;
      if (lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180) {
        return (lat, lng);
      }
    }
    return null;
  }

  static String? _extractTitle(String source) {
    if (source.trim().isEmpty) return null;
    var cleaned = source.replaceAll(_url, ' ');
    cleaned = cleaned.replaceAll(_coord, ' ');
    for (final line in cleaned.split(RegExp(r'\r\n|\n|\r'))) {
      final trimmed = line.trim();
      if (trimmed.isNotEmpty) return trimmed;
    }
    return null;
  }
}
