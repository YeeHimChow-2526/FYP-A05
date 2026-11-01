enum EndpointReason { ok, blankUrl, blankKey, badUrl, unsupportedScheme, cleartextNotAllowed }

extension EndpointReasonWire on EndpointReason {
  String get wireName => switch (this) {
        EndpointReason.ok => 'OK',
        EndpointReason.blankUrl => 'BLANK_URL',
        EndpointReason.blankKey => 'BLANK_KEY',
        EndpointReason.badUrl => 'BAD_URL',
        EndpointReason.unsupportedScheme => 'UNSUPPORTED_SCHEME',
        EndpointReason.cleartextNotAllowed => 'CLEARTEXT_NOT_ALLOWED',
      };

  static EndpointReason fromWire(String raw) => switch (raw) {
        'OK' => EndpointReason.ok,
        'BLANK_URL' => EndpointReason.blankUrl,
        'BLANK_KEY' => EndpointReason.blankKey,
        'BAD_URL' => EndpointReason.badUrl,
        'UNSUPPORTED_SCHEME' => EndpointReason.unsupportedScheme,
        'CLEARTEXT_NOT_ALLOWED' => EndpointReason.cleartextNotAllowed,
        _ => throw ArgumentError('Unknown EndpointReason: $raw'),
      };
}

class EndpointVerdict {
  const EndpointVerdict(this.reason, {this.normalizedBaseUrl = '', this.host = ''});

  final EndpointReason reason;
  final String normalizedBaseUrl;
  final String host;

  bool get usable => reason == EndpointReason.ok;
}

class ApiEndpointPolicy {
  static const Set<String> localCleartextHosts = {'localhost', '127.0.0.1', '10.0.2.2'};

  static EndpointVerdict check(String baseUrl, String apiKey, bool allowLocalCleartext) {
    final raw = baseUrl.trim();
    if (raw.isEmpty) return const EndpointVerdict(EndpointReason.blankUrl);
    final uri = Uri.tryParse(raw);
    if (uri == null) return const EndpointVerdict(EndpointReason.badUrl);
    final scheme = uri.scheme.toLowerCase();
    if (scheme.isEmpty) return const EndpointVerdict(EndpointReason.badUrl);
    final host = uri.host.toLowerCase();
    if (host.isEmpty || uri.userInfo.isNotEmpty || uri.hasQuery || uri.hasFragment) {
      return const EndpointVerdict(EndpointReason.badUrl);
    }
    switch (scheme) {
      case 'https':
        break;
      case 'http':
        if (!allowLocalCleartext || !localCleartextHosts.contains(host)) {
          return EndpointVerdict(EndpointReason.cleartextNotAllowed, host: host);
        }
      default:
        return EndpointVerdict(EndpointReason.unsupportedScheme, host: host);
    }
    if (apiKey.trim().isEmpty) return EndpointVerdict(EndpointReason.blankKey, host: host);
    return EndpointVerdict(EndpointReason.ok, normalizedBaseUrl: raw.replaceAll(RegExp(r'/+$'), ''), host: host);
  }

  static String? joinUrl(String normalizedBaseUrl, String path, bool allowLocalCleartext) {
    if (!path.startsWith('/v1/') || path.contains('?') || path.contains('#')) return null;
    final full = '${normalizedBaseUrl.replaceAll(RegExp(r'/+$'), '')}/${path.replaceFirst(RegExp(r'^/+'), '')}';
    final uri = Uri.tryParse(full);
    if (uri == null) return null;
    final scheme = uri.scheme.toLowerCase();
    if (scheme.isEmpty) return null;
    final host = uri.host.toLowerCase();
    if (host.isEmpty || uri.userInfo.isNotEmpty) return null;
    switch (scheme) {
      case 'https':
        break;
      case 'http':
        if (!allowLocalCleartext || !localCleartextHosts.contains(host)) return null;
      default:
        return null;
    }
    return full;
  }
}
