// lib/core/constants/app_config.dart（Flutter App 內畫面：設定、新手教學、今日行程；只讀原生狀態）
class AppConfig {
  static const int permissionTutorialPages = 6;          // 教學卡片總頁數 (含麥克風本地收音說明頁、語音優先示範動畫頁與授權步驟頁)
  static const String todayTripsResetTimeHkt = "00:00";  // 今日行程（含待確認）清除時間（香港時間；與原生 TodayTripStore 一致）
}
