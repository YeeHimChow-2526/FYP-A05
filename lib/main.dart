import 'package:copilot/app/trip_runtime.dart';
import 'package:copilot/core/constants/app_colors.dart';
import 'package:copilot/core/platform/overlay_channel.dart';
import 'package:copilot/features/ignition/ignition_screen.dart';
import 'package:copilot/features/trip_history/feedback_fixture_app.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

Future<void> main(List<String> args) async {
  WidgetsFlutterBinding.ensureInitialized();
  if (kDebugMode && args.contains(feedbackFixtureArg)) {
    runApp(const FeedbackFixtureApp());
    return;
  }
  OverlayChannel.attach();
  Object? attachError;
  try {
    await TripRuntime.instance.attach();
  } catch (error) {
    attachError = error;
  }
  runApp(CopilotApp(attachError: attachError));
}

class CopilotApp extends StatefulWidget {
  const CopilotApp({super.key, this.attachError});

  final Object? attachError;

  @override
  State<CopilotApp> createState() => _CopilotAppState();
}

class _CopilotAppState extends State<CopilotApp> {
  Object? _error;

  @override
  void initState() {
    super.initState();
    _error = widget.attachError;
  }

  Future<void> _retry() async {
    try {
      await TripRuntime.instance.attach();
      if (mounted) setState(() => _error = null);
    } catch (error) {
      if (mounted) setState(() => _error = error);
    }
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: '泊車副駕',
      theme: ThemeData(
        brightness: Brightness.dark,
        scaffoldBackgroundColor: AppColors.surface,
        colorScheme: const ColorScheme.dark(
          surface: AppColors.surface,
          primary: AppColors.amberSteady,
          onPrimary: AppColors.surface,
          onSurface: AppColors.textPrimary,
        ),
      ),
      home: _error == null
          ? const IgnitionScreen()
          : Scaffold(
              backgroundColor: AppColors.surface,
              body: Center(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text('未能連接行程', style: TextStyle(color: AppColors.textPrimary, fontSize: 20)),
                    const SizedBox(height: 12),
                    Text('$_error', style: TextStyle(color: AppColors.textSecondary)),
                    const SizedBox(height: 16),
                    FilledButton(onPressed: _retry, child: const Text('重試')),
                  ],
                ),
              ),
            ),
    );
  }
}
