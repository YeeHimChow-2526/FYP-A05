package com.hkparking.copilot.net

import java.util.Locale

/**
 * Step 3.5（SC-018／RT-5）：雲端 API 端點設定檢查。
 * Base URL／API key 只由 BuildConfig（local.properties／-P／環境變數）注入，絕不入庫。
 * 規則：一律要 https；只有 debug（allowLocalCleartext=true）先准 http，而且只限本機迴環主機
 * （精確全等，同 network_security_config.xml includeSubdomains=false）。
 * scheme／host 用 Locale.ROOT 小寫正規化；拒絕尾點 localhost.、子網域、未列入白名單嘅 host。
 * 唔合規（空白、壞 URL、非本機 http、冇 key）→ 視為「未設定」，傳輸層直接回 networkError（離線分支），唔會 crash。
 * 永不關閉 SSL 驗證；https 一律用系統預設信任鏈。
 */
object ApiEndpointPolicy {
  /** debug 先准 http 嘅主機：本機、模擬器指向電腦嘅 10.0.2.2；真機用 `adb reverse` 再打 localhost。 */
  val LOCAL_CLEARTEXT_HOSTS: Set<String> = setOf("localhost", "127.0.0.1", "10.0.2.2")

  enum class Reason { OK, BLANK_URL, BLANK_KEY, BAD_URL, UNSUPPORTED_SCHEME, CLEARTEXT_NOT_ALLOWED }

  data class Verdict(
    val reason: Reason,
    val normalizedBaseUrl: String = "",
    val host: String = "",
  ) {
    val usable: Boolean get() = reason == Reason.OK
  }

  fun check(baseUrl: String, apiKey: String, allowLocalCleartext: Boolean): Verdict {
    val raw = baseUrl.trim()
    if (raw.isEmpty()) return Verdict(Reason.BLANK_URL)
    val uri = runCatching { java.net.URI(raw) }.getOrNull() ?: return Verdict(Reason.BAD_URL)
    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return Verdict(Reason.BAD_URL)
    val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
    if (host.isEmpty() || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) {
      return Verdict(Reason.BAD_URL)
    }
    when (scheme) {
      "https" -> Unit
      "http" -> if (!allowLocalCleartext || host !in LOCAL_CLEARTEXT_HOSTS) {
        return Verdict(Reason.CLEARTEXT_NOT_ALLOWED, host = host)
      }
      else -> return Verdict(Reason.UNSUPPORTED_SCHEME, host = host)
    }
    // 保守：有 URL 冇 key 都當未設定，唔好未認證就打伺服器。
    if (apiKey.isBlank()) return Verdict(Reason.BLANK_KEY, host = host)
    return Verdict(Reason.OK, normalizedBaseUrl = raw.trimEnd('/'), host = host)
  }

  /**
   * RT-5：拼接 base＋path；只接受以 `/v1/` 開頭且無 `?`／`#` 嘅 path；
   * 結果再做 URI 終檢（scheme／host 規則同 [check]）。
   */
  fun joinUrl(normalizedBaseUrl: String, path: String, allowLocalCleartext: Boolean): String? {
    if (!path.startsWith("/v1/") || path.contains('?') || path.contains('#')) return null
    val full = normalizedBaseUrl.trimEnd('/') + "/" + path.trimStart('/')
    val uri = runCatching { java.net.URI(full) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
    val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
    if (host.isEmpty() || uri.rawUserInfo != null) return null
    when (scheme) {
      "https" -> Unit
      "http" -> if (!allowLocalCleartext || host !in LOCAL_CLEARTEXT_HOSTS) return null
      else -> return null
    }
    return full
  }
}
