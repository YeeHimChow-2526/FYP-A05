package com.hkparking.copilot.net

import com.hkparking.copilot.NativeConfig
import com.hkparking.copilot.voice.RecommendationCard

object ApiResponses {
  fun parseRecommend(body: String): RecommendResponse? {
    val root = MiniJson.parseObjectOrNull(body) ?: return null
    val status = root["status"] as? String
    if (status.isNullOrBlank()) return null
    val cardsRaw = root["cards"]
    if (cardsRaw != null && cardsRaw !is List<*>) return null
    val cardItems = (cardsRaw as? List<*>) ?: emptyList<Any?>()
    if (cardItems.any { it !is Map<*, *> }) return null
    val topCue = nonBlankString(root["spoken_cue"])
    val cards = cardItems.map { item ->
      val card = item as Map<*, *>
      RecommendationCard(
        carparkId = stringOr(card["carpark_id"], ""),
        name = stringOr(card["name"], ""),
        type = stringOr(card["type"], ""),
        vacancies = intOr(card["vacancies"], -1),
        distanceMeters = intOr(card["distance_meters"], 0),
        feeSummary = stringOr(card["fee_summary"], ""),
        spokenCue = nonBlankString(card["spoken_cue"]) ?: topCue ?: "",
        isStale = card["is_stale"] == true,
      )
    }
    return RecommendResponse(
      status = status,
      speechLocale = stringOr(root["speech_locale"], NativeConfig.SPEECH_LOCALE_DEFAULT),
      speechLocaleFallback = root["speech_locale_fallback"] == true,
      sourceStatus = stringOr(root["source_status"], "ok"),
      sourceSnapshotTs = stringOr(root["source_snapshot_ts"], ""),
      processedAt = stringOr(root["processed_at"], ""),
      serverNow = stringOr(root["server_now"], ""),
      dataAgeSeconds = intOr(root["data_age_seconds"], 0),
      cards = cards,
      awaitVoiceReply = root["await_voice_reply"] == true,
    )
  }

  fun buildResult(path: String, statusCode: Int, body: String, headers: Map<String, String>): HttpResult {
    if (path == ApiClient.RECOMMEND && statusCode in 200..299) {
      val parsed = parseRecommend(body)
      if (parsed == null) {
        runCatching {
          android.util.Log.i(
            "ApiResponses",
            "Recommend parse failed: statusCode=$statusCode bytes=${body.length}",
          )
        }
        return HttpResult(statusCode = 0, bodyJson = body, headers = headers, networkError = true)
      }
      val topCue = nonBlankString(MiniJson.parseObjectOrNull(body)?.get("spoken_cue"))
      val spokenCue = parsed.cards.firstOrNull()?.spokenCue?.takeIf { it.isNotBlank() } ?: topCue
      return HttpResult(
        statusCode = statusCode,
        bodyJson = body,
        headers = headers,
        recommendResponse = parsed,
        spokenCue = spokenCue,
      )
    }
    if (statusCode == 409) {
      val spokenCue = nonBlankString(MiniJson.parseObjectOrNull(body)?.get("spoken_cue"))
      return HttpResult(statusCode = statusCode, bodyJson = body, headers = headers, spokenCue = spokenCue)
    }
    if (statusCode == 429) {
      val retryAfterSeconds = headerRetrySeconds(headers) ?: bodyRetrySeconds(body)
      return HttpResult(
        statusCode = statusCode,
        bodyJson = body,
        headers = headers,
        retryAfterSeconds = retryAfterSeconds,
      )
    }
    return HttpResult(statusCode = statusCode, bodyJson = body, headers = headers)
  }

  private fun headerRetrySeconds(headers: Map<String, String>): Long? {
    val raw = headers.entries.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }?.value ?: return null
    return raw.trim().toLongOrNull()?.takeIf { it >= 0L }
  }

  private fun bodyRetrySeconds(body: String): Long? {
    val value = MiniJson.parseObjectOrNull(body)?.get("retry_after_seconds") ?: return null
    val seconds = when (value) {
      is Long -> value
      is Double -> if (value.isFinite() && value % 1.0 == 0.0) value.toLong() else return null
      else -> return null
    }
    return seconds.takeIf { it >= 0L }
  }

  private fun nonBlankString(value: Any?): String? = (value as? String)?.takeIf { it.isNotBlank() }

  private fun stringOr(value: Any?, default: String): String = value as? String ?: default

  private fun intOr(value: Any?, default: Int): Int = when (value) {
    is Long -> if (value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) value.toInt() else default
    is Double -> {
      val asLong = value.toLong()
      if (value.isFinite() && value % 1.0 == 0.0 && asLong in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
        asLong.toInt()
      } else {
        default
      }
    }
    else -> default
  }
}

internal object MiniJson {
  fun parseObjectOrNull(text: String): Map<String, Any?>? {
    return runCatching {
      val parser = Parser(text)
      parser.skipBomAndWs()
      val value = parser.parseValue(1)
      parser.skipWs()
      if (!parser.eof()) return null
      value as? Map<String, Any?>
    }.getOrNull()
  }

