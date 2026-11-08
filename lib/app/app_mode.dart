import 'package:flutter/foundation.dart';

/// Phase 2：DEMO／正常 模式。兩個模式 UI 同業務流程完全一樣，只係換資料／導航來源。
/// - DEMO：ChannelFixtureTransport（Kotlin fixture）+ FakeNavEngine，只限 debug build。
/// - 正常：跟 build flag（HKP_USE_REAL_API／HKP_USE_REAL_NAVIGATION）；release 一律 REAL+LIVE。
/// 冇靜默 fallback：畫面永遠顯示 [AppModeInfo.marker]，講明實際用緊邊個來源。
enum AppRunMode {
  demo('DEMO'),
  normal('NORMAL');

  const AppRunMode(this.wire);
  final String wire;

  static AppRunMode fromWire(String? raw) => raw == 'DEMO' ? AppRunMode.demo : AppRunMode.normal;
}

class AppModeInfo {
  const AppModeInfo({
    required this.mode,
    required this.liveApi,
    required this.realNavigation,
    required this.demoAllowed,
  });

  final AppRunMode mode;
  final bool liveApi;
  final bool realNavigation;
  final bool demoAllowed;

  bool get isDemo => mode == AppRunMode.demo;

  String get marker {
    final data = liveApi ? '即時數據' : '示範數據（fixture）';
    final nav = realNavigation ? 'Google 導航' : '模擬導航';
    return '${isDemo ? 'DEMO' : '正常'}｜$data｜$nav';
  }
}

/// release build 唔准 DEMO。測試可覆寫。
bool demoModeAllowed({bool? releaseOverride}) => !(releaseOverride ?? kReleaseMode);
