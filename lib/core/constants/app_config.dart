// lib/core/constants/app_config.dart（Flutter App 內畫面：設定、新手教學、今日行程；只讀原生狀態）
class AppConfig {
  static const int permissionTutorialPages = 6; // onboarding page count
  static const String todayTripsResetTimeHkt = "00:00"; // HKT；同 TodayTripStore
  static const List<String> tripRatingTags = ['GOOD', 'OK', 'BAD'];
  static const List<String> tripPerkStatusTags = ['VALID', 'INVALID', 'NONE'];
  static const int tripPerkNotesMaxChars = 140;

  /// 行程中圓形掣下面留白（邏輯像素），避開原生 Navigation SDK 底部 ETA footer。
  /// GoogleMap.setPadding 只移 logo／重新置中，唔會移動 ETA 卡。
  static const double inTripNavFooterClearance = 48;
}
