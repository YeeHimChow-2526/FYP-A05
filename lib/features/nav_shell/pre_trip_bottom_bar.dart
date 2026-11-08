import 'package:copilot/core/constants/app_colors.dart';
import 'package:flutter/material.dart';

/// Phase 2：地圖分頁嘅行動列。主按鈕「出發」（A-2）；次按鈕「現場即泊」（StartupEntry.onSite）。
/// 今日行程已搬去底部分頁，唔再喺呢度。
class PreTripBottomBar extends StatelessWidget {
  const PreTripBottomBar({
    super.key,
    required this.onDepart,
    required this.onHere,
  });

  final VoidCallback onDepart;
  final VoidCallback onHere;

  @override
  Widget build(BuildContext context) {
    return Material(
      key: const Key('map_actions'),
      color: AppColors.cardBackgroundSolid,
      borderRadius: BorderRadius.circular(12),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(12, 8, 12, 8),
        child: Row(
          children: [
            Expanded(
              flex: 2,
              child: FilledButton(
                key: const Key('map_depart'),
                onPressed: onDepart,
                child: const Text('出發'),
              ),
            ),
            const SizedBox(width: 8),
            Expanded(
              child: OutlinedButton(
                key: const Key('map_on_site'),
                onPressed: onHere,
                child: const Text('現場即泊'),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
