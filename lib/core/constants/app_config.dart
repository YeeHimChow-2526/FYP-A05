/// Flutter App 內畫面常數 — 來源：實作版 Doc v2.9.1 PART 2 §二.1
/// 行車常數一律見原生 NativeConfig.kt（Kotlin 為狀態機唯一擁有者）。
// （Flutter App 內畫面：設定、新手教學、今日行程；只讀原生狀態）
class AppConfig {
  static const int permissionTutorialPages = 6;          // 教學卡片總頁數 (含麥克風本地收音說明頁、語音優先示範動畫頁與授權步驟頁)
  static const String todayTripsResetTimeHkt = "00:00";  // 今日行程（含待確認）清除時間（香港時間；與原生 TodayTripStore 一致）
}
