import 'package:copilot/core/constants/app_colors.dart';
import 'package:flutter/material.dart';

class DestinationInputBar extends StatefulWidget {
  const DestinationInputBar({super.key, required this.onSubmit});

  final Future<void> Function(String raw) onSubmit;

  @override
  State<DestinationInputBar> createState() => _DestinationInputBarState();
}

class _DestinationInputBarState extends State<DestinationInputBar> {
  final TextEditingController _text = TextEditingController();
  bool _busy = false;

  @override
  void dispose() {
    _text.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      await widget.onSubmit(_text.text);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Material(
      color: AppColors.cardBackgroundSolid,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(12, 8, 12, 8),
        child: Row(
          children: [
            Expanded(
              child: TextField(
                key: const Key('destination_field'),
                controller: _text,
                style: const TextStyle(color: AppColors.textPrimary, fontSize: 18),
                decoration: const InputDecoration(
                  hintText: '終點',
                  hintStyle: TextStyle(color: AppColors.textSecondary),
                  border: InputBorder.none,
                ),
                textInputAction: TextInputAction.done,
                onSubmitted: (_) => _submit(),
              ),
            ),
            const SizedBox(width: 8),
            SizedBox(
              height: 48,
              child: FilledButton(
                key: const Key('destination_submit'),
                onPressed: _busy ? null : _submit,
                child: const Text('去'),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
