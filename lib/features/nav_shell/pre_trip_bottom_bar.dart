import 'package:copilot/core/constants/app_colors.dart';
import 'package:flutter/material.dart';

class PreTripBottomBar extends StatelessWidget {
  const PreTripBottomBar({
    super.key,
    required this.onDepart,
    required this.onHere,
    required this.onToday,
  });

  final VoidCallback onDepart;
  final VoidCallback onHere;
  final VoidCallback onToday;

  @override
  Widget build(BuildContext context) {
    return Material(
      key: const Key('pre_trip_bottom_bar'),
      color: AppColors.cardBackgroundSolid,
      child: SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(12, 8, 12, 8),
          child: Row(
            children: [
              Expanded(
                child: FilledButton(
                  onPressed: onDepart,
                  child: const Text('出發'),
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: FilledButton(
                  onPressed: onHere,
                  child: const Text('現場即泊'),
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: OutlinedButton(
                  key: const ValueKey('open_today_trips'),
                  onPressed: onToday,
                  child: const Text('今日行程'),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
