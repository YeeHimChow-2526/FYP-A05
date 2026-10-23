package com.hkparking.copilot.sim

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import com.hkparking.copilot.NativeConfig

// SIM: default cruise speed for debug virtual joystick
private const val CRUISE_SPEED_KMH = 30

internal fun interface SimBroadcastSender {
  fun send(context: Context, event: String, intValue: Int?)
}

internal data class JoystickButton(
  val label: String,
  val event: String,
  val intValue: Int?,
)

internal interface JoystickWindowHost {
  fun show(
    context: Context,
    buttons: List<JoystickButton>,
    onTap: (Context, JoystickButton) -> Unit,
  ): Boolean

  fun hide()
}

internal object SimJoystickOverlay {
  internal var sender: SimBroadcastSender = SimBroadcastSender { context, event, intValue ->
    val intent = android.content.Intent(SimEventReceiver.ACTION_SIM_EVENT).setPackage(context.packageName)
    intent.putExtra(SimEventReceiver.EXTRA_EVENT, event)
    if (intValue != null) {
      intent.putExtra(SimEventReceiver.EXTRA_VALUE, intValue)
    }
    context.sendBroadcast(intent)
  }

  internal var windowHost: JoystickWindowHost = DefaultJoystickWindowHost()

  val buttons: List<JoystickButton> = listOf(
    JoystickButton("D 遠", "SET_DISTANCE_M", NativeConfig.TRIGGER_DISTANCE_M + 200),
    JoystickButton("D 1km", "SET_DISTANCE_M", NativeConfig.TRIGGER_DISTANCE_M),
    JoystickButton("D 150", "SET_DISTANCE_M", NativeConfig.APPROACH_RADIUS_M),
    JoystickButton("D 80", "SET_DISTANCE_M", NativeConfig.APPROACH_CORE_RADIUS_M),
    JoystickButton("D 離開", "SET_DISTANCE_M", NativeConfig.APPROACH_EXIT_DISTANCE_M + 50),
    JoystickButton("V 0", "SET_SPEED_KMH", 0),
    JoystickButton("V 慢", "SET_SPEED_KMH", NativeConfig.APPROACH_SPEED_THRESHOLD_KMH - 1),
    JoystickButton("V 巡航", "SET_SPEED_KMH", CRUISE_SPEED_KMH),
    JoystickButton("PAUSE", "PAUSE", null),
    JoystickButton("RESUME", "RESUME", null),
  )

  var isShown: Boolean = false
    private set

  fun show(context: Context) {
    if (isShown) return
    val shown = runCatching {
      windowHost.show(context, buttons) { tapContext, button ->
        tap(tapContext, button)
      }
    }
    if (shown.getOrNull() == true) {
      isShown = true
      runCatching { Log.i("SimJoystickOverlay", "shown buttons=${buttons.size}") }
    } else {
      val failure = shown.exceptionOrNull()
      if (failure != null) {
        val e = failure
        runCatching { Log.w("SimJoystickOverlay", "show failed reason=${e::class.java.simpleName}") }
      } else {
        runCatching { Log.w("SimJoystickOverlay", "show failed reason=false") }
      }
    }
  }

  fun hide() {
    if (!isShown) return
    val hidden = runCatching { windowHost.hide() }
    isShown = false
    if (hidden.isSuccess) {
      runCatching { Log.i("SimJoystickOverlay", "hidden") }
    } else {
      hidden.exceptionOrNull()?.let { e ->
        runCatching { Log.w("SimJoystickOverlay", "hide failed reason=${e::class.java.simpleName}") }
      }
    }
  }

  internal fun tap(context: Context, button: JoystickButton) {
    runCatching { Log.i("SimJoystickOverlay", "tap event=${button.event} value=${button.intValue}") }
    val sent = runCatching { sender.send(context, button.event, button.intValue) }
    if (sent.isFailure) {
      sent.exceptionOrNull()?.let { e ->
        runCatching { Log.w("SimJoystickOverlay", "send failed reason=${e::class.java.simpleName}") }
      }
    }
  }
}

private class DefaultJoystickWindowHost : JoystickWindowHost {
  private var view: android.view.View? = null
  private var wm: WindowManager? = null

  override fun show(
    context: Context,
    buttons: List<JoystickButton>,
    onTap: (Context, JoystickButton) -> Unit,
  ): Boolean {
    if (mainThreadState() != true) {
      runCatching { Log.w("SimJoystickOverlay", "show failed reason=NotMainThread") }
      return false
    }
    val shown = runCatching {
      val windowManager = context.getSystemService(WindowManager::class.java) ?: return@runCatching false
      val layout = LinearLayout(context)
      runCatching { layout.orientation = LinearLayout.VERTICAL }
      buttons.forEach { button ->
        val widget = Button(context)
        runCatching { widget.text = button.label }
        runCatching {
          widget.setOnClickListener { v ->
            runCatching { onTap(v.context.applicationContext, button) }
          }
        }
        runCatching { layout.addView(widget) }
      }
      val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
      )
      runCatching { params.gravity = Gravity.BOTTOM or Gravity.START }
      windowManager.addView(layout, params)
      view = layout
      wm = windowManager
      true
    }
    return shown.getOrDefault(false)
  }

  override fun hide() {
    val localView = view
    val localWm = wm
    view = null
    wm = null
    if (mainThreadState() == false) {
      val posted = runCatching {
        Handler(Looper.getMainLooper()).post {
          runCatching { if (localView != null) localWm?.removeView(localView) }
        }
      }
      if (posted.isFailure) {
        runCatching { if (localView != null) localWm?.removeView(localView) }
      }
      return
    }
    runCatching { if (localView != null) localWm?.removeView(localView) }
  }

  private fun mainThreadState(): Boolean? {
    val probe = runCatching {
      val main = Looper.getMainLooper() ?: return@runCatching null
      Looper.myLooper() == main
    }
    if (probe.isFailure) return null
    return probe.getOrNull()
  }
}
