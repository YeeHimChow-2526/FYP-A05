package com.hkparking.copilot.di

import com.hkparking.copilot.net.ApiTransport
import com.hkparking.copilot.net.HttpResult

fun interface NetworkStatusProvider {
  fun isOnline(): Boolean
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
  }

  override fun startListening(hotword: Boolean) {
    listening = true
  }

  override fun stopListening() {
    listening = false
    listener = null
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
      connection.requestMethod = "POST"
      connection.doOutput = true
      connection.setRequestProperty("Content-Type", "application/json")
      connection.outputStream.use { stream -> stream.write(bodyJson.toByteArray()) }
      val code = connection.responseCode
      val stream = if (code in 200..299) connection.inputStream else connection.errorStream
      val body = stream?.bufferedReader()?.use { reader -> reader.readText() }.orEmpty()
      val retryAfter = connection.getHeaderField("Retry-After")
      HttpResult(
        statusCode = code,
        bodyJson = body,
        headers = if (retryAfter == null) emptyMap() else mapOf("Retry-After" to retryAfter),
        retryAfterSeconds = retryAfter?.toLongOrNull(),
      )
    }.getOrElse {
      HttpResult(statusCode = 0, networkError = true)
    }
  }
}
