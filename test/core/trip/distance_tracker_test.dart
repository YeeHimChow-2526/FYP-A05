
import 'package:copilot/core/constants/native_config.dart';
import 'package:copilot/core/trip/distance_tracker.dart';
import 'package:copilot/core/trip/trip_ports.dart';
import 'package:copilot/core/trip/trip_types.dart';
import 'package:flutter_test/flutter_test.dart';

const destLat = 22.3193;
const destLng = 114.1694;
const delta1000 = 0.008983;

LocFix fixNorth(int metres, double speed, int ts, {double accuracyM = 10}) {
  return LocFix(lat: destLat + delta1000 * metres / 1000, lng: destLng, accuracyM: accuracyM, speedKmh: speed, timestampMs: ts);
}

DistanceTracker tracker(List<SamplingTier> tiers, [List<DistanceSample>? samples]) {
  return DistanceTracker(DistanceTrackerListener(
    onSample: (sample) => samples?.add(sample),
    onTierChanged: tiers.add,
  ));
}

void main() {
  test('haversine rounds to metres', () {
    final m100 = DistanceTracker.haversineM(destLat, destLng, destLat + delta1000 * 0.1, destLng);
    final m1000 = DistanceTracker.haversineM(destLat, destLng, destLat + delta1000, destLng);
    expect((m100 - 100).abs(), lessThanOrEqualTo(1));
    expect((m1000 - 1000).abs(), lessThanOrEqualTo(2));
  });

  test('tier hysteresis 150 enter 200 exit', () {
    final tiers = <SamplingTier>[];
    final subject = tracker(tiers)..setDestination(destLat, destLng);
    var t = 0;
    void push(int metres) {
      subject.onFix(fixNorth(metres, 20, t));
      t += 1000;
    }

    push(1500);
    expect(subject.tier, SamplingTier.far);
    push(900);
    push(150);
    push(180);
    expect(subject.tier, SamplingTier.approach);
    push(201);
    push(2500);
    expect(tiers, [SamplingTier.near, SamplingTier.approach, SamplingTier.near, SamplingTier.far]);
  });

  test('inaccurate or backwards fix is ignored', () {
    final samples = <DistanceSample>[];
    final subject = tracker([], samples)..setDestination(destLat, destLng);
    subject.onFix(fixNorth(100, 5, 0, accuracyM: 150));
    subject.onTick(6000);
    expect(samples, isEmpty);
    expect(subject.lastDistanceM, isNull);
    subject.onFix(fixNorth(100, 5, 2000));
    subject.onFix(fixNorth(100, 5, 1000));
    expect(samples, hasLength(1));
  });

  test('nan or negative speed uses haversine and null speed is fast', () {
    expect(DistanceTracker.sampleSpeedForTsm(null), double.maxFinite);
    final samples = <DistanceSample>[];
    final subject = tracker([], samples)..setDestination(destLat, destLng);
    subject.onFix(fixNorth(200, double.nan, 0));
    subject.onFix(LocFix(lat: destLat + delta1000 * 0.1, lng: destLng, accuracyM: 10, speedKmh: -1, timestampMs: 1000));
    expect(samples.last.speedKmh.isFinite, isTrue);
    expect(samples.last.speedKmh, isNot(double.maxFinite));
  });

  test('tick emits one stale sample and recent speed expires', () {
    final samples = <DistanceSample>[];
    final subject = tracker([], samples)..setDestination(destLat, destLng);
    subject.onFix(fixNorth(100, 4, 0));
    subject.onTick(6000);
    subject.onTick(7000);
    expect(samples.where((sample) => !sample.fresh), hasLength(1));
    expect(samples.last.fixAgeS, greaterThanOrEqualTo(NativeConfig.GPS_FIX_STALE_S + 1));
    expect(subject.recentSpeedKmh(0), 4);
    expect(subject.recentSpeedKmh(NativeConfig.LOC_INTERVAL_APPROACH_MS + NativeConfig.GPS_FIX_STALE_S * 1000 + 1), isNull);
  });

  test('reset returns far', () {
    final tiers = <SamplingTier>[];
    final subject = tracker(tiers)..setDestination(destLat, destLng);
    subject.onFix(fixNorth(100, 5, 0));
    subject.reset();
    expect(subject.tier, SamplingTier.far);
    expect(subject.lastDistanceM, isNull);
  });

  test('T7 low-hold gap reset', () {
    final samples = <DistanceSample>[];
    final subject = tracker([], samples)..setDestination(destLat, destLng);
    subject.onFix(fixNorth(70, 5, 0));
    subject.onFix(fixNorth(70, 5, 1000));
    expect(samples.last.lowSpeedHoldS, 1);
    final gapResetAt = 1000 + NativeConfig.GPS_FIX_STALE_S * 1000 + 1;
    subject.onFix(fixNorth(70, 5, gapResetAt));
    expect(samples.last.lowSpeedHoldS, 0);
    subject.onFix(fixNorth(70, 5, gapResetAt + 1000));
    expect(samples.last.lowSpeedHoldS, 1);
    subject.onTick(gapResetAt + 1000 + NativeConfig.GPS_FIX_STALE_S * 1000 + 1);
    subject.onFix(fixNorth(70, 5, gapResetAt + 1000 + NativeConfig.GPS_FIX_STALE_S * 1000 + 1));
    expect(samples.last.lowSpeedHoldS, 0);
  });

  test('T8 exit hold only while muted and long gap resets', () {
    final samples = <DistanceSample>[];
    final subject = tracker([], samples)..setDestination(destLat, destLng);
    subject.onFix(fixNorth(250, 5, 0));
    subject.onFix(fixNorth(250, 5, 10000));
    expect(samples.last.exitHoldS, 0);
    subject.setApproachMuted(true);
    subject.onFix(fixNorth(250, 5, 11000));
    expect(samples.last.exitHoldS, 0);
    subject.onFix(fixNorth(250, 5, 20000));
    expect(samples.last.exitHoldS, 9);
    subject.onFix(fixNorth(250, 5, 20000 + NativeConfig.APPROACH_EXIT_HOLD_S * 1000 + 1));
    expect(samples.last.exitHoldS, 0);
    subject.onFix(fixNorth(250, 5, 20000 + NativeConfig.APPROACH_EXIT_HOLD_S * 1000 + 1 + 9000));
    expect(samples.last.exitHoldS, 9);
  });

  test('T11 setDestination reemits fresh false and null returns far', () {
    final samples = <DistanceSample>[];
    final tiers = <SamplingTier>[];
    final subject = tracker(tiers, samples)..setDestination(destLat, destLng);
    subject.onFix(fixNorth(100, 5, 0));
    subject.onTick(6000);
    final before = samples.length;
    subject.setDestination(destLat + 0.01, destLng);
    expect(samples.length, before + 1);
    expect(samples.last.fresh, isFalse);
    subject.setDestination(null, null);
    expect(subject.tier, SamplingTier.far);
    expect(subject.lastDistanceM, isNull);
    expect(tiers, contains(SamplingTier.far));
  });

  test('T12 tier changes notify onTierChanged', () {
    final tiers = <SamplingTier>[];
    final subject = tracker(tiers)..setDestination(destLat, destLng);
    subject.onFix(fixNorth(900, 5, 0));
    subject.onFix(fixNorth(100, 5, 1000));
    expect(tiers, [SamplingTier.near, SamplingTier.approach]);
    subject.reset();
    expect(tiers.last, SamplingTier.far);
    expect(subject.tier, SamplingTier.far);
  });
}