  private class ParseFail : RuntimeException()

  private class Parser(private val text: String) {
    private var index = 0

    fun eof(): Boolean = index >= text.length

    fun skipBomAndWs() {
      if (!eof() && text[index] == '\uFEFF') index += 1
      skipWs()
    }

    fun skipWs() {
      while (!eof()) {
        val ch = text[index]
        if (ch != ' ' && ch != '\t' && ch != '\n' && ch != '\r') break
        index += 1
      }
    }

    fun parseValue(depth: Int): Any? {
      skipWs()
      if (eof()) fail()
      return when (val ch = text[index]) {
        '{' -> {
          if (depth > MAX_DEPTH) fail()
          parseObject(depth)
        }
        '[' -> {
          if (depth > MAX_DEPTH) fail()
          parseArray(depth)
        }
        '"' -> parseString()
        't' -> parseLiteral("true", true)
        'f' -> parseLiteral("false", false)
        'n' -> parseLiteral("null", null)
        '-' -> parseNumber()
        else -> if (ch in '0'..'9') parseNumber() else fail()
      }
    }

    private fun parseObject(depth: Int): Map<String, Any?> {
      expect('{')
      val map = LinkedHashMap<String, Any?>()
      skipWs()
      if (!eof() && text[index] == '}') {
        index += 1
        return map
      }
      while (true) {
        skipWs()
        if (eof() || text[index] != '"') fail()
        val key = parseString()
        skipWs()
        expect(':')
        map[key] = parseValue(depth + 1)
        skipWs()
        if (eof()) fail()
        when (text[index]) {
          ',' -> index += 1
          '}' -> {
            index += 1
            return map
          }
          else -> fail()
        }
      }
    }

    private fun parseArray(depth: Int): List<Any?> {
      expect('[')
      val list = ArrayList<Any?>()
      skipWs()
      if (!eof() && text[index] == ']') {
        index += 1
        return list
      }
      while (true) {
        list += parseValue(depth + 1)
        skipWs()
        if (eof()) fail()
        when (text[index]) {
          ',' -> index += 1
          ']' -> {
            index += 1
            return list
          }
          else -> fail()
        }
      }
    }

    private fun parseString(): String {
      expect('"')
      val out = StringBuilder()
      while (!eof()) {
        val ch = text[index]
        index += 1
        when {
          ch == '"' -> return out.toString()
          ch == '\\' -> out.append(parseEscape())
          ch.code < 0x20 -> fail()
          else -> out.append(ch)
        }
      }
      fail()
    }

    private fun parseEscape(): Char {
      if (eof()) fail()
      val ch = text[index]
      index += 1
      return when (ch) {
        '"', '\\', '/' -> ch
        'b' -> '\b'
        'f' -> '\u000C'
        'n' -> '\n'
        'r' -> '\r'
        't' -> '\t'
        'u' -> parseHex4().toChar()
        else -> fail()
      }
    }

    private fun parseHex4(): Int {
      var value = 0
      repeat(4) {
        if (eof()) fail()
        val digit = hexValue(text[index])
        index += 1
        value = (value shl 4) + digit
      }
      return value
    }

    private fun hexValue(ch: Char): Int = when (ch) {
      in '0'..'9' -> ch.code - '0'.code
      in 'a'..'f' -> ch.code - 'a'.code + 10
      in 'A'..'F' -> ch.code - 'A'.code + 10
      else -> fail()
    }

    private fun parseNumber(): Any {
      val start = index
      if (!eof() && text[index] == '-') index += 1
      if (eof() || text[index] !in '0'..'9') fail()
      if (text[index] == '0') {
        index += 1
      } else {
        while (!eof() && text[index] in '0'..'9') index += 1
      }
      var fractional = false
      if (!eof() && text[index] == '.') {
        fractional = true
        index += 1
        if (eof() || text[index] !in '0'..'9') fail()
        while (!eof() && text[index] in '0'..'9') index += 1
      }
      if (!eof() && (text[index] == 'e' || text[index] == 'E')) {
        fractional = true
        index += 1
        if (!eof() && (text[index] == '+' || text[index] == '-')) index += 1
        if (eof() || text[index] !in '0'..'9') fail()
        while (!eof() && text[index] in '0'..'9') index += 1
      }
      val lexeme = text.substring(start, index)
      if (!fractional) {
        lexeme.toLongOrNull()?.let { return it }
      }
      return lexeme.toDouble()
    }

    private fun parseLiteral(word: String, value: Any?): Any? {
      for (ch in word) {
        if (eof() || text[index] != ch) fail()
        index += 1
      }
      return value
    }

    private fun expect(ch: Char) {
      if (eof() || text[index] != ch) fail()
      index += 1
    }

    private fun fail(): Nothing = throw ParseFail()
  }

  private const val MAX_DEPTH = 32
}
