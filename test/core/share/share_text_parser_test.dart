import 'package:copilot/core/share/share_text_parser.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('step32_01_parseTitleAndShortUrl', () {
    final p = ShareTextParser.parse('朗豪坊 https://maps.app.goo.gl/abc');
    expect(p.title, '朗豪坊');
    expect(p.hasUrl, isTrue);
    expect(p.lat, isNull);
    expect(p.lng, isNull);
  });

  test('step32_02_parseMultilineTakesFirstLine', () {
    final p = ShareTextParser.parse('朗豪坊\n旺角亞皆老街8號\nhttps://maps.app.goo.gl/abc');
    expect(p.title, '朗豪坊');
    expect(p.hasUrl, isTrue);
  });

  test('step32_03_parsePlainDroppedPin', () {
    final p = ShareTextParser.parse('22.318, 114.168');
    expect(p.title, isNull);
    expect(p.lat, closeTo(22.318, 0.0001));
    expect(p.lng, closeTo(114.168, 0.0001));
  });

  test('step32_04_parseCoordsInsideUrl', () {
    final a = ShareTextParser.parse('https://maps.google.com/?q=22.30123,114.17234');
    expect(a.lat, closeTo(22.30123, 0.00001));
    expect(a.lng, closeTo(114.17234, 0.00001));
    expect(a.hasUrl, isTrue);
    expect(a.title, isNull);

    final b = ShareTextParser.parse('https://www.google.com/maps/place/Foo/@22.30123,114.17234,17z');
    expect(b.lat, closeTo(22.30123, 0.00001));
    expect(b.lng, closeTo(114.17234, 0.00001));
    expect(b.hasUrl, isTrue);
    expect(b.title, isNull);
  });

  test('step32_05_parseUrlOnlyHasNoTitle', () {
    final p = ShareTextParser.parse('https://maps.app.goo.gl/abc');
    expect(p.title, isNull);
    expect(p.hasUrl, isTrue);
    expect(p.lat, isNull);
    expect(p.lng, isNull);
  });

  test('step32_06_parseBlankAndNull', () {
    for (final raw in <String?>[null, '   ']) {
      final p = ShareTextParser.parse(raw);
      expect(p.title, isNull);
      expect(p.lat, isNull);
      expect(p.lng, isNull);
      expect(p.hasUrl, isFalse);
      expect(p.isEmpty, isTrue);
    }
  });

  test('step32_07_parseRejectsOutOfRangeCoords', () {
    final a = ShareTextParser.parse('95.1234, 200.1234');
    expect(a.lat, isNull);
    expect(a.lng, isNull);
    final b = ShareTextParser.parse('12.3, 45.6');
    expect(b.lat, isNull);
    expect(b.lng, isNull);
  });

  test('step32_08_parseSubjectFallback', () {
    final p = ShareTextParser.parse('https://maps.app.goo.gl/abc', subject: '朗豪坊');
    expect(p.title, '朗豪坊');
    expect(p.hasUrl, isTrue);
  });
}
