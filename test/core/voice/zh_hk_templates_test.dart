import 'package:copilot/core/constants/native_config.dart';
import 'package:copilot/core/net/recommendation_card.dart';
import 'package:copilot/core/voice/zh_hk_templates.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('keeps old cues and adds offline notice', () {
    expect(ZhHkTemplates.speechLocale, NativeConfig.SPEECH_LOCALE_DEFAULT);
    expect(ZhHkTemplates.NETWORK_SLOW, '網絡有啲慢，我再試吓');
    expect(ZhHkTemplates.NETWORK_FAILED, '網絡唔順，遲啲再試');
    expect(ZhHkTemplates.METER_STALE_SPOKEN, '附近可能有咪錶，資料暫時未更新');
    expect(ZhHkTemplates.TAP_CAPSULE, '請拍一拍膠囊答我');
    expect(ZhHkTemplates.OFFLINE_STALE_NOTICE, '冇網，數字唔係最新');
    expect(ZhHkTemplates.directLock('朗豪坊'), '收到，為你鎖定朗豪坊，搜尋緊周邊車位。');
    expect(ZhHkTemplates.implicitLockEcho(null, '朗豪坊'), '收到，為你鎖定朗豪坊，搜尋緊周邊車位。');
    expect(ZhHkTemplates.implicitLockEcho('旺角', '朗豪坊'), '收到，為你鎖定旺角區朗豪坊，搜尋緊周邊車位。');
  });

  test('withVoiceReplyPrompt only appends when full voice and mic inactive', () {
    const cue = '去邊度？';
    expect(ZhHkTemplates.withVoiceReplyPrompt(cue, TripVoiceMode.fullVoice, false), cue + ZhHkTemplates.TAP_CAPSULE);
    expect(ZhHkTemplates.withVoiceReplyPrompt(cue, TripVoiceMode.fullVoice, true), cue);
    expect(ZhHkTemplates.withVoiceReplyPrompt(cue, TripVoiceMode.announceManual, false), cue);
    expect(
      ZhHkTemplates.withVoiceReplyPrompt(cue + ZhHkTemplates.TAP_CAPSULE, TripVoiceMode.fullVoice, false),
      cue + ZhHkTemplates.TAP_CAPSULE,
    );
  });

  test('cardDetails prefers fee summary', () {
    const withFee = RecommendationCard(
      carparkId: 'A',
      name: '場',
      type: 'INDOOR',
      vacancies: 1,
      distanceMeters: 1,
      feeSummary: '首小時 20',
      spokenCue: '',
      isStale: false,
    );
    expect(ZhHkTemplates.cardDetails(withFee), '場，首小時 20');
  });
}
