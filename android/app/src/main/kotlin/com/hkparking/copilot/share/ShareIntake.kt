package com.hkparking.copilot.share

data class ParsedShare(
  val title: String?,
  val lat: Double?,
  val lng: Double?,
  val hasUrl: Boolean,
) {
  val isEmpty: Boolean
    get() = title == null && lat == null && !hasUrl
  val hasPin: Boolean
    get() = lat != null && lng != null
}

object ShareTextParser {
  private val URL_REGEX = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
  // Decimal lat,lng with ≥3 fractional digits; optional spaces around comma.
  private val COORD_REGEX = Regex("""(-?\d+\.\d{3,})\s*,\s*(-?\d+\.\d{3,})""")

  fun parse(text: String?, subject: String? = null): ParsedShare {
    val rawText = text ?: ""
    val rawSubject = subject ?: ""
    // RT-5: extract hasUrl + first valid (lat,lng) from the *raw* text (and subject) first.
    val hasUrl = URL_REGEX.containsMatchIn(rawText) || URL_REGEX.containsMatchIn(rawSubject)
    val pin = firstValidCoord(rawText) ?: firstValidCoord(rawSubject)
    val title = extractTitle(rawText) ?: extractTitle(rawSubject)
    return ParsedShare(
      title = title,
      lat = pin?.first,
      lng = pin?.second,
      hasUrl = hasUrl,
    )
  }

  private fun firstValidCoord(source: String): Pair<Double, Double>? {
    for (match in COORD_REGEX.findAll(source)) {
      val lat = match.groupValues[1].toDoubleOrNull() ?: continue
      val lng = match.groupValues[2].toDoubleOrNull() ?: continue
      if (lat in -90.0..90.0 && lng in -180.0..180.0) {
        return lat to lng
      }
    }
    return null
  }

  private fun extractTitle(source: String): String? {
    if (source.isBlank()) return null
    // RT-5: strip full URLs first, then remaining coord tokens, then first non-empty line.
    var cleaned = URL_REGEX.replace(source, " ")
    cleaned = COORD_REGEX.replace(cleaned, " ")
    for (line in cleaned.lineSequence()) {
      val trimmed = line.trim()
      if (trimmed.isNotEmpty()) return trimmed
    }
    return null
  }
}

data class ShareIntake(
  val title: String?,
  val lat: Double?,
  val lng: Double?,
  val hasUrl: Boolean,
) {
  val hasPin: Boolean
    get() = lat != null && lng != null
}

enum class B2Begin { NAME_LOCK_NOW, AWAIT_EVENT }

fun interface B2MissResolver {
  fun begin(title: String): B2Begin
}

object B2MissResolvers {
  val NAME_LOCK: B2MissResolver = B2MissResolver { B2Begin.NAME_LOCK_NOW }
  val AWAIT_EVENT: B2MissResolver = B2MissResolver { B2Begin.AWAIT_EVENT }
}

enum class ShareOutcome {
  HIT,
  PIN,
  AMBIGUOUS,
  MISS_NAME_LOCKED,
  MISS_PENDING,
  NO_TITLE,
}
