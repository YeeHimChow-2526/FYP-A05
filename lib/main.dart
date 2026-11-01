import 'package:copilot/app/trip_runtime.dart';
import 'package:copilot/core/constants/app_colors.dart';
import 'package:copilot/core/platform/overlay_channel.dart';
import 'package:copilot/features/ignition/ignition_screen.dart';
import 'package:flutter/material.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  OverlayChannel.attach();
  await TripRuntime.instance.attach();
  runApp(const CopilotApp());
}

class CopilotApp extends StatelessWidget {
  const CopilotApp({super.key});

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
      home: const IgnitionScreen(),
    );
  }
}
