import 'package:copilot/core/constants/app_colors.dart';
import 'package:copilot/core/net/recommendation_card.dart';
import 'package:flutter/material.dart';

const _consentButtonStyle = ButtonStyle(
  visualDensity: VisualDensity.compact,
  tapTargetSize: MaterialTapTargetSize.shrinkWrap,
  padding: WidgetStatePropertyAll(EdgeInsets.symmetric(horizontal: 8)),
);

class RecommendCardsStrip extends StatelessWidget {
  const RecommendCardsStrip({
    super.key,
    required this.cards,
    required this.onConfirm,
    this.pendingCarparkId,
    this.onAcceptPending,
    this.onRejectPending,
  });

  final List<RecommendationCard> cards;
  final ValueChanged<int> onConfirm;

  /// 第 3 次起等同意嗰間。確認／取消放喺原本嗰張卡，唔另開一張卡。
  final String? pendingCarparkId;
  final VoidCallback? onAcceptPending;
  final VoidCallback? onRejectPending;

  @override
  Widget build(BuildContext context) {
    if (cards.isEmpty) return const SizedBox.shrink();
    return SizedBox(
      height: 200,
      child: ListView.separated(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        scrollDirection: Axis.horizontal,
        itemCount: cards.length,
        separatorBuilder: (_, _) => const SizedBox(width: 8),
        itemBuilder: (context, index) {
          final card = cards[index];
          final staleMeter = card.type == 'METER' && card.isStale;
          final staleKnown = card.isStale && card.type != 'METER' && card.vacancies >= 0;
          final vacancy = card.demonstrationSupply
              ? '示範數據，唔係即時供應'
              : staleKnown
                  ? '最後已知空位 ${card.vacancies}（未更新，唔保證）'
                  : switch (card.availability) {
            CardAvailability.noAvailability => '暫時冇位（只供參考）',
            CardAvailability.lastKnownZero => '最後已知冇位（而家未知）',
            CardAvailability.unconfirmed when card.type != 'METER' =>
              '空位未確認，唔保證有位',
            _ =>
              staleMeter || card.vacancies < 0
                  ? '咪錶空位未確認，唔保證有位'
                  : '空位 ${card.vacancies}',
          };
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
                    Text(
                      card.name.isEmpty ? card.carparkId : card.name,
                      style: const TextStyle(
                        color: AppColors.textPrimary,
                        fontSize: 16,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      vacancy,
                      style: const TextStyle(color: AppColors.textSecondary),
                    ),
                    Text(
                      [
                        '${card.distanceMeters} m',
                        if (card.isFallback) '咪錶後備',
                      ].join('・'),
                      style: const TextStyle(color: AppColors.textSecondary),
                    ),
                    Text(
                      card.feeSummary,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(color: AppColors.textSecondary),
                    ),
                    if (card.isStale || card.legacyContract)
                      Text(
                        [if (card.isStale) '資料未更新', if (card.legacyContract) '舊版伺服器'].join('・'),
                        style: const TextStyle(color: AppColors.alertBreathing),
                      ),
                    const Spacer(),
                    SizedBox(
                      height: 40,
                      width: double.infinity,
                      child: card.carparkId == pendingCarparkId
                          ? Row(
                              children: [
                                Expanded(
                                  child: FilledButton(
                                    key: const Key('switch_consent_confirm'),
                                    style: _consentButtonStyle,
                                    onPressed: onAcceptPending,
                                    child: const Text('確認'),
                                  ),
                                ),
                                const SizedBox(width: 8),
                                Expanded(
                                  child: OutlinedButton(
                                    key: const Key('switch_consent_cancel'),
                                    style: _consentButtonStyle,
                                    onPressed: onRejectPending,
                                    child: const Text('取消'),
                                  ),
                                ),
                              ],
                            )
                          : FilledButton(
                              key: Key('confirm_card_$index'),
                              onPressed: card.commitAllowed ? () => onConfirm(index) : null,
                              child: Text(
                                card.demonstrationSupply
                                    ? '示範數據，唔可以預留'
                                    : card.commitAllowed
                                        ? '確認去呢個'
                                        : card.availability == CardAvailability.unconfirmed
                                            ? '空位未確認，唔可以預留'
                                            : '只供參考，唔可以預留',
                              ),
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
