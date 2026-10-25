package com.hkparking.copilot.sim

import com.hkparking.copilot.di.VoiceInput
import com.hkparking.copilot.voice.CommandCodes
import com.hkparking.copilot.voice.ParseContext
import com.hkparking.copilot.voice.ParseResult

// SIM: debug source set only
class SimVoiceInput : VoiceInput {
  private var listener: ((String, Double?) -> Unit)? = null
  private var controlListener: ((String, Int?, String?) -> Unit)? = null
  var isListening: Boolean = false
    private set
  var lastParseResult: ParseResult? = null
    private set

  override fun setUtteranceListener(listener: ((String, Double?) -> Unit)?) {
    this.listener = listener
  }

  fun setSimControlListener(listener: ((event: String, intValue: Int?, stringValue: String?) -> Unit)?) {
    controlListener = listener
  }

  override fun startListening(hotword: Boolean) {
    isListening = true
  }

  override fun stopListening() {
    isListening = false
  }

  fun injectUtterance(text: String, confidence: Double? = null) {
    lastParseResult = CommandCodes.parse(text, ParseContext(confidence = confidence))
    if (!isListening) return
    listener?.invoke(text, confidence)
  }

  fun emitSimControl(event: String, intValue: Int? = null, stringValue: String? = null) {
    controlListener?.invoke(event, intValue, stringValue)
  }

  fun recordParse(result: ParseResult) {
    lastParseResult = result
  }
}
