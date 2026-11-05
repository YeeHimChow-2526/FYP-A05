import 'package:copilot/app/trip_runtime.dart';
import 'package:copilot/features/ignition/ignition_screen.dart';
import 'package:copilot/platform/trip_platform_channel.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

Future<void> _attach(WidgetTester tester) {
  return tester.runAsync(() => TripRuntime.instance.attach());
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('F-T1 pre-trip shows HK map and bottom bar', (tester) async {
    final channel = MethodChannel(ChannelTripPlatform.name);
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (call) async => null);
    await _attach(tester);
    await tester.pumpWidget(const MaterialApp(home: IgnitionScreen()));
    await tester.pumpAndSettle(const Duration(milliseconds: 50));
    expect(find.byKey(const Key('home_map_backdrop')), findsOneWidget);
    expect(find.byKey(const Key('fake_nav_view')), findsOneWidget);
    expect(find.textContaining('22.3193, 114.1694'), findsWidgets);
    expect(find.byKey(const Key('pre_trip_bottom_bar')), findsOneWidget);
    expect(find.byKey(const Key('chrome_park')), findsNothing);
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
  });

  testWidgets('C19 onFgsStopped returns pre-trip bar and keeps map', (tester) async {
    final channel = MethodChannel(ChannelTripPlatform.name);
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (call) async {
      if (call.method == 'startTripFgs') return true;
      return null;
    });
    await _attach(tester);
    await tester.pumpWidget(const MaterialApp(home: IgnitionScreen()));
    await tester.pumpAndSettle(const Duration(milliseconds: 50));
    expect(find.byKey(const Key('pre_trip_bottom_bar')), findsOneWidget);
    expect(find.byKey(const Key('fake_nav_view')), findsOneWidget);

    await tester.tap(find.widgetWithText(FilledButton, '出發'));
    await tester.pump();
    await tester.runAsync(() async {
      await Future<void>.delayed(const Duration(milliseconds: 200));
    });
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('chrome_exit')), findsOneWidget);
    expect(find.byKey(const Key('pre_trip_bottom_bar')), findsNothing);
    expect(find.byKey(const Key('fake_nav_view')), findsOneWidget);

    const codec = StandardMethodCodec();
    await tester.binding.defaultBinaryMessenger.handlePlatformMessage(
      ChannelTripPlatform.name,
      codec.encodeMethodCall(const MethodCall('onFgsStopped', {'reason': 'TEST'})),
      (_) {},
    );
    await tester.pump();
    expect(find.byKey(const Key('pre_trip_bottom_bar')), findsOneWidget);
    expect(find.byKey(const Key('fake_nav_view')), findsOneWidget);
    expect(find.byKey(const Key('chrome_exit')), findsNothing);

    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
  });
}
