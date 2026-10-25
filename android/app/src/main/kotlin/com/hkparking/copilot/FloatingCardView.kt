package com.hkparking.copilot

data class CardUiState(
  val visible: Boolean = false,
  val carparkId: String = "",
  val title: String = "",
  val subtitle: String = "",
  val showManualButtons: Boolean = false,
  val manualActions: List<String> = emptyList(),
  val transitionMs: Int = 0,
  val backgroundColor: Int = 0,
  val blurRadius: Float = 0f,
)

class FloatingCardView(
  private val onConfirm: () -> Unit,
  private val onNext: () -> Unit,
  private val onDismiss: () -> Unit,
) {
  var uiState: CardUiState = CardUiState()
    private set

  private var overlayView: android.view.View? = null
  private var windowManager: android.view.WindowManager? = null

  fun show(state: CardUiState, context: android.content.Context?) {
    uiState = state
    if (!state.visible || context == null) return
    runCatching { attach(context, state) }
  }

  fun hide() {
    uiState = uiState.copy(visible = false, manualActions = emptyList())
    detach()
  }

  fun performCardTap() {
    if (!uiState.visible) return
    onConfirm()
  }

  fun performSwipeDismiss() {
    onDismiss()
  }

  fun performManualNext() {
    if (!uiState.visible) return
    onNext()
  }

  fun performManualConfirm() {
    if (!uiState.visible) return
    onConfirm()
  }

  fun performManualCancel() {
    onDismiss()
  }

  private fun attach(context: android.content.Context, state: CardUiState) {
    val wm = runCatching { context.getSystemService(android.view.WindowManager::class.java) }.getOrNull() ?: return
    detach()
    windowManager = wm
    val root = android.widget.LinearLayout(context).apply {
      orientation = android.widget.LinearLayout.VERTICAL
      setBackgroundColor(state.backgroundColor)
      setPadding(24, 16, 24, 16)
      setOnClickListener { performCardTap() }
    }
    root.addView(text(context, state.title, 18f))
    root.addView(text(context, state.subtitle, 14f))
    if (state.showManualButtons) {
      val row = android.widget.LinearLayout(context).apply {
        orientation = android.widget.LinearLayout.HORIZONTAL
      }
      state.manualActions.forEach { label ->
        val button = android.widget.Button(context).apply {
          text = label
          setOnClickListener {
            when (label) {
              ACTION_NEXT -> performManualNext()
              ACTION_CONFIRM -> performManualConfirm()
              ACTION_CANCEL -> performManualCancel()
            }
          }
        }
        row.addView(button)
      }
      root.addView(row)
    }
    val params = android.view.WindowManager.LayoutParams(
      android.view.WindowManager.LayoutParams.MATCH_PARENT,
      cardHeightPx(context),
      android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
      android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
      android.graphics.PixelFormat.TRANSLUCENT,
    )
    params.gravity = android.view.Gravity.BOTTOM
    runCatching {
      if (android.os.Build.VERSION.SDK_INT >= 31 && wm.isCrossWindowBlurEnabled) {
        params.flags = params.flags or android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        params.blurBehindRadius = state.blurRadius.toInt()
      }
    }
    runCatching { wm.addView(root, params) }.onSuccess { overlayView = root }
  }

  private fun text(context: android.content.Context, value: String, sizeSp: Float): android.widget.TextView {
    return android.widget.TextView(context).apply {
      text = value
      setTextColor(android.graphics.Color.WHITE)
      textSize = sizeSp
    }
  }

  private fun cardHeightPx(context: android.content.Context): Int {
    val metrics = runCatching { context.resources.displayMetrics }.getOrNull() ?: return android.view.WindowManager.LayoutParams.WRAP_CONTENT
    val maxDp = NativeConfig.OVERLAY_MAX_CARD_HEIGHT_DP * metrics.density
    val maxCover = metrics.heightPixels * NativeConfig.OVERLAY_MAX_SCREEN_COVERAGE
    return minOf(maxDp, maxCover.toFloat()).toInt().coerceAtLeast(1)
  }

  private fun detach() {
    val view = overlayView ?: return
    runCatching { windowManager?.removeView(view) }
    overlayView = null
    windowManager = null
  }

  companion object {
    const val ACTION_NEXT = "下一個"
    const val ACTION_CONFIRM = "確認"
    const val ACTION_CANCEL = "取消"
    val MANUAL_ACTIONS = listOf(ACTION_NEXT, ACTION_CONFIRM, ACTION_CANCEL)
    val CARD_BACKGROUND = 0xCC121212.toInt()
    const val CARD_BLUR_RADIUS = 16f
  }
}
