package com.hkparking.copilot.voice

import com.hkparking.copilot.NativeConfig

enum class CommandCode {
  FIND_PARKING,
  SHOW_DETAILS,
  NEXT_OPTION,
  CONFIRM_TARGET,
  CANCEL,
  SET_DESTINATION,
  ON_SITE,
  METER_PREFERENCE_OVERRIDE,
  UNKNOWN,
}

enum class PendingQuestion {
  NONE,
  RECOMMENDATION_CONFIRM,
  SCARCITY_EXPAND,
  CLARIFY_DESTINATION,
  SILENCE_DRIVE_ASK_DEST,
}

enum class PreferenceOverride {
  NONE,
  METERS_FOCUS,
}

enum class PendingInvalidation {
  TIMEOUT,
  CANCEL,
  DESTINATION_OR_CANDIDATE_CHANGED,
  DIALOGUE_INTERRUPTED,
}

data class ParseContext(
  val pendingQuestion: PendingQuestion = PendingQuestion.NONE,
  val isTtsSpeaking: Boolean = false,
  val isMapsSpeaking: Boolean = false,
  val isBluetoothSwitching: Boolean = false,
  val isInCall: Boolean = false,
  val confidence: Double? = null,
)

data class ParseResult(
  val code: CommandCode,
  val destinationName: String? = null,
  val preferenceOverride: PreferenceOverride = PreferenceOverride.NONE,
  val accepted: Boolean = false,
  val confirmed: Boolean = false,
  val spokenCue: String? = null,
  val lowConfidence: Boolean = false,
  val missingConfidenceNotTreatedAsZero: Boolean = false,
  val blockedAsSelfHearing: Boolean = false,
)

object CommandCodes {
  fun invalidatePending(reason: PendingInvalidation): PendingQuestion {
    return PendingQuestion.NONE
  }

  fun parse(text: String, context: ParseContext = ParseContext()): ParseResult {
    val confidence = context.confidence
    val missingConfidence = confidence == null || confidence < 0.0
    if (!missingConfidence && confidence < NativeConfig.STT_CONFIDENCE_THRESHOLD) {
      return ParseResult(
        code = CommandCode.UNKNOWN,
        accepted = false,
        confirmed = false,
        spokenCue = ZhHkTemplates.LOW_CONFIDENCE,
        lowConfidence = true,
        missingConfidenceNotTreatedAsZero = false,
      )
    }
    val mapped = mapUtterance(text.trim())
    if (mapped.code != CommandCode.CONFIRM_TARGET) {
      return mapped.copy(
        accepted = mapped.code != CommandCode.UNKNOWN,
        missingConfidenceNotTreatedAsZero = missingConfidence,
      )
    }
    val selfHearing = context.isTtsSpeaking ||
      context.isMapsSpeaking ||
      context.isBluetoothSwitching ||
      context.isInCall
    val bound = context.pendingQuestion != PendingQuestion.NONE && !selfHearing
    return mapped.copy(
      accepted = bound,
      confirmed = bound,
      blockedAsSelfHearing = selfHearing,
      missingConfidenceNotTreatedAsZero = missingConfidence,
    )
  }

  private fun mapUtterance(text: String): ParseResult {
    if (text.contains("搵個錶位") || text.contains("想泊街錶")) {
      return ParseResult(
        code = CommandCode.METER_PREFERENCE_OVERRIDE,
        preferenceOverride = PreferenceOverride.METERS_FOCUS,
      )
    }
    if (CANCEL_TOKENS.any { text.contains(it) }) {
      return ParseResult(code = CommandCode.CANCEL)
    }
    if (text.contains("詳細啲")) {
      return ParseResult(code = CommandCode.SHOW_DETAILS)
    }
    if (NEXT_TOKENS.any { text.contains(it) }) {
      return ParseResult(code = CommandCode.NEXT_OPTION)
    }
    val destination = extractDestination(text)
    if (destination != null) {
      return ParseResult(code = CommandCode.SET_DESTINATION, destinationName = destination)
    }
    if (ON_SITE_TOKENS.any { text.contains(it) }) {
      return ParseResult(code = CommandCode.ON_SITE)
    }
    if (isConfirm(text)) {
      return ParseResult(code = CommandCode.CONFIRM_TARGET)
    }
    if (FIND_TOKENS.any { text.contains(it) }) {
      return ParseResult(code = CommandCode.FIND_PARKING)
    }
    return ParseResult(code = CommandCode.UNKNOWN)
  }

