import 'package:copilot/features/ignition/ignition_screen.dart';
import 'package:copilot/platform/trip_platform_channel.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('C19 onFgsStopped shows trip not started', (tester) async {
    final channel = MethodChannel(ChannelTripPlatform.name);
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'startTripFgs') return true;
      return null;
    });
    await tester.pumpWidget(const MaterialApp(home: IgnitionScreen()));
    expect(find.text('未開程'), findsOneWidget);

    await tester.ensureVisible(find.widgetWithText(FilledButton, '出發'));
    await tester.tap(find.widgetWithText(FilledButton, '出發'));
    await tester.pump();
    await tester.runAsync(() async {
      await Future<void>.delayed(const Duration(milliseconds: 200));
    });
    await tester.pumpAndSettle();
    expect(find.text('已開程'), findsOneWidget);

    const codec = StandardMethodCodec();
    await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
      ChannelTripPlatform.name,
      codec.encodeMethodCall(const MethodCall('onFgsStopped', {'reason': 'TEST'})),
      (_) {},
    );
    await tester.pump();
    expect(find.text('未開程'), findsOneWidget);
    expect(find.text('已開程'), findsNothing);

    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
  });
}
