import 'package:flutter/widgets.dart';

abstract class NavEngine {
  Widget buildNavView({Key? key});

  Future<bool> ensureInitialized();

  Future<void> syncUserLocation(double lat, double lng);

  Future<bool> startGuidanceTo({
    required String title,
    required double lat,
    required double lng,
  });

  Future<void> setTurnByTurnAudioEnabled(bool enabled);

  /// 確認口播期間暫停 SDK 導航聲。唔改用戶靜音選擇；plugin 0.11.2 冇音量檔，只能靜音。
  Future<void> suspendTurnByTurnAudio() async {}

  /// 口播完結後按用戶原本的開關恢復 SDK 導航聲。
  Future<void> resumeTurnByTurnAudio() async {}

  Future<void> showRouteOverview();

  /// 地圖四邊留白（邏輯像素）。底部用來避開行程掣；原生 ETA footer 唔跟呢個 padding 上移。
  Future<void> setMapPadding(EdgeInsets padding) async {}

  Future<void> stopGuidance();

  /// 位置權限批咗之後（出發流程／返回 App）再試：初始化導航 session、開返「我的位置」同導航 UI。
  Future<void> onLocationPermissionGranted() async {}

  /// 最近一次路線結果（只作診斷／顯示；冇就 null）。
  String? get lastRouteStatus => null;
}
