package com.hkparking.copilot

import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

class EngineShutdownReceiver(
  private val sink: Sink,
) : BroadcastReceiver() {
  fun interface Sink {
    fun onEngineSignal(signal: EngineShutdownDetector.Signal, disconnected: Boolean)
  }

  override fun onReceive(context: Context?, intent: Intent?) {
    val action = intent?.action
    val isCar = runCatching {
      val device = intent?.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
      val deviceClass = runCatching { device?.bluetoothClass?.deviceClass }.getOrNull()
      isCarDeviceClass(deviceClass)
    }.getOrDefault(false)
    handleAction(action, isCar)
  }

  internal fun handleAction(action: String?, isCarDevice: Boolean) {
    when (action) {
      Intent.ACTION_POWER_DISCONNECTED -> sink.onEngineSignal(EngineShutdownDetector.Signal.POWER, true)
      Intent.ACTION_POWER_CONNECTED -> sink.onEngineSignal(EngineShutdownDetector.Signal.POWER, false)
      BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
        if (isCarDevice) sink.onEngineSignal(EngineShutdownDetector.Signal.BT_CAR, true)
      }
      BluetoothDevice.ACTION_ACL_CONNECTED -> {
        if (isCarDevice) sink.onEngineSignal(EngineShutdownDetector.Signal.BT_CAR, false)
      }
    }
  }

  interface Registrar {
    fun register(context: Context, receiver: BroadcastReceiver, actions: List<String>)
    fun unregister(context: Context, receiver: BroadcastReceiver)
  }

  companion object {
    fun actionsFor(bluetoothGranted: Boolean): List<String> {
      val actions = mutableListOf(
        Intent.ACTION_POWER_DISCONNECTED,
        Intent.ACTION_POWER_CONNECTED,
      )
      if (bluetoothGranted) {
        actions += BluetoothDevice.ACTION_ACL_DISCONNECTED
        actions += BluetoothDevice.ACTION_ACL_CONNECTED
      }
      return actions
    }

    fun isCarDeviceClass(deviceClass: Int?): Boolean {
      if (deviceClass == null) return false
      return deviceClass == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO ||
        deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE
    }

    var registrar: Registrar = object : Registrar {
      override fun register(context: Context, receiver: BroadcastReceiver, actions: List<String>) {
        val filter = IntentFilter()
        actions.forEach { filter.addAction(it) }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
      }

      override fun unregister(context: Context, receiver: BroadcastReceiver) {
        context.unregisterReceiver(receiver)
      }
    }
  }
}
