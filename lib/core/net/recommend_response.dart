import '../constants/native_config.dart';
import '../voice/zh_hk_templates.dart';
import 'recommendation_card.dart';

class RecommendResponse {
  const RecommendResponse({
    required this.status,
    this.speechLocale = NativeConfig.SPEECH_LOCALE_DEFAULT,
    this.speechLocaleFallback = false,
    this.sourceStatus = 'ok',
    this.sourceSnapshotTs = '',
    this.processedAt = '',
    this.serverNow = '',
    this.dataAgeSeconds = 0,
    this.cards = const [],
    this.awaitVoiceReply = false,
    this.spokenCue,
  });

  final String status;
  final String speechLocale;
  final bool speechLocaleFallback;
  final String sourceStatus;
  final String sourceSnapshotTs;
  final String processedAt;
  final String serverNow;
  final int dataAgeSeconds;
  final List<RecommendationCard> cards;
  final bool awaitVoiceReply;

  /// 頂層 spoken_cue（例如冇 viable 卡時嘅擴大範圍提問）。
  final String? spokenCue;

  List<RecommendationCard> sanitizedCards() {
    return cards.map((card) {
      if (card.type == 'METER' && (card.isStale || sourceStatus == 'degraded')) {
        return card.copyWith(vacancies: -1, spokenCue: ZhHkTemplates.METER_STALE_SPOKEN);
      }
      return card;
    }).toList();
  }

  RecommendResponse copyWithCards(List<RecommendationCard> next) {
    return RecommendResponse(
      status: status,
      speechLocale: speechLocale,
      speechLocaleFallback: speechLocaleFallback,
      sourceStatus: sourceStatus,
      sourceSnapshotTs: sourceSnapshotTs,
      processedAt: processedAt,
      serverNow: serverNow,
      dataAgeSeconds: dataAgeSeconds,
      cards: next,
      awaitVoiceReply: awaitVoiceReply,
      spokenCue: spokenCue,
    );
  }
}
