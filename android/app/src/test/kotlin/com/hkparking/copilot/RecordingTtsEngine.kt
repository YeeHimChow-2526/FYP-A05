package com.hkparking.copilot

import com.hkparking.copilot.voice.TtsEngine

class RecordingTtsEngine : TtsEngine {
  var boundLocale: String? = null
    private set
  val spoken = mutableListOf<String>()

  override fun bindLocale(locale: String): Boolean {
    boundLocale = locale
    return true
  }

  override fun speak(text: String, onDone: () -> Unit, onError: () -> Unit) {
    spoken += text
    onDone()
  }

  override fun stop() {}
}
