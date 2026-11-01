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
  });

  final String carparkId;
  final String name;
  final String type;
  final int vacancies;
  final int distanceMeters;
  final String feeSummary;
  final String spokenCue;
  final bool isStale;

  RecommendationCard copyWith({int? vacancies, String? spokenCue}) {
    return RecommendationCard(
      carparkId: carparkId,
      name: name,
      type: type,
      vacancies: vacancies ?? this.vacancies,
      distanceMeters: distanceMeters,
      feeSummary: feeSummary,
      spokenCue: spokenCue ?? this.spokenCue,
      isStale: isStale,
    );
  }
}
