package com.hkparking.copilot

import com.hkparking.copilot.di.LocFix
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class SamplingTier(val intervalMs: Long, val priority: String) {
  FAR(NativeConfig.LOC_INTERVAL_FAR_MS, NativeConfig.LOC_PRIORITY_FAR),
  NEAR(NativeConfig.LOC_INTERVAL_NEAR_MS, NativeConfig.LOC_PRIORITY_NEAR),
  APPROACH(NativeConfig.LOC_INTERVAL_APPROACH_MS, NativeConfig.LOC_PRIORITY_APPROACH),
}

data class DistanceSample(
  val distanceM: Int,
  val speedKmh: Double,
  val fixAgeS: Int,
  val lowSpeedHoldS: Int,
  val exitHoldS: Int,
  val fresh: Boolean,
)

class DistanceTracker(
  private val listener: Listener,
) {
  interface Listener {
    fun onSample(sample: DistanceSample)
    fun onTierChanged(tier: SamplingTier)
  }

  var tier: SamplingTier = SamplingTier.FAR
    private set
  var lastDistanceM: Int? = null
    private set
  var latestClockMs: Long? = null
    private set

  private var destLat: Double? = null
  private var destLng: Double? = null
  private var lastValidFix: LocFix? = null
  private var resolvedSpeedKmh: Double? = null
  private var lowStreakStartMs: Long? = null
  private var exitStreakStartMs: Long? = null
  private var approachMuted: Boolean = false
  private var staleEmittedForGap: Boolean = false

  fun setDestination(lat: Double?, lng: Double?) {
    if (lat == null || lng == null) {
      destLat = null
      destLng = null
      lastDistanceM = null
      lowStreakStartMs = null
      exitStreakStartMs = null
      resolvedSpeedKmh = null
      staleEmittedForGap = false
      promoteTier(SamplingTier.FAR)
      return
    }
    destLat = lat
    destLng = lng
    lowStreakStartMs = null
    exitStreakStartMs = null
    val fix = lastValidFix ?: return
    val ageMs = ageOf(fix)
    val fresh = ageMs <= NativeConfig.GPS_FIX_STALE_S * 1000L
    if (!fresh) staleEmittedForGap = true
    emitFromFix(fix, ageMs, fresh)
  }

  fun setApproachMuted(muted: Boolean) {
    if (approachMuted == muted) {
      if (!muted) exitStreakStartMs = null
      return
    }
    approachMuted = muted
    exitStreakStartMs = null
  }

  fun onFix(fix: LocFix) {
    if (fix.accuracyM > NativeConfig.VALID_FIX_MAX_ACCURACY_M) return
    val previous = lastValidFix
    if (previous != null && fix.timestampMs < previous.timestampMs) return

    if (previous != null) {
      val gap = fix.timestampMs - previous.timestampMs
      // Low-speed streak needs near-1Hz continuity (D17): any gap > GPS_FIX_STALE_S resets.
      // Exit streak (D18/T8): reset on tick-stale or on gaps long enough to falsely complete
      // the 10s exit hold; shorter sparse gaps (e.g. T8 +9s) must keep streakStartMs.
      if (staleEmittedForGap || gap > NativeConfig.GPS_FIX_STALE_S * 1000L) {
        lowStreakStartMs = null
      }
      if (staleEmittedForGap || gap > NativeConfig.APPROACH_EXIT_HOLD_S * 1000L) {
        exitStreakStartMs = null
      }
    }
    staleEmittedForGap = false
    resolvedSpeedKmh = resolveSpeed(fix, previous)
    lastValidFix = fix
    bumpClock(fix.timestampMs)
    emitFromFix(fix, ageMs = 0L, fresh = true)
  }

  fun onTick(nowMs: Long) {
    bumpClock(nowMs)
    val fix = lastValidFix ?: return
    if (destLat == null || destLng == null) return
    val ageMs = nowMs - fix.timestampMs
    if (ageMs <= NativeConfig.GPS_FIX_STALE_S * 1000L) return
    if (staleEmittedForGap) return
    staleEmittedForGap = true
    lowStreakStartMs = null
    exitStreakStartMs = null
    val distance = lastDistanceM ?: roundHaversine(fix.lat, fix.lng, destLat!!, destLng!!)
    lastDistanceM = distance
    updateTier(distance)
    listener.onSample(
      DistanceSample(
        distanceM = distance,
        speedKmh = sampleSpeedForTsm(),
        fixAgeS = staleFixAgeS(ageMs),
        lowSpeedHoldS = 0,
        exitHoldS = 0,
        fresh = false,
      ),
    )
  }

  fun distanceToDestinationM(): Int? {
    val fix = lastValidFix ?: return null
    val lat = destLat ?: return null
    val lng = destLng ?: return null
    return roundHaversine(fix.lat, fix.lng, lat, lng)
  }

  fun recentSpeedKmh(nowMs: Long? = null): Double? {
    val fix = lastValidFix ?: return null
    val clock = nowMs ?: latestClockMs ?: fix.timestampMs
    val ageMs = clock - fix.timestampMs
    val maxAge = tier.intervalMs + NativeConfig.GPS_FIX_STALE_S * 1000L
    if (ageMs > maxAge) return null
    return resolvedSpeedKmh
  }

  fun reset() {
    destLat = null
    destLng = null
    lastValidFix = null
    resolvedSpeedKmh = null
    lowStreakStartMs = null
    exitStreakStartMs = null
    approachMuted = false
    lastDistanceM = null
    latestClockMs = null
    staleEmittedForGap = false
    promoteTier(SamplingTier.FAR)
  }

  private fun emitFromFix(fix: LocFix, ageMs: Long, fresh: Boolean) {
    val lat = destLat
    val lng = destLng
    if (lat == null || lng == null) return
    val distance = roundHaversine(fix.lat, fix.lng, lat, lng)
    lastDistanceM = distance
    val lowHold = computeLowHold(distance, fix.timestampMs)
    val exitHold = computeExitHold(distance, fix.timestampMs)
    updateTier(distance)
    listener.onSample(
      DistanceSample(
        distanceM = distance,
        speedKmh = sampleSpeedForTsm(),
        fixAgeS = if (fresh) (ageMs / 1000L).toInt().coerceAtLeast(0) else staleFixAgeS(ageMs),
        lowSpeedHoldS = lowHold,
        exitHoldS = exitHold,
        fresh = fresh,
      ),
    )
  }

  private fun computeLowHold(distanceM: Int, timestampMs: Long): Int {
    val speed = resolvedSpeedKmh
    val qualifies = distanceM <= NativeConfig.APPROACH_CORE_RADIUS_M &&
      speed != null &&
      speed < NativeConfig.APPROACH_SPEED_THRESHOLD_KMH
    if (!qualifies) {
      lowStreakStartMs = null
      return 0
    }
    val start = lowStreakStartMs
    if (start == null) {
      lowStreakStartMs = timestampMs
      return 0
    }
    return ((timestampMs - start) / 1000L).toInt().coerceAtLeast(0)
  }

  private fun computeExitHold(distanceM: Int, timestampMs: Long): Int {
    if (!approachMuted) {
      exitStreakStartMs = null
      return 0
    }
    val qualifies = distanceM > NativeConfig.APPROACH_EXIT_DISTANCE_M
    if (!qualifies) {
      exitStreakStartMs = null
      return 0
    }
    val start = exitStreakStartMs
    if (start == null) {
      exitStreakStartMs = timestampMs
      return 0
    }
    return ((timestampMs - start) / 1000L).toInt().coerceAtLeast(0)
  }

  private fun updateTier(distanceM: Int) {
    val next = when (tier) {
      SamplingTier.APPROACH -> when {
        distanceM > NativeConfig.HIGH_FREQ_EXIT_RADIUS_M -> {
          if (distanceM <= NativeConfig.TRIGGER_DISTANCE_M) SamplingTier.NEAR else SamplingTier.FAR
        }
        else -> SamplingTier.APPROACH
      }
      SamplingTier.NEAR, SamplingTier.FAR -> when {
        distanceM <= NativeConfig.APPROACH_RADIUS_M -> SamplingTier.APPROACH
        distanceM <= NativeConfig.TRIGGER_DISTANCE_M -> SamplingTier.NEAR
        else -> SamplingTier.FAR
      }
    }
    promoteTier(next)
  }

  private fun promoteTier(next: SamplingTier) {
    if (tier == next) return
    tier = next
    listener.onTierChanged(next)
  }

  private fun resolveSpeed(fix: LocFix, previous: LocFix?): Double? {
    val raw = fix.speedKmh
    if (!raw.isNaN() && raw >= 0.0) return raw
    if (previous == null) return null
    val dtMs = fix.timestampMs - previous.timestampMs
    if (dtMs <= 0L) return null
    val metres = haversineM(previous.lat, previous.lng, fix.lat, fix.lng)
    return metres / (dtMs / 1000.0) * 3.6
  }

  private fun sampleSpeedForTsm(): Double = resolvedSpeedKmh ?: Double.MAX_VALUE

  private fun ageOf(fix: LocFix): Long {
    val clock = latestClockMs ?: fix.timestampMs
    return (clock - fix.timestampMs).coerceAtLeast(0L)
  }

  private fun bumpClock(ms: Long) {
    val current = latestClockMs
    latestClockMs = if (current == null) ms else maxOf(current, ms)
  }

  private fun roundHaversine(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Int =
    haversineM(lat1, lng1, lat2, lng2).roundToInt()

  private fun staleFixAgeS(ageMs: Long): Int {
    // Floor seconds, but once wall age has crossed GPS_FIX_STALE_S ensure TSM sees > stale.
    val floored = (ageMs / 1000L).toInt().coerceAtLeast(0)
    return maxOf(floored, NativeConfig.GPS_FIX_STALE_S + 1)
  }

  companion object {
    fun haversineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
      val earthRadiusM = 6_371_000.0
      val dLat = Math.toRadians(lat2 - lat1)
      val dLng = Math.toRadians(lng2 - lng1)
      val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
        sin(dLng / 2) * sin(dLng / 2)
      val c = 2 * atan2(sqrt(a), sqrt(1 - a))
      return earthRadiusM * c
    }
  }
}
