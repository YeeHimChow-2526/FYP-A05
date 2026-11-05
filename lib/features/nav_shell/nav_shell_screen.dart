import 'dart:async';

import 'package:copilot/core/constants/app_colors.dart';
import 'package:copilot/core/nav/nav_engine.dart';
import 'package:copilot/core/trip/trip_controller.dart';
import 'package:copilot/features/nav_shell/destination_input_bar.dart';
import 'package:copilot/features/nav_shell/recommend_cards_strip.dart';
import 'package:flutter/material.dart';

class NavShellScreen extends StatefulWidget {
  const NavShellScreen({super.key, required this.controller, required this.navEngine});

  final TripController controller;
  final NavEngine navEngine;

  @override
  State<NavShellScreen> createState() => _NavShellScreenState();
}

class _NavShellScreenState extends State<NavShellScreen> {
  bool _cardsOpen = true;
  bool _muted = false;

  @override
  void initState() {
    super.initState();
    unawaited(widget.navEngine.ensureInitialized());
  }

  Future<void> _toggleMute() async {
    final next = !_muted;
    setState(() => _muted = next);
    await widget.navEngine.setTurnByTurnAudioEnabled(!next);
  }

  @override
  Widget build(BuildContext context) {
    return StreamBuilder<TripSnapshot>(
      stream: widget.controller.changes,
      initialData: widget.controller.snapshot,
      builder: (context, snap) {
        final data = snap.data ?? widget.controller.snapshot;
        final origin = data.lastOriginLat == null ? null : '${data.lastOriginLat!.toStringAsFixed(5)}, ${data.lastOriginLng!.toStringAsFixed(5)}';
        final showCards = _cardsOpen && data.cards.isNotEmpty;
        return Scaffold(
          backgroundColor: AppColors.surface,
          body: Stack(
            children: [
              Positioned.fill(child: widget.navEngine.buildNavView()),
              SafeArea(
                child: Column(
                  children: [
                    DestinationInputBar(onSubmit: widget.controller.submitDestinationText),
                    if (origin != null)
                      Align(
                        alignment: Alignment.centerLeft,
                        child: Padding(
                          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
                          child: Text('起點 $origin', style: const TextStyle(color: AppColors.textSecondary, fontSize: 14)),
                        ),
                      ),
                    if (data.destinationName != null)
                      Align(
                        alignment: Alignment.centerLeft,
                        child: Padding(
                          padding: const EdgeInsets.symmetric(horizontal: 16),
                          child: Text('終點 ${data.destinationName}', style: const TextStyle(color: AppColors.textPrimary, fontSize: 14)),
                        ),
                      ),
                    const Spacer(),
                    if (showCards)
                      RecommendCardsStrip(
                        cards: data.cards,
                        onConfirm: (index) {
                          unawaited(widget.controller.confirmCandidate(index));
                        },
                      ),
                    _ChromeBar(
                      cardCount: data.cards.length,
                      cardsOpen: _cardsOpen,
                      muted: _muted,
                      onCompass: () {
                        unawaited(widget.navEngine.showRouteOverview());
                      },
                      onPark: () {
                        unawaited(widget.controller.requestRecommendation());
                      },
                      onCards: () => setState(() => _cardsOpen = !_cardsOpen),
                      onMute: () {
                        unawaited(_toggleMute());
                      },
                      onExit: () {
                        unawaited(widget.controller.abortTripByUser());
                      },
                    ),
                  ],
                ),
              ),
            ],
          ),
        );
      },
    );
  }
}

class _ChromeBar extends StatelessWidget {
  const _ChromeBar({
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
    return Material(
      color: AppColors.cardBackgroundSolid,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 8),
        child: Row(
          children: [
            _Hit(icon: Icons.explore, label: '羅盤', onTap: onCompass),
            _Hit(icon: Icons.local_parking, label: '搵位', onTap: onPark),
            _Hit(icon: cardsOpen ? Icons.expand_more : Icons.expand_less, label: '車場', badge: cardCount, onTap: onCards),
            _Hit(icon: muted ? Icons.volume_off : Icons.volume_up, label: muted ? '轉彎聲' : '靜音', onTap: onMute),
            _Hit(icon: Icons.close, label: '結束', onTap: onExit),
          ],
        ),
      ),
    );
  }
}

class _Hit extends StatelessWidget {
  const _Hit({required this.icon, required this.label, required this.onTap, this.badge});

  final IconData icon;
  final String label;
  final VoidCallback onTap;
  final int? badge;

  @override
  Widget build(BuildContext context) {
    return Expanded(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 2),
        child: Material(
          color: AppColors.surface,
          child: InkWell(
            onTap: onTap,
            child: ConstrainedBox(
              constraints: const BoxConstraints(minWidth: 48, minHeight: 48),
              child: Padding(
                padding: const EdgeInsets.symmetric(vertical: 6),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(icon, color: AppColors.textPrimary, size: 22),
                    Text(badge == null || badge == 0 ? label : '$label $badge', style: const TextStyle(color: AppColors.textPrimary, fontSize: 12)),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
