import 'package:copilot/core/net/api_responses.dart';
import 'package:copilot/core/net/recommendation_card.dart';
import 'package:copilot/core/trip/trip_types.dart';
import 'package:copilot/core/trip/vacancy_pip.dart';
import 'package:copilot/features/nav_shell/recommend_cards_strip.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

const _body = '''
{"status":"SUCCESS","spoken_cue":"要唔要搵遠少少？","await_voice_reply":false,"cards":[
 {"carpark_id":"F","name":"F","type":"INDOOR","vacancies":0,"distance_meters":10,"fee_summary":"","spoken_cue":"F只供參考","is_stale":false,"availability":"NO_AVAILABILITY","commit_allowed":false,"is_fallback":false},
 {"carpark_id":"OK","name":"OK","type":"INDOOR","vacancies":3,"distance_meters":90,"fee_summary":"","spoken_cue":"前方首選OK","is_stale":false,"availability":"AVAILABLE","commit_allowed":true,"is_fallback":false},
 {"carpark_id":"M","name":"M","type":"METER","vacancies":-1,"distance_meters":99,"fee_summary":"","spoken_cue":"附近可能有咪錶","is_stale":true,"availability":"UNCONFIRMED","commit_allowed":false,"is_fallback":true}
]}''';

void main() {
  test('parses availability / commit_allowed / is_fallback and picks viable lead cue', () {
    final parsed = ApiResponses.parseRecommend(_body)!;
    expect(parsed.cards.map((c) => c.availability), ['NO_AVAILABILITY', 'AVAILABLE', 'UNCONFIRMED']);
    expect(parsed.cards.map((c) => c.commitAllowed), [false, true, false]);
    expect(parsed.cards[2].isFallback, isTrue);
    expect(parsed.cards[2].isViable, isTrue); // unknown meter is not full
    expect(parsed.spokenCue, '要唔要搵遠少少？');
    expect(ApiResponses.buildResult(kRecommendPath, 200, _body, const {}).spokenCue, '前方首選OK');
    expect(VacancyPip.forLead(parsed.cards), StatusPip.red); // lead OK (3) not reference F
    expect(VacancyPip.isFresh(parsed.cards), isTrue);
  });

  test('legacy response without availability keeps old behaviour', () {
    final parsed = ApiResponses.parseRecommend(
        '{"status":"SUCCESS","cards":[{"carpark_id":"A","name":"A","type":"INDOOR","vacancies":5,"distance_meters":1,"fee_summary":"","spoken_cue":"x","is_stale":false}]}')!;
    expect(parsed.cards.single.availability, CardAvailability.available);
    expect(parsed.cards.single.commitAllowed, isTrue);
  });

  testWidgets('reference / unconfirmed cards have a disabled confirm button and honest label', (tester) async {
    final cards = ApiResponses.parseRecommend(_body)!.cards;
    final taps = <int>[];
    await tester.pumpWidget(MaterialApp(home: Scaffold(body: RecommendCardsStrip(cards: cards, onConfirm: taps.add))));
    FilledButton button(int i) => tester.widget<FilledButton>(find.byKey(Key('confirm_card_$i')));
    expect(button(0).onPressed, isNull);
    expect(button(1).onPressed, isNotNull);
    expect(find.text('暫時冇位（只供參考）'), findsOneWidget);
    expect(find.text('99 m・咪錶後備'), findsOneWidget);
    await tester.tap(find.byKey(const Key('confirm_card_0')));
    expect(taps, isEmpty);
  });
}
