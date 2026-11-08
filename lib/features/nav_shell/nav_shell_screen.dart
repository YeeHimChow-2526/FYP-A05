import 'dart:async';

import 'package:copilot/core/constants/app_colors.dart';
import 'package:copilot/core/constants/app_config.dart';
import 'package:copilot/core/nav/nav_engine.dart';
import 'package:copilot/core/trip/trip_controller.dart';
import 'package:copilot/core/trip/trip_types.dart';
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
  bool _muted = false;
  final GlobalKey _bottomStackKey = GlobalKey();
  double? _reportedMapBottom;

  void _reportBottomObstruction() {
    if (!mounted) return;
    final box =
        _bottomStackKey.currentContext?.findRenderObject() as RenderBox?;
    if (box == null || !box.hasSize) return;
    final bottom = box.size.height + MediaQuery.paddingOf(context).bottom;
    if (_reportedMapBottom != null &&
        (bottom - _reportedMapBottom!).abs() < 0.5) {
      return;
    }
    _reportedMapBottom = bottom;
    unawaited(widget.navEngine.setMapPadding(EdgeInsets.only(bottom: bottom)));
  }

  @override
  void dispose() {
    unawaited(widget.navEngine.setMapPadding(EdgeInsets.zero));
    super.dispose();
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
        final origin = data.lastOriginLat == null
            ? null
            : '${data.lastOriginLat!.toStringAsFixed(5)}, ${data.lastOriginLng!.toStringAsFixed(5)}';
        final showCards = data.cardsOpen && data.cards.isNotEmpty;
        WidgetsBinding.instance.addPostFrameCallback(
          (_) => _reportBottomObstruction(),
        );
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
              Align(
                alignment: Alignment.centerLeft,
                child: Padding(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 16,
                    vertical: 4,
                  ),
                  child: Text(
                    _statusLine(data),
                    key: const Key('trip_status_line'),
                    style: const TextStyle(
                      color: AppColors.textSecondary,
                      fontSize: 13,
                    ),
                  ),
                ),
              ),
              Padding(
                padding: const EdgeInsets.symmetric(
                  horizontal: 12,
                  vertical: 2,
                ),
                child: Text(
                  navTargetLine(widget.controller),
                  key: const Key('nav_target_line'),
                  style: const TextStyle(
                    color: AppColors.textSecondary,
                    fontSize: 12,
                  ),
                ),
              ),
              if (widget.controller.navTarget?.state == NavTargetState.failed)
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 12),
                  child: Material(
                    key: const Key('route_failed_banner'),
                    color: AppColors.cardBackgroundSolid,
                    child: Padding(
                      padding: const EdgeInsets.all(8),
                      child: Text(
                        '去「${widget.controller.navTarget!.title}」嘅路線計算失敗'
                        '${widget.navEngine.lastRouteStatus == null ? '' : '（${widget.navEngine.lastRouteStatus}）'}'
                        '。請檢查網絡／定位，再撳去或者確認一次。',
                        style: const TextStyle(
                          color: AppColors.amberSteady,
                          fontSize: 13,
                        ),
                      ),
                    ),
                  ),
                ),
              const Spacer(),
              KeyedSubtree(
                key: const Key('in_trip_bottom_stack'),
                child: Column(
                  key: _bottomStackKey,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    if (showCards)
                      RecommendCardsStrip(
                        key: const Key('recommend_cards_strip'),
                        cards: data.cards,
                        pendingCarparkId: data.pendingSwitchCarparkId,
                        onConfirm: (index) {
                          unawaited(widget.controller.confirmCandidate(index));
                        },
                        onAcceptPending: () => unawaited(widget.controller.acceptPendingSwitch()),
                        onRejectPending: widget.controller.rejectPendingSwitch,
                      ),
                    InTripCircularChrome(
                      cardCount: data.cards.length,
                      cardsOpen: data.cardsOpen,
                      muted: _muted,
                      onCompass: () {
                        unawaited(widget.navEngine.showRouteOverview());
                      },
                      onPark: () {
                        unawaited(widget.controller.requestRecommendation());
                      },
                      onCards: widget.controller.toggleCards,
                      onMute: () {
                        unawaited(_toggleMute());
                      },
                      onExit: () {
                        unawaited(widget.controller.abortTripByUser());
                      },
                    ),
                    const SizedBox(
                      key: Key('nav_sdk_footer_clearance'),
                      height: AppConfig.inTripNavFooterClearance,
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

/// 就緒（1 km latch）同空位（pip）分開顯示；未有新鮮數據就講明，唔扮有空位。
String _statusLine(TripSnapshot data) {
  final ready = data.readinessLatched ? '已入 1 km 預備區' : '未到 1 km 預備區';
  final String vacancy;
  if (!data.vacancyFresh) {
    vacancy = data.cards.isEmpty ? '空位：未有數據' : '空位：資料未更新';
  } else {
    vacancy = switch (data.vacancyPip) {
      StatusPip.green => '空位：充足',
      StatusPip.yellow => '空位：緊湊',
      StatusPip.red => '空位：緊張（有後備）',
      StatusPip.none => '空位：未知',
    };
  }
  final release = switch (data.lockRelease) {
    LockReleaseState.none => '',
    LockReleaseState.pending => '｜舊車位釋放中',
    LockReleaseState.failed => '｜舊車位未釋放（會重試）',
    LockReleaseState.unsupported => '｜舊車位：伺服器唔支援即時釋放',
  };
  final voice = data.voiceInputBlocked == null
      ? ''
      : '｜語音停用（${data.voiceInputBlocked}），用手動掣';
  final issue = data.serverIssue == null ? '' : '｜${data.serverIssue!.message}';
  return '$ready｜$vacancy$release$voice$issue';
}

/// r7：細行診斷——目的地 vs 而家實際導航目標（只喺 SDK 回覆後先寫「導航緊」）＋最近確認結果。
String navTargetLine(TripController c) {
  final dest = c.tsm.destinationName;
  final t = c.navTarget;
  final String nav;
  if (t == null) {
    nav = '未開導航';
  } else {
    final kind = t.kind == NavTargetKind.carpark ? '停車場' : '目的地';
    nav = switch (t.state) {
      NavTargetState.starting => '計緊路線去$kind「${t.title}」',
      NavTargetState.guiding => '導航緊去$kind「${t.title}」',
      NavTargetState.failed => '去$kind「${t.title}」路線失敗',
    };
  }
  final note = c.confirmNote;
  return '目的地：${dest ?? '—'}｜$nav${note == null ? '' : '｜$note'}';
}
