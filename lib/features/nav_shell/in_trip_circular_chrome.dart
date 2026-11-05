import 'package:copilot/core/constants/app_colors.dart';
import 'package:flutter/material.dart';

class InTripCircularChrome extends StatelessWidget {
  const InTripCircularChrome({
    super.key,
    required this.cardCount,
    required this.cardsOpen,
    required this.muted,
    required this.onCompass,
    required this.onPark,
    required this.onCards,
    required this.onMute,
    required this.onExit,
  });

  final int cardCount;
  final bool cardsOpen;
  final bool muted;
  final VoidCallback onCompass;
  final VoidCallback onPark;
  final VoidCallback onCards;
  final VoidCallback onMute;
  final VoidCallback onExit;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(8, 0, 8, 8),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceEvenly,
        children: [
          _CircleButton(
            buttonKey: const Key('chrome_compass'),
            tooltip: '羅盤',
            icon: Icons.explore,
            onTap: onCompass,
          ),
          _CircleButton(
            buttonKey: const Key('chrome_park'),
            tooltip: '搵位',
            icon: Icons.local_parking,
            onTap: onPark,
          ),
          _CircleButton(
            buttonKey: const Key('chrome_cards'),
            tooltip: '車場',
            icon: cardsOpen ? Icons.expand_more : Icons.expand_less,
            badge: cardCount,
            onTap: onCards,
          ),
          _CircleButton(
            buttonKey: const Key('chrome_mute'),
            tooltip: muted ? '轉彎聲' : '靜音',
            icon: muted ? Icons.volume_off : Icons.volume_up,
            onTap: onMute,
          ),
          _CircleButton(
            buttonKey: const Key('chrome_exit'),
            tooltip: '結束',
            icon: Icons.close,
            onTap: onExit,
          ),
        ],
      ),
    );
  }
}

class _CircleButton extends StatelessWidget {
  const _CircleButton({
    required this.buttonKey,
    required this.tooltip,
    required this.icon,
    required this.onTap,
    this.badge,
  });

  final Key buttonKey;
  final String tooltip;
  final IconData icon;
  final VoidCallback onTap;
  final int? badge;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: 48,
      height: 48,
      child: Stack(
        clipBehavior: Clip.none,
        children: [
          Material(
            color: AppColors.cardBackground,
            shape: const CircleBorder(),
            child: IconButton(
              key: buttonKey,
              tooltip: tooltip,
              onPressed: onTap,
              icon: Icon(icon, color: AppColors.textPrimary),
            ),
          ),
          if (badge != null && badge! > 0)
            Positioned(
              right: -2,
              top: -2,
              child: IgnorePointer(
                child: DecoratedBox(
                  decoration: const BoxDecoration(
                    color: AppColors.amberSteady,
                    shape: BoxShape.circle,
                  ),
                  child: Padding(
                    padding: const EdgeInsets.all(4),
                    child: Text(
                      '$badge',
                      style: const TextStyle(color: AppColors.surface, fontSize: 10),
                    ),
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}
