package com.hkparking.copilot.di

import com.hkparking.copilot.NativeConfig
import com.hkparking.copilot.net.ApiClient
import com.hkparking.copilot.net.ApiEndpointPolicy
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

/**
 * Step 3.5（SC-018／RT-3..7）：真雲端傳輸。release 一律用；debug 要 HKP_USE_REAL_API=true 先用。
 * - Base URL／key 由建置設定注入；檢查規則見 [ApiEndpointPolicy]，唔合規 → networkError（離線），唔 crash。
 * - 網絡 I/O 喺有上限 daemon 池 `hkp-api-io`（RT-3）；結果喺背景 resume，FBS launchApi 跳返主執行緒。
 * - 單次請求總時限 = [timeoutMs]（預設 NativeConfig.API_TIMEOUT_MS）；body 硬上限 MAX_RESPONSE_BYTES（RT-4）。
 * - 唔跟 redirect（3xx → HTTP_REDIRECT_BLOCKED log，唔帶 key／URL 秘密）（RT-7）；唔 log key／body。
 */
class HttpApiTransport(
  private val baseUrl: String = "",
  private val apiKey: String = ApiClient.INTERNAL_API_KEY,
  private val allowLocalCleartext: Boolean = com.hkparking.copilot.BuildConfig.DEBUG,
  private val timeoutMs: Long = NativeConfig.API_TIMEOUT_MS,
) : ApiTransport {
  val verdict: ApiEndpointPolicy.Verdict = ApiEndpointPolicy.check(baseUrl, apiKey, allowLocalCleartext)

  @Volatile private var configLogged = false

  override suspend fun post(path: String, bodyJson: String): HttpResult {
    val current = verdict
    if (!current.usable) {
      logConfigOnce(current)
      return HttpResult(statusCode = 0, networkError = true)
    }
    val fullUrl = ApiEndpointPolicy.joinUrl(current.normalizedBaseUrl, path, allowLocalCleartext)
      ?: return HttpResult(statusCode = 0, networkError = true)
    return kotlin.coroutines.suspendCoroutine { cont ->
      val finished = java.util.concurrent.atomic.AtomicBoolean(false)
      val connectionRef = java.util.concurrent.atomic.AtomicReference<java.net.HttpURLConnection?>(null)
      fun finish(result: HttpResult) {
        if (finished.compareAndSet(false, true)) cont.resumeWith(Result.success(result))
      }
      val watchdogBox = arrayOf<java.util.concurrent.ScheduledFuture<*>?>(null)
      val future: java.util.concurrent.Future<*> = try {
        IO_EXECUTOR.submit {
          val result = blockingPost(fullUrl, path, bodyJson, connectionRef, finished)
          watchdogBox[0]?.cancel(false)
          finish(result)
        }
      } catch (_: java.util.concurrent.RejectedExecutionException) {
        finish(HttpResult(statusCode = 0, networkError = true))
        return@suspendCoroutine
      }
      watchdogBox[0] = WATCHDOG.schedule(
        {
          // RT-3：watchdog 搶先 finished 後 disconnect＋cancel I/O future。
          if (finished.compareAndSet(false, true)) {
            runCatching { connectionRef.get()?.disconnect() }
            runCatching { future.cancel(true) }
            cont.resumeWith(Result.success(HttpResult(statusCode = 0, timedOut = true)))
          } else {
            runCatching { connectionRef.get()?.disconnect() }
            runCatching { future.cancel(true) }
          }
        },
        timeoutMs,
        java.util.concurrent.TimeUnit.MILLISECONDS,
      )
    }
  }

  private fun blockingPost(
    url: String,
    path: String,
    bodyJson: String,
    connectionRef: java.util.concurrent.atomic.AtomicReference<java.net.HttpURLConnection?>,
    finished: java.util.concurrent.atomic.AtomicBoolean,
  ): HttpResult {
    return runCatching {
      val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
      connectionRef.set(connection)
      // RT-3：放入 connRef 後立即檢查；若 watchdog 已贏，斷線退出。
      if (finished.get()) {
        runCatching { connection.disconnect() }
        return HttpResult(statusCode = 0, timedOut = true)
      }
      try {
        val bytes = bodyJson.toByteArray(Charsets.UTF_8)
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.useCaches = false
        connection.instanceFollowRedirects = false
        connection.connectTimeout = timeoutMs.toInt()
        connection.readTimeout = timeoutMs.toInt()
        connection.setFixedLengthStreamingMode(bytes.size)
        connection.setRequestProperty(ApiClient.HEADER_API_KEY, apiKey)
        connection.setRequestProperty(ApiClient.HEADER_CONTENT_TYPE, ApiClient.JSON_CONTENT_TYPE)
        connection.setRequestProperty("Accept", "application/json")
        connection.outputStream.use { stream -> stream.write(bytes) }
        val code = connection.responseCode
        if (code in 300..399) {
          runCatching { android.util.Log.i(TAG, "HTTP_REDIRECT_BLOCKED path=$path status=$code") }
        } else {
          runCatching { android.util.Log.i(TAG, "POST $path status=$code") }
        }
        // RT-4：errorStream 可能 null（空 body 4xx／5xx）；2xx 用 inputStream。
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = readCapped(stream) ?: return HttpResult(statusCode = 0, networkError = true)
        val retryAfter = connection.getHeaderField("Retry-After")
        val headers = if (retryAfter == null) emptyMap() else mapOf("Retry-After" to retryAfter)
        ApiResponses.buildResult(path, code, body, headers)
      } finally {
        runCatching { connection.disconnect() }
      }
    }.getOrElse { error ->
      val timedOut = error is java.net.SocketTimeoutException || finished.get()
      runCatching { android.util.Log.w(TAG, "POST $path failed reason=${error.javaClass.simpleName}") }
      HttpResult(statusCode = 0, timedOut = timedOut, networkError = !timedOut)
    }
  }

  /** RT-4：讀取上限 [NativeConfig.MAX_RESPONSE_BYTES]；超過回 null（當 networkError）。null stream → ""。 */
  private fun readCapped(stream: java.io.InputStream?): String? {
    if (stream == null) return ""
    return stream.use { input ->
      val out = java.io.ByteArrayOutputStream()
      val buf = ByteArray(8192)
      var total = 0
      while (true) {
        val n = input.read(buf)
        if (n < 0) break
        total += n
        if (total > NativeConfig.MAX_RESPONSE_BYTES) return@use null
        out.write(buf, 0, n)
      }
      out.toString(Charsets.UTF_8.name())
    }
  }

  private fun logConfigOnce(current: ApiEndpointPolicy.Verdict) {
    if (configLogged) return
    configLogged = true
    runCatching {
      android.util.Log.w(TAG, "API not configured reason=${current.reason} host=${current.host.ifBlank { "-" }}")
    }
  }

  companion object {
    const val TAG = "HttpApiTransport"
    // RT-3：有上限 daemon 池；拒絕策略 → 呼叫端收到 RejectedExecutionException → networkError。
    private val IO_EXECUTOR: java.util.concurrent.ThreadPoolExecutor =
      java.util.concurrent.ThreadPoolExecutor(
        NativeConfig.API_IO_POOL_CORE,
        NativeConfig.API_IO_POOL_MAX,
        60L,
        java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue(32),
        java.util.concurrent.ThreadFactory { runnable ->
          Thread(runnable, "hkp-api-io").apply { isDaemon = true }
        },
        java.util.concurrent.ThreadPoolExecutor.AbortPolicy(),
      ).also { it.allowCoreThreadTimeOut(true) }
    private val WATCHDOG: java.util.concurrent.ScheduledExecutorService =
      java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "hkp-api-watchdog").apply { isDaemon = true }
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
