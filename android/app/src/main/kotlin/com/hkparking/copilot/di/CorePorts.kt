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
