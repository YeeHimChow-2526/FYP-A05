import 'dart:async';

import 'package:copilot/core/constants/app_colors.dart';
import 'package:copilot/core/nav/nav_engine.dart';
import 'package:copilot/core/trip/trip_controller.dart';
import 'package:copilot/features/nav_shell/destination_input_bar.dart';
import 'package:copilot/features/nav_shell/in_trip_circular_chrome.dart';
import 'package:copilot/features/nav_shell/recommend_cards_strip.dart';
import 'package:flutter/material.dart';

class NavShellScreen extends StatefulWidget {
  const NavShellScreen({
    super.key,
    required this.controller,
    required this.navEngine,
  });

  final TripController controller;
  final NavEngine navEngine;

  @override
  State<NavShellScreen> createState() => _NavShellScreenState();
}

class _NavShellScreenState extends State<NavShellScreen> {
  @override
  void initState() {
    super.initState();
    unawaited(widget.navEngine.ensureInitialized());
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.surface,
      body: Stack(
        children: [
          Positioned.fill(child: widget.navEngine.buildNavView()),
          InTripOverlay(
            controller: widget.controller,
            navEngine: widget.navEngine,
          ),
        ],
      ),
    );
  }
}

class InTripOverlay extends StatefulWidget {
  const InTripOverlay({
    super.key,
    required this.controller,
    required this.navEngine,
  });

  final TripController controller;
  final NavEngine navEngine;

  @override
  State<InTripOverlay> createState() => _InTripOverlayState();
}

class _InTripOverlayState extends State<InTripOverlay> {
  bool _cardsOpen = true;
  bool _muted = false;

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
        final origin = data.lastOriginLat == null
            ? null
            : '${data.lastOriginLat!.toStringAsFixed(5)}, ${data.lastOriginLng!.toStringAsFixed(5)}';
        final showCards = _cardsOpen && data.cards.isNotEmpty;
        return SafeArea(
          child: Column(
            children: [
              DestinationInputBar(
                onSubmit: widget.controller.submitDestinationText,
              ),
              if (origin != null)
                Align(
                  alignment: Alignment.centerLeft,
                  child: Padding(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 16,
                      vertical: 4,
                    ),
                    child: Text(
                      '起點 $origin',
                      style: const TextStyle(
                        color: AppColors.textSecondary,
                        fontSize: 14,
                      ),
                    ),
                  ),
                ),
              if (data.destinationName != null)
                Align(
                  alignment: Alignment.centerLeft,
                  child: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 16),
                    child: Text(
                      '終點 ${data.destinationName}',
                      style: const TextStyle(
                        color: AppColors.textPrimary,
                        fontSize: 14,
                      ),
                    ),
                  ),
                ),
              const Spacer(),
              if (showCards)
                RecommendCardsStrip(
                  key: const Key('recommend_cards_strip'),
                  cards: data.cards,
                  onConfirm: (index) {
                    unawaited(widget.controller.confirmCandidate(index));
                  },
                ),
              InTripCircularChrome(
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
        );
      },
    );
  }
}
