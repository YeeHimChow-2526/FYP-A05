package com.hkparking.copilot

import android.Manifest

/**
 * t1253 權限政策（單一來源）。
 * - location：必要（精確；只得大約＝唔夠）。行程開始前由用戶撳「出發」觸發系統對話框。
 * - notifications：可選（Android 13+ 先有）；拒絕都照開程（前景服務通知仍喺工作管理員顯示）。
 * - bluetooth：可選（Android 12+）；用嚟偵測熄匙／泊好，冇就手動確認泊好。
 * - microphone：可選；只喺用戶撳語音開關時要求。拒絕＝TTS＋手動。
 * 懸浮窗、通知存取權（舊模式 A）唔喺呢度要求。
 * 「要去系統設定」只係根據 Android 嘅 shouldShowRequestPermissionRationale＋我哋問過嘅紀錄推斷，唔聲稱「永久拒絕」。
 */
object TripPermissions {
  const val LOCATION = "location"
  const val NOTIFICATIONS = "notifications"
  const val BLUETOOTH = "bluetooth"
  const val MICROPHONE = "microphone"
  val KINDS = listOf(LOCATION, NOTIFICATIONS, BLUETOOTH, MICROPHONE)

  const val GRANTED = "granted"
  const val APPROXIMATE = "approximate"   // 只有大約位置（精確未批）
  const val DENIED = "denied"
  const val NOT_APPLICABLE = "notApplicable" // 呢個 Android 版本冇呢個執行期權限

  fun manifestPermissions(kind: String, sdk: Int): List<String> = when (kind) {
    LOCATION -> listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    NOTIFICATIONS -> if (sdk >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
    BLUETOOTH -> if (sdk >= 31) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList()
    MICROPHONE -> listOf(Manifest.permission.RECORD_AUDIO)
    else -> emptyList()
  }

  interface Host {
    val sdk: Int
    fun granted(permission: String): Boolean
    fun rationale(permission: String): Boolean
    fun askedBefore(kind: String): Boolean
  }

  fun state(kind: String, host: Host): String {
    val perms = manifestPermissions(kind, host.sdk)
    if (perms.isEmpty()) return NOT_APPLICABLE
    if (kind == LOCATION) {
      val fine = host.granted(Manifest.permission.ACCESS_FINE_LOCATION)
      val coarse = host.granted(Manifest.permission.ACCESS_COARSE_LOCATION)
      return when {
        fine -> GRANTED
        coarse -> APPROXIMATE
        else -> DENIED
      }
    }
    return if (perms.all { host.granted(it) }) GRANTED else DENIED
  }

  /** 仲可唔可以彈系統對話框：未問過，或者 Android 話應該解釋（即係用戶拒絕過但未揀「唔再問」）。 */
  fun canRequest(kind: String, host: Host): Boolean {
    val s = state(kind, host)
    if (s == GRANTED || s == NOT_APPLICABLE) return false
    val key = if (kind == LOCATION) Manifest.permission.ACCESS_FINE_LOCATION else manifestPermissions(kind, host.sdk).first()
    return !host.askedBefore(kind) || host.rationale(key)
  }

  fun status(kind: String, host: Host): Map<String, Any?> = mapOf(
    "state" to state(kind, host),
    "canRequest" to canRequest(kind, host),
    "askedBefore" to host.askedBefore(kind),
  )

  fun allStatus(host: Host, locationServicesOn: Boolean): Map<String, Any?> =
    KINDS.associateWith { status(it, host) } + mapOf("locationServicesOn" to locationServicesOn)

  /** 行程開始必要條件（同 Dart 一致）：精確位置＋系統定位開咗。通知唔再阻擋。 */
  fun missingForStart(host: Host, locationServicesOn: Boolean): List<String> {
    val missing = mutableListOf<String>()
    if (state(LOCATION, host) != GRANTED) missing += StartupPermissionChecker.FINE_LOCATION
    if (!locationServicesOn) missing += LOCATION_SERVICES_OFF
    return missing
  }

  const val LOCATION_SERVICES_OFF = "系統定位未開"
}

/** App 畫面係咪真係見到（MainActivity onResume/onPause）。語音辨識只可以喺見到 App 時由用戶動作開。 */
object AppVisibility {
  @Volatile var foreground: Boolean = false
}
