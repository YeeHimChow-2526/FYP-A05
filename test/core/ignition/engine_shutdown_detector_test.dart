import 'package:copilot/core/ignition/engine_shutdown_detector.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('first disconnect mints token; second signal does not', () {
    var now = 1000;
    final detector = EngineShutdownDetector(nowMs: () => now);
    final token = detector.onDisconnected(EngineSignal.power);
    expect(token, 1);
    expect(detector.isPending, isTrue);
    expect(detector.lastEventMs, 1000);
    now = 1500;
    expect(detector.onDisconnected(EngineSignal.btCar), isNull);
    expect(detector.isPending, isTrue);
    expect(detector.lastEventMs, 1500);
  });

  test('reconnect of all signals cancels pending token', () {
    final detector = EngineShutdownDetector(nowMs: () => 1);
    final token = detector.onDisconnected(EngineSignal.power)!;
    detector.onDisconnected(EngineSignal.btCar);
    detector.onReconnected(EngineSignal.power);
    expect(detector.isPending, isTrue);
    detector.onReconnected(EngineSignal.btCar);
    expect(detector.isPending, isFalse);
    expect(detector.confirm(token), isFalse);
  });

  test('wrong token does not confirm', () {
    final detector = EngineShutdownDetector(nowMs: () => 1);
    final token = detector.onDisconnected(EngineSignal.btCar)!;
    expect(detector.confirm(token + 9), isFalse);
    expect(detector.isPending, isTrue);
    expect(detector.confirm(token), isTrue);
    expect(detector.isPending, isFalse);
    expect(detector.confirm(token), isFalse);
  });

  test('reset clears pending without sleeping', () {
    final detector = EngineShutdownDetector(nowMs: () => 1);
    detector.onDisconnected(EngineSignal.power);
    detector.reset();
    expect(detector.isPending, isFalse);
    expect(detector.confirm(1), isFalse);
  });
}
