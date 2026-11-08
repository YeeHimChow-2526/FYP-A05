import '../net/recommendation_card.dart';
import 'trip_types.dart';

/// 空位 Status Pip（同 1 km 就緒綠燈分開）。門檻只用現有參數表：
/// ARCHITECTURE §4 `LOW_WATERMARK_VACANCIES=3`（伺服器低水位＝「緊張已備後備」→ 紅）。
/// 參數表冇「黃／緊湊」門檻，所以呢度唔會出黃（唔自創常數）；過期或 -1（未知）→ none，唔扮新鮮空位。
class VacancyPip {
  static const int LOW_WATERMARK_VACANCIES = 3; // ignore: constant_identifier_names

  static StatusPip forLead(List<RecommendationCard> cards) {
    if (cards.isEmpty) return StatusPip.none;
    final lead = cards.where((card) => card.isViable && !card.demonstrationSupply).firstOrNull;
    if (lead == null) return StatusPip.none;
    if (lead.isStale || lead.vacancies < 0) return StatusPip.none;
    return lead.vacancies <= LOW_WATERMARK_VACANCIES ? StatusPip.red : StatusPip.green;
  }

  static bool isFresh(List<RecommendationCard> cards) {
    final lead = cards.where((card) => card.isViable && !card.demonstrationSupply).firstOrNull;
    return lead != null && !lead.isStale && lead.vacancies >= 0;
  }
}
