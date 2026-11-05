import 'package:copilot/core/constants/app_colors.dart';
import 'package:copilot/core/net/recommendation_card.dart';
import 'package:copilot/core/voice/zh_hk_templates.dart';
import 'package:flutter/material.dart';

class RecommendCardsStrip extends StatelessWidget {
  const RecommendCardsStrip({super.key, required this.cards, required this.onConfirm});

  final List<RecommendationCard> cards;
  final ValueChanged<int> onConfirm;

  @override
  Widget build(BuildContext context) {
    if (cards.isEmpty) return const SizedBox.shrink();
    return SizedBox(
      height: 168,
      child: ListView.separated(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        scrollDirection: Axis.horizontal,
        itemCount: cards.length,
        separatorBuilder: (_, _) => const SizedBox(width: 8),
        itemBuilder: (context, index) {
          final card = cards[index];
          final staleMeter = card.type == 'METER' && card.isStale;
          final vacancy = staleMeter || card.vacancies < 0 ? ZhHkTemplates.METER_STALE_UI : '空位 ${card.vacancies}';
          return Material(
            key: Key('recommend_card_$index'),
            color: AppColors.cardBackgroundSolid,
            child: SizedBox(
              width: 240,
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(card.name.isEmpty ? card.carparkId : card.name, style: const TextStyle(color: AppColors.textPrimary, fontSize: 16)),
                    const SizedBox(height: 4),
                    Text(vacancy, style: const TextStyle(color: AppColors.textSecondary)),
                    Text('${card.distanceMeters} m', style: const TextStyle(color: AppColors.textSecondary)),
                    Text(card.feeSummary, style: const TextStyle(color: AppColors.textSecondary)),
                    if (card.isStale) const Text('資料未更新', style: TextStyle(color: AppColors.alertBreathing)),
                    const Spacer(),
                    SizedBox(
                      height: 40,
                      width: double.infinity,
                      child: FilledButton(
                        key: Key('confirm_card_$index'),
                        onPressed: () => onConfirm(index),
                        child: const Text('確認去呢個'),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          );
        },
      ),
    );
  }
}
