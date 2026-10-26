import 'package:copilot/core/constants/app_colors.dart';
import 'package:copilot/core/platform/overlay_channel.dart';
import 'package:copilot/features/trip_history/quick_feedback_bar.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

class TodayTripView {
  TodayTripView({
    required this.sessionId,
    required this.carparkId,
    required this.carparkName,
    required this.parkedAtEpochMs,
    required this.status,
    required this.confirmSyncPending,
    required this.feedbackSubmitted,
  });

  final String sessionId;
  final String carparkId;
  final String carparkName;
  final int parkedAtEpochMs;
  final String status;
  final bool confirmSyncPending;
  final bool feedbackSubmitted;

  static TodayTripView? fromMap(Map<Object?, Object?> map) {
    final sessionId = map['sessionId'] as String?;
    if (sessionId == null || sessionId.isEmpty) return null;
    return TodayTripView(
      sessionId: sessionId,
      carparkId: (map['carparkId'] as String?) ?? '',
      carparkName: (map['carparkName'] as String?) ?? '',
      parkedAtEpochMs: (map['parkedAtEpochMs'] as num?)?.toInt() ?? 0,
      status: (map['status'] as String?) ?? 'CANDIDATE',
      confirmSyncPending: map['confirmSyncPending'] == true,
      feedbackSubmitted: map['feedbackSubmitted'] == true,
    );
  }
}

class TodayTripScreen extends StatefulWidget {
  const TodayTripScreen({super.key});

  @override
  State<TodayTripScreen> createState() => _TodayTripScreenState();
}

class _TodayTripScreenState extends State<TodayTripScreen> {
  List<TodayTripView> _trips = const [];
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    OverlayChannel.attach();
    OverlayChannel.todayTripsListeners.add(_onTripsChanged);
    _reload();
  }

  @override
  void dispose() {
    // RT-7: always remove listener.
    OverlayChannel.todayTripsListeners.remove(_onTripsChanged);
    super.dispose();
  }

  void _onTripsChanged() {
    _reload();
  }

  Future<void> _reload() async {
    try {
      final raw = await OverlayChannel.getTodayTrips();
      final list = <TodayTripView>[];
      if (raw is List) {
        for (final item in raw) {
          if (item is Map) {
            final view = TodayTripView.fromMap(Map<Object?, Object?>.from(item));
            if (view != null) list.add(view);
          }
        }
      }
      if (!mounted) return;
      setState(() {
        _trips = list;
        _loading = false;
      });
    } on MissingPluginException {
      if (!mounted) return;
      setState(() {
        _trips = const [];
        _loading = false;
      });
    } on PlatformException {
      if (!mounted) return;
      setState(() {
        _trips = const [];
        _loading = false;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _trips = const [];
        _loading = false;
      });
    }
  }

  Future<void> _confirm(String sessionId) async {
    try {
      await OverlayChannel.confirmParkedCandidate(sessionId);
    } catch (_) {
      // Silent.
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.surface,
      appBar: AppBar(
        backgroundColor: AppColors.surface,
        title: const Text('今日行程', style: TextStyle(color: AppColors.textPrimary)),
        iconTheme: const IconThemeData(color: AppColors.textPrimary),
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _trips.isEmpty
              ? const Center(
                  child: Text(
                    '今日未有行程',
                    key: ValueKey('empty_today_trips'),
                    style: TextStyle(color: AppColors.textSecondary, fontSize: 18),
                  ),
                )
              : ListView.separated(
                  padding: const EdgeInsets.all(16),
                  itemCount: _trips.length,
                  separatorBuilder: (_, __) => const SizedBox(height: 12),
                  itemBuilder: (context, index) {
                    final trip = _trips[index];
                    return _TripCard(
                      trip: trip,
                      onConfirm: () => _confirm(trip.sessionId),
                      onFeedbackSubmitted: _reload,
                    );
                  },
                ),
    );
  }
}

class _TripCard extends StatelessWidget {
  const _TripCard({
    required this.trip,
    required this.onConfirm,
    required this.onFeedbackSubmitted,
  });

  final TodayTripView trip;
  final VoidCallback onConfirm;
  final VoidCallback onFeedbackSubmitted;

  @override
  Widget build(BuildContext context) {
    final isCandidate = trip.status == 'CANDIDATE';
    final isConfirmed = trip.status == 'CONFIRMED';
    return Material(
      color: AppColors.cardBackgroundSolid,
      borderRadius: BorderRadius.circular(12),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (isCandidate)
              Text(
                '可能已泊入 ${trip.carparkName}',
                key: ValueKey('candidate_${trip.sessionId}'),
                style: const TextStyle(color: AppColors.textPrimary, fontSize: 18),
              )
            else
              Text(
                trip.carparkName.isEmpty ? trip.carparkId : trip.carparkName,
                style: const TextStyle(color: AppColors.textPrimary, fontSize: 18),
              ),
            const SizedBox(height: 8),
            if (isCandidate)
              FilledButton(
                key: ValueKey('confirm_${trip.sessionId}'),
                onPressed: onConfirm,
                child: const Text('確認已泊妥'),
              ),
            if (isConfirmed && !trip.feedbackSubmitted)
              QuickFeedbackBar(
                key: ValueKey('feedback_${trip.sessionId}'),
                sessionId: trip.sessionId,
                carparkId: trip.carparkId,
                parkedAtEpochMs: trip.parkedAtEpochMs,
                onSubmitted: onFeedbackSubmitted,
              ),
            if (isConfirmed && trip.feedbackSubmitted)
              const Text(
                '已提交反饋',
                style: TextStyle(color: AppColors.textSecondary),
              ),
          ],
        ),
      ),
    );
  }
}
