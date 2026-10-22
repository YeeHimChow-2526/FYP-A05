package com.hkparking.copilot.di

import com.hkparking.copilot.net.ApiTransport

object Bindings {
  val voiceInput: VoiceInput = SttVoiceInput()
  val locationSource: LocationSource = FusedLocationSource()
  val apiTransport: ApiTransport = HttpApiTransport()

  fun attachService(service: com.hkparking.copilot.FloatingBubbleService) {}

  fun detachService(service: com.hkparking.copilot.FloatingBubbleService) {}
}
