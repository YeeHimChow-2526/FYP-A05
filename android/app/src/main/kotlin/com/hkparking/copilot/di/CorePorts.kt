package com.hkparking.copilot.di

import com.hkparking.copilot.NativeConfig
import com.hkparking.copilot.net.ApiClient
import com.hkparking.copilot.net.ApiResponses
import com.hkparking.copilot.net.ApiTransport
import com.hkparking.copilot.net.HttpResult

fun interface NetworkStatusProvider {
  fun isOnline(): Boolean
}

fun safeIsOnline(query: () -> Boolean): Boolean {
  return runCatching { query() }.getOrElse { false }
}

interface VoiceInput {
  fun setUtteranceListener(listener: ((String, Double?) -> Unit)?)
  fun startListening(hotword: Boolean = false)
  fun stopListening()
}

interface LocationSource {
  fun setLocationListener(listener: ((distanceMeters: Int, speedKmh: Double, fixAgeSeconds: Int) -> Unit)?)
  fun setMotionListener(
    listener: ((distanceMeters: Int, speedKmh: Double, fixAgeSeconds: Int, lowSpeedHoldS: Int, exitHoldS: Int) -> Unit)?,
  ) {}
  fun setFixListener(listener: ((LocFix) -> Unit)?) {}
  fun setTickListener(listener: ((nowMs: Long) -> Unit)?) {}
  fun applySamplingTier(tier: com.hkparking.copilot.SamplingTier) {}
  fun start()
  fun stop()
}

data class LocFix(
  val lat: Double,
  val lng: Double,
  val accuracyM: Double,
  val speedKmh: Double,
  val timestampMs: Long,
  val distanceMeters: Int = 0,
  val fixAgeSeconds: Int = 0,
)

class SttVoiceInput : VoiceInput {
  private var listener: ((String, Double?) -> Unit)? = null
  private var listening = false

  override fun setUtteranceListener(listener: ((String, Double?) -> Unit)?) {
    this.listener = listener
    if (listener == null) listening = false
  }

  override fun startListening(hotword: Boolean) {
    listening = true
  }

  override fun stopListening() {
    listening = false
  }
}

class FusedLocationSource : LocationSource {
  private var listener: ((Int, Double, Int) -> Unit)? = null
  private var started = false

  override fun setLocationListener(listener: ((Int, Double, Int) -> Unit)?) {
    this.listener = listener
  }

  override fun start() {
    started = true
  }

  override fun stop() {
    started = false
  }
}


class PlatformLocationSource : LocationSource {
  private var appContext: android.content.Context? = null
  private var locationManager: android.location.LocationManager? = null
  private var distanceListener: ((Int, Double, Int) -> Unit)? = null
  private var motionListener: ((Int, Double, Int, Int, Int) -> Unit)? = null
  private var fixListener: ((LocFix) -> Unit)? = null
  private var tickListener: ((Long) -> Unit)? = null
  private var started = false
  private var currentTier: com.hkparking.copilot.SamplingTier = com.hkparking.copilot.SamplingTier.FAR
  private val handler = android.os.Handler(android.os.Looper.getMainLooper())
  private val tickRunnable = object : Runnable {
    override fun run() {
      if (!started) return
      val now = android.os.SystemClock.elapsedRealtime()
      tickListener?.invoke(now)
      handler.postDelayed(this, com.hkparking.copilot.NativeConfig.DISTANCE_TICK_MS)
    }
  }
  private val locationListener = android.location.LocationListener { location ->
    val speedKmh = if (location.hasSpeed()) location.speed * 3.6 else Double.NaN
    val timestampMs = location.elapsedRealtimeNanos / 1_000_000L
    val fix = LocFix(
      lat = location.latitude,
      lng = location.longitude,
      accuracyM = location.accuracy.toDouble(),
      speedKmh = speedKmh,
      timestampMs = timestampMs,
    )
    fixListener?.invoke(fix)
  }

  fun attach(context: android.content.Context) {
    appContext = context.applicationContext
    locationManager = runCatching {
      context.applicationContext.getSystemService(android.content.Context.LOCATION_SERVICE) as? android.location.LocationManager
    }.getOrNull()
  }

  fun detach() {
    stopInternal()
    appContext = null
    locationManager = null
    distanceListener = null
    motionListener = null
    fixListener = null
    tickListener = null
  }

  override fun setLocationListener(listener: ((Int, Double, Int) -> Unit)?) {
    distanceListener = listener
  }

  override fun setMotionListener(
    listener: ((distanceMeters: Int, speedKmh: Double, fixAgeSeconds: Int, lowSpeedHoldS: Int, exitHoldS: Int) -> Unit)?,
  ) {
    motionListener = listener
  }

