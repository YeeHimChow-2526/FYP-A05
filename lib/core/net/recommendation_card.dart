/// 伺服器 `availability`（cloud `app/scoring.py`）。舊後端冇呢欄 → [CardAvailability.deriveLegacy]（保守推斷）。
/// 只有 viable（AVAILABLE／STALE_ESTIMATE／UNCONFIRMED）可以做首選；LAST_KNOWN_ZERO／NO_AVAILABILITY 係參考卡。
class CardAvailability {
  static const String available = 'AVAILABLE';
  static const String staleEstimate = 'STALE_ESTIMATE';
  static const String unconfirmed = 'UNCONFIRMED';
  static const String lastKnownZero = 'LAST_KNOWN_ZERO';
  static const String noAvailability = 'NO_AVAILABILITY';
  static const Set<String> viable = {available, staleEstimate, unconfirmed};
  static const Set<String> committable = {available, staleEstimate};

  /// 舊後端（冇 availability）：缺資料一律唔當有位。過期／-1 → UNCONFIRMED；新鮮 0 → NO_AVAILABILITY；
  /// 新鮮正數先當 AVAILABLE（卡上仍標「舊版伺服器」）。
  static String deriveLegacy({required int vacancies, required bool isStale}) {
    if (isStale || vacancies < 0) return unconfirmed;
    if (vacancies == 0) return noAvailability;
    return available;
  }
}

class RecommendationCard {
  const RecommendationCard({
    required this.carparkId,
    required this.name,
    required this.type,
    required this.vacancies,
    required this.distanceMeters,
    required this.feeSummary,
    required this.spokenCue,
    required this.isStale,
    this.gateLat,
    this.gateLng,
    this.availability = CardAvailability.available,
    this.commitAllowed = true,
    this.isFallback = false,
    this.legacyContract = false,
    this.demonstrationSupply = false,
  });

  final String carparkId;
  final String name;
  final String type;
  final int vacancies;
  final int distanceMeters;
  final String feeSummary;
  final String spokenCue;
  final bool isStale;
  final double? gateLat;
  final double? gateLng;
  final String availability;
  final bool commitAllowed;
  final bool isFallback;

  /// 伺服器冇送 availability／commit_allowed（舊合約）；UI 要顯示出嚟。
  final bool legacyContract;

  /// 正常模式底下嘅示範供應（fixture id、DEMO_ 前綴、費用或名稱標 DEMO）。唔當即時真實空位。
  final bool demonstrationSupply;

  /// 可以做首選（UNCONFIRMED 都算，但唔一定可以預留：睇 [commitAllowed]）。
  bool get isViable => CardAvailability.viable.contains(availability);

  RecommendationCard copyWith({
    int? vacancies,
    String? spokenCue,
    bool? commitAllowed,
    bool? demonstrationSupply,
  }) {
    return RecommendationCard(
      carparkId: carparkId,
      name: name,
      type: type,
      vacancies: vacancies ?? this.vacancies,
      distanceMeters: distanceMeters,
      feeSummary: feeSummary,
      spokenCue: spokenCue ?? this.spokenCue,
      isStale: isStale,
      gateLat: gateLat,
      gateLng: gateLng,
      availability: availability,
      commitAllowed: commitAllowed ?? this.commitAllowed,
      isFallback: isFallback,
      legacyContract: legacyContract,
      demonstrationSupply: demonstrationSupply ?? this.demonstrationSupply,
    );
  }
}