  private fun extractDestination(text: String): String? {
    val go = GO_DESTINATION.find(text)
    if (go != null) {
      return cleanName(go.groupValues[1])
    }
    val dest = DESTINATION.find(text)
    if (dest != null) {
      return cleanName(dest.groupValues[1])
    }
    return null
  }

  private fun cleanName(raw: String): String {
    return raw.trim().trimEnd('。', '！', '!', '？', '?', '，', ',')
  }

  private fun isConfirm(text: String): Boolean {
    if (text.contains("好的") || text.contains("去呢個")) {
      return true
    }
    if (text.equals("Yes", ignoreCase = true)) {
      return true
    }
    return text == "好" || text.startsWith("好，") || text.startsWith("好,") || text.startsWith("好 ")
  }

  private val CANCEL_TOKENS = listOf("唔使", "取消", "收埋", "等等")
  private val NEXT_TOKENS = listOf("有冇第二個", "下一個", "轉場")
  private val ON_SITE_TOKENS = listOf("附近", "呢度", "而家位置")
  private val FIND_TOKENS = listOf("我要泊車", "泊車", "搵位", "出發")
  private val GO_DESTINATION = Regex("""泊車助手[，,]\s*去\s*(.+)""")
  private val DESTINATION = Regex("""目的地\s*(.+)""")
}

object ZhHkTemplates {
  val speechLocale: String = NativeConfig.SPEECH_LOCALE_DEFAULT

  const val WAKE_ACK = "喺度，想去邊度？"
  const val NO_MORE_OPTIONS = "暫時冇其他選擇"
  const val PLACE_CLARIFY = "邊一區？"
  const val SILENCE_DRIVE = "出發緊？去邊度等我睇定車位？"
  const val NETWORK_SLOW = "網絡有啲慢，我再試吓"
  const val NETWORK_FAILED = "網絡唔順，遲啲再試"
  const val B1_DESTINATION_MISSED = "聽唔到目的地，請再講一次"
  const val ON_SITE_LOCATION_MISSED = "暫時搵唔到你位置，請稍後再講一次"
  const val CROWD_YELLOW_FLAG = "近期有司機回報疑似霸位，請留意現場"
  const val CROWD_SINGLE_LABEL = "⚠️ 近期有司機回報疑似霸位"
  const val CHRONIC_HOTSPOT_LABEL = "⚠️ 常有雪糕筒霸位"
  const val METER_STALE_SPOKEN = "附近可能有咪錶，資料暫時未更新"
  const val METER_STALE_UI = "咪錶資料暫時未更新"
  const val LOW_CONFIDENCE = "太嘈聽唔清，稍後再試"
  const val ASK_DESTINATION = "去邊度？"
  const val TAP_CAPSULE = "請拍一拍膠囊答我"
  const val NOTIFICATION_BODY = "🅿️ 需要搵車位就撳我"
  const val NOTIFICATION_ACTION = "開始泊車助手（啟用語音）"

  fun directLock(name: String): String {
    return "收到，為你鎖定$name，搜尋緊周邊車位。"
  }

  fun implicitEcho(districtFeature: String, fullName: String): String {
    return "收到，為你鎖定【$districtFeature】[$fullName]，搜尋緊周邊車位。"
  }

  fun permissionRepair(missing: String): String {
    return "泊車副駕需要補授權：$missing。請泊好車後再處理。"
  }

  fun withVoiceReplyPrompt(cue: String, voiceMode: TripVoiceMode, isMicFgsActive: Boolean): String {
    if (voiceMode != TripVoiceMode.FULL_VOICE || isMicFgsActive) {
      return cue
    }
    if (cue.endsWith(TAP_CAPSULE)) {
      return cue
    }
    return cue + TAP_CAPSULE
  }
}