  override fun setFixListener(listener: ((LocFix) -> Unit)?) {
    fixListener = listener
  }

  override fun setTickListener(listener: ((nowMs: Long) -> Unit)?) {
    tickListener = listener
  }

  override fun applySamplingTier(tier: com.hkparking.copilot.SamplingTier) {
    if (currentTier == tier && started) return
    val changed = currentTier != tier
    currentTier = tier
    if (started && changed) {
      reregister()
    }
  }

  override fun start() {
    if (started) return
    started = true
    reregister()
    handler.removeCallbacksAndMessages(null)
    handler.post(tickRunnable)
  }

  override fun stop() {
    stopInternal()
  }

  private fun stopInternal() {
    started = false
    handler.removeCallbacksAndMessages(null)
    runCatching { locationManager?.removeUpdates(locationListener) }
  }

  private fun reregister() {
    val lm = locationManager ?: return
    runCatching { lm.removeUpdates(locationListener) }
    val provider = when {
      runCatching { lm.allProviders.contains(android.location.LocationManager.FUSED_PROVIDER) }.getOrDefault(false) ->
        android.location.LocationManager.FUSED_PROVIDER
      else -> android.location.LocationManager.GPS_PROVIDER
    }
    val quality = when (currentTier.priority) {
      com.hkparking.copilot.NativeConfig.LOC_PRIORITY_APPROACH ->
        android.location.LocationRequest.QUALITY_HIGH_ACCURACY
      else -> android.location.LocationRequest.QUALITY_BALANCED_POWER_ACCURACY
    }
    val request = android.location.LocationRequest.Builder(currentTier.intervalMs)
      .setQuality(quality)
      .setMinUpdateIntervalMillis(currentTier.intervalMs)
      .build()
    val ctx = appContext ?: return
    runCatching {
      lm.requestLocationUpdates(provider, request, ctx.mainExecutor, locationListener)
    }.onFailure { err ->
      runCatching {
        android.util.Log.w(
          "PlatformLocationSource",
          "requestLocationUpdates failed reason=${err.javaClass.simpleName}",
        )
      }
    }
  }
}

class HttpApiTransport(
  private val baseUrl: String = "",
) : ApiTransport {
  override suspend fun post(path: String, bodyJson: String): HttpResult {
    if (baseUrl.isBlank()) {
      return HttpResult(statusCode = 0, networkError = true)
    }
    return runCatching {
      val connection = java.net.URL(baseUrl.trimEnd('/') + path).openConnection() as java.net.HttpURLConnection
      try {
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = NativeConfig.API_TIMEOUT_MS.toInt()
        connection.readTimeout = NativeConfig.API_TIMEOUT_MS.toInt()
        connection.setRequestProperty(ApiClient.HEADER_API_KEY, ApiClient.INTERNAL_API_KEY)
        connection.setRequestProperty(ApiClient.HEADER_CONTENT_TYPE, ApiClient.JSON_CONTENT_TYPE)
        connection.setRequestProperty("Accept", "application/json")
        connection.outputStream.use { stream -> stream.write(bodyJson.toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader(Charsets.UTF_8)?.use { reader -> reader.readText() }.orEmpty()
        val retryAfter = connection.getHeaderField("Retry-After")
        val headers = if (retryAfter == null) emptyMap() else mapOf("Retry-After" to retryAfter)
        ApiResponses.buildResult(path, code, body, headers)
      } finally {
        runCatching { connection.disconnect() }
      }
    }.getOrElse { error ->
      val timedOut = error is java.net.SocketTimeoutException
      HttpResult(statusCode = 0, timedOut = timedOut, networkError = !timedOut)
    }
  }
}

fun interface NavigationPort {
  fun launchGateNavigation(carparkName: String): Boolean
}

class IntentNavigationPort(
  private val contextProvider: () -> android.content.Context?,
) : NavigationPort {
  override fun launchGateNavigation(carparkName: String): Boolean {
    val ctx = contextProvider() ?: return false
    val q = java.net.URLEncoder.encode(carparkName, Charsets.UTF_8.name()).replace("+", "%20")
    val uri = android.net.Uri.parse("google.navigation:q=$q&mode=d")
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri).apply {
      setPackage("com.google.android.apps.maps")
      addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return runCatching {
      ctx.startActivity(intent)
      android.util.Log.i("NavigationPort", "launchGateNavigation ok name=$carparkName")
      true
    }.getOrElse { err ->
      runCatching {
        android.util.Log.w(
          "NavigationPort",
          "launchGateNavigation failed name=$carparkName reason=${err.javaClass.simpleName}",
        )
      }
      false
    }
  }
}
