// ignore_for_file: constant_identifier_names

import '../constants/native_config.dart';
import '../net/recommendation_card.dart';

enum TripVoiceMode { fullVoice, announceManual, manualOnly }

class ZhHkTemplates {
  static String get speechLocale => NativeConfig.SPEECH_LOCALE_DEFAULT;

  static const String WAKE_ACK = '喺度，想去邊度？';
  static const String NO_MORE_OPTIONS = '暫時冇其他選擇';
  static const String PLACE_CLARIFY = '邊一區？';
  static const String SILENCE_DRIVE = '出發緊？去邊度等我睇定車位？';
  static const String NETWORK_SLOW = '網絡有啲慢，我再試吓';
  static const String NETWORK_FAILED = '網絡唔順，遲啲再試';
  static const String B1_DESTINATION_MISSED = '聽唔到目的地，請再講一次';
  static const String ON_SITE_LOCATION_MISSED = '暫時搵唔到你位置，請稍後再講一次';
  static const String CROWD_YELLOW_FLAG = '近期有司機回報疑似霸位，請留意現場';
  static const String CROWD_SINGLE_LABEL = '⚠️ 近期有司機回報疑似霸位';
  static const String CHRONIC_HOTSPOT_LABEL = '⚠️ 常有雪糕筒霸位';
  static const String METER_STALE_SPOKEN = '附近可能有咪錶，資料暫時未更新';
  static const String METER_STALE_UI = '咪錶資料暫時未更新';
  static const String LOW_CONFIDENCE = '太嘈聽唔清，稍後再試';
  static const String ASK_DESTINATION = '去邊度？';
  static const String TAP_CAPSULE = '請拍一拍膠囊答我';
  static const String NOTIFICATION_BODY = '🅿️ 需要搵車位就撳我';
  static const String NOTIFICATION_ACTION = '開始泊車助手（啟用語音）';
  static const String OFFLINE_STALE_NOTICE = '冇網，數字唔係最新';

  static String directLock(String name) => '收到，為你鎖定$name，搜尋緊周邊車位。';

  static String implicitEcho(String districtFeature, String fullName) =>
      '收到，為你鎖定【$districtFeature】[$fullName]，搜尋緊周邊車位。';

  static String implicitLockEcho(String? districtShort, String fullName) {
    if (districtShort == null || districtShort.trim().isEmpty) {
      return '收到，為你鎖定$fullName，搜尋緊周邊車位。';
    }
    return '收到，為你鎖定$districtShort區$fullName，搜尋緊周邊車位。';
  }

  static String permissionRepair(String missing) => '泊車副駕需要補授權：$missing。請泊好車後再處理。';

  static String withVoiceReplyPrompt(String cue, TripVoiceMode voiceMode, bool isMicFgsActive) {
    if (voiceMode != TripVoiceMode.fullVoice || isMicFgsActive) return cue;
    if (cue.endsWith(TAP_CAPSULE)) return cue;
    return cue + TAP_CAPSULE;
  }

  static String cardDetails(RecommendationCard card) {
    if (card.feeSummary.trim().isNotEmpty) return '${card.name}，${card.feeSummary}';
    return card.name;
  }
}
