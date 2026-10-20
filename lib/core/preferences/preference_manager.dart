import 'package:copilot/core/platform/overlay_channel.dart';

enum DriverPreference {
  indoorOnly('INDOOR_ONLY'),
  smartHybrid('SMART_HYBRID'),
  metersFocus('METERS_FOCUS');

  const DriverPreference(this.code);

  final String code;

  static const DriverPreference defaultPreference = DriverPreference.smartHybrid;
  static const bool defaultHotwordEnabled = true;
}

class PreferenceManager {
  DriverPreference preference;
  bool hotwordEnabled;

  PreferenceManager({
    this.preference = DriverPreference.defaultPreference,
    this.hotwordEnabled = DriverPreference.defaultHotwordEnabled,
  });

  Future<void> setPreference(DriverPreference value) async {
    preference = value;
    await _sync();
  }

  Future<void> setHotwordEnabled(bool enabled) async {
    hotwordEnabled = enabled;
    await _sync();
  }

  Future<void> _sync() {
    return OverlayChannel.setPreferences(
      preference: preference.code,
      hotwordEnabled: hotwordEnabled,
    );
  }
}
