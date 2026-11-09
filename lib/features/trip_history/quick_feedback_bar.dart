import 'dart:convert';

import 'package:copilot/core/constants/app_colors.dart';
import 'package:copilot/core/constants/app_config.dart';
import 'package:copilot/core/trip/today_trip_sync.dart';
import 'package:flutter/material.dart';

/// Three-slot feedback bar for a confirmed today-trip row (RT-7 StatefulWidget).
class QuickFeedbackBar extends StatefulWidget {
  const QuickFeedbackBar({
    super.key,
    required this.sessionId,
    required this.carparkId,
    required this.parkedAtEpochMs,
    this.sync,
    this.onSubmitted,
  });

  final String sessionId;
  final String carparkId;
  final int parkedAtEpochMs;
  final TodayTripSync? sync;
  final VoidCallback? onSubmitted;

  @override
  State<QuickFeedbackBar> createState() => _QuickFeedbackBarState();
}

class _QuickFeedbackBarState extends State<QuickFeedbackBar> {
  String? _selectedRatingTag;
  String? _selectedPerkStatusTag;
  late final TextEditingController _notesController;
  bool _submitting = false;
  String? _failureText;

  @override
  void initState() {
    super.initState();
    _notesController = TextEditingController();
  }

  @override
  void dispose() {
    _notesController.dispose();
    super.dispose();
  }

  bool get _canSubmit =>
      !_submitting &&
      _selectedRatingTag != null &&
      _selectedPerkStatusTag != null;

  Future<void> _submit() async {
    if (!_canSubmit) return;
    setState(() => _submitting = true);
    final notes = _notesController.text;
    final truncated = notes.length > AppConfig.tripPerkNotesMaxChars
        ? notes.substring(0, AppConfig.tripPerkNotesMaxChars)
        : notes;
    final payload = <String, dynamic>{
      'session_id': widget.sessionId,
      'carpark_id': widget.carparkId,
      'rating_tag': _selectedRatingTag,
      'perk_status_tag': _selectedPerkStatusTag,
      'perk_notes': truncated,
      'trip_timestamp': widget.parkedAtEpochMs ~/ 1000,
    };
    // RT-3: snake_case JSON; no photo_url key.
    final json = jsonEncode(payload);
    try {
      final outcome = await widget.sync?.submitReport(json);
      if (outcome != null && outcome.posted) {
        widget.onSubmitted?.call();
      } else if (mounted) {
        setState(() => _failureText = feedbackFailureText(outcome?.reason));
      }
    } catch (_) {
      if (mounted) setState(() => _failureText = feedbackFailureText('NETWORK_OR_HTTP'));
    } finally {
      if (mounted) {
        setState(() => _submitting = false);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            for (final tag in AppConfig.tripRatingTags)
              ChoiceChip(
                key: ValueKey('rating_$tag'),
                label: Text(tag),
                selected: _selectedRatingTag == tag,
                onSelected: (_) {
                  setState(() => _selectedRatingTag = tag);
                },
              ),
          ],
        ),
        const SizedBox(height: 8),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            for (final tag in AppConfig.tripPerkStatusTags)
              ChoiceChip(
                key: ValueKey('perk_$tag'),
                label: Text(tag),
                selected: _selectedPerkStatusTag == tag,
                onSelected: (_) {
                  setState(() => _selectedPerkStatusTag = tag);
                },
              ),
          ],
        ),
        const SizedBox(height: 8),
        TextField(
          key: const ValueKey('perk_notes'),
          controller: _notesController,
          maxLength: AppConfig.tripPerkNotesMaxChars,
          style: const TextStyle(color: AppColors.textPrimary),
          decoration: const InputDecoration(
            hintText: '備註（選填）',
            hintStyle: TextStyle(color: AppColors.textSecondary),
            counterStyle: TextStyle(color: AppColors.textSecondary),
          ),
        ),
        if (_failureText != null)
          Padding(
            padding: const EdgeInsets.only(bottom: 8),
            child: Text(
              _failureText!,
              key: const ValueKey('feedback_failure'),
              style: const TextStyle(color: AppColors.textSecondary),
            ),
          ),
        Align(
          alignment: Alignment.centerRight,
          child: FilledButton(
            key: const ValueKey('submit_feedback'),
            onPressed: _canSubmit ? () { _submit(); } : null,
            child: const Text('提交反饋'),
          ),
        ),
      ],
    );
  }
}
