import 'package:copilot/core/constants/native_config.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('mirror keeps Kotlin names and values', () {
    expect(NativeConfig.API_TIMEOUT_MS, 2500);
    expect(NativeConfig.API_MAX_ATTEMPTS, 3);
    expect(NativeConfig.API_RETRY_INTERVAL_MS, 1000);
    expect(NativeConfig.RATE_LIMIT_RETRY_DELAY_MS, 5000);
    expect(NativeConfig.RATE_LIMIT_MAX_WAIT_MS, 10000);
    expect(NativeConfig.RATE_LIMIT_RETRY_AFTER_MIN_S, 1);
    expect(NativeConfig.RATE_LIMIT_RETRY_AFTER_MAX_S, 10);
    expect(NativeConfig.MAX_RESPONSE_BYTES, 256 * 1024);
    expect(NativeConfig.ENGINE_OFF_DEBOUNCE_MS, 2000);
    expect(NativeConfig.ENGINE_OFF_MAX_SPEED_KMH, 8);
    expect(NativeConfig.SPEECH_LOCALE_DEFAULT, 'zh-HK');
    expect(NativeConfig.TRIGGER_DISTANCE_M, 1000);
    expect(NativeConfig.APPROACH_RADIUS_M, 150);
    expect(NativeConfig.APPROACH_CORE_RADIUS_M, 80);
    expect(NativeConfig.APPROACH_SPEED_THRESHOLD_KMH, 8);
    expect(NativeConfig.APPROACH_CONSECUTIVE_S, 3);
    expect(NativeConfig.APPROACH_EXIT_DISTANCE_M, 200);
    expect(NativeConfig.APPROACH_EXIT_HOLD_S, 10);
  });
}
