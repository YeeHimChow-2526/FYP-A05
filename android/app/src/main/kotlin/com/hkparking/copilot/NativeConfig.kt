package com.hkparking.copilot

// 來源：實作版 Doc v2.9.1 PART 2 §二.1。命名見 docs/reference/native_config_glossary.md
object NativeConfig {
  // --- 車載卡片尺寸（原生 FloatingCardView）---
  // 卡片高 = min(OVERLAY_MAX_CARD_HEIGHT_DP, OVERLAY_MAX_SCREEN_COVERAGE × 螢幕高)
  const val OVERLAY_MAX_CARD_HEIGHT_DP = 175
  const val OVERLAY_MAX_SCREEN_COVERAGE = 0.4
  const val CARD_PAGE_TRANSITION_MS = 350  // 追問切卡動畫；唔自動彈卡

  // --- FGS 啟動握手 ---
  const val FGS_BOOTSTRAP_TIMEOUT_MS = 2000L  // 等 FGS_STARTED 上限；逾時仍 finish（MIC_READY 獨立）

  // --- 導航觸發、命中置信度與語音主動狀態機 ---
  const val NAV_CONFIDENCE_HIGH_THRESHOLD = 0.8
  const val NAV_CONFIDENCE_LOW_THRESHOLD = 0.5
  const val DISTANCE_SANITY_TOLERANCE_MULTIPLIER = 1.2
  const val TRIGGER_DISTANCE_M = 1000  // 1km JIT；已 ≤1km 即時觸發
  const val SILENCE_DRIVE_SPEED_KMH = 20
  const val SILENCE_DRIVE_DURATION_S = 30
  const val SILENCE_DRIVE_DISTANCE_M = 400
  // ALERT_BREATHING 聆聽沿用 LISTEN_*

  // --- 粵語 STT／VAD／本機字典樹 ---
  const val LISTEN_OPENING_WAIT_S = 3.0   // 起始值，真機調校
  const val LISTEN_END_SILENCE_S = 0.8    // 起始值，真機調校
  const val LISTEN_MAX_UTTERANCE_S = 8.0  // 起始值，真機調校
  const val STT_RESULT_TIMEOUT_S = 5.0    // 起始值，真機調校
  // 收音計時由本機 VAD 擁有；未啟用熱詞時本句完結即停 AudioRecord 並降回 location
  const val VOICE_CAPABILITY_CHECK_TIMEOUT_MS = 1500L  // 與握手並行；唔阻塞 FGS_STARTED
  const val SPEECH_LOCALE_DEFAULT = "zh-HK"  // 唔靜默回退普通話
  const val STT_CONFIDENCE_THRESHOLD = 0.6  // −1／缺失唔當 0；唔跨引擎比較
  const val TRIE_LOOKUP_BUDGET_MS = 150

  // --- 軟件距離與到場（外圈／核心）---
  const val APPROACH_RADIUS_M = 150
  const val APPROACH_CORE_RADIUS_M = 80
  const val APPROACH_SPEED_THRESHOLD_KMH = 8
  const val APPROACH_CONSECUTIVE_S = 3
  const val DEAD_RECKONING_SPEED_KMH = 15
  const val GPS_FIX_STALE_S = 5  // 仍在請求但最後有效 fix 過此秒
  const val APPROACH_EXIT_DISTANCE_M = 200  // 起始值，真機調校
  const val APPROACH_EXIT_HOLD_S = 10       // 起始值，真機調校
  const val HIGH_FREQ_EXIT_RADIUS_M = 200
  const val RADAR_MUTE_SPEED_KMH = 8
  const val TEARDOWN_FADE_MS = 500
  // --- 距離追蹤 ---
  const val VALID_FIX_MAX_ACCURACY_M = 100  // 超過唔當有效 fix；起始值，真機調校
  const val DISTANCE_TICK_MS = 1000L        // 只計時、唔開 GPS

  // --- 分級定位採樣 (FGS 運行期間；省電 vs 精度) ---
  // >1km：BALANCED，約每 30s；≤1km：BALANCED，約每 5s；≤150m：HIGH_ACCURACY，1Hz
  const val LOC_INTERVAL_FAR_MS = 30000L
  const val LOC_INTERVAL_NEAR_MS = 5000L
  const val LOC_INTERVAL_APPROACH_MS = 1000L
  const val LOC_PRIORITY_FAR = "BALANCED"
  const val LOC_PRIORITY_NEAR = "BALANCED"
  const val LOC_PRIORITY_APPROACH = "HIGH_ACCURACY"
  // --- 熄匙 ---
  const val ENGINE_OFF_DEBOUNCE_MS = 2000L  // 起始值，真機調校；見 glossary
  const val ENGINE_OFF_MAX_SPEED_KMH = 8    // 行車中拔線唔當熄匙；真機調校

  // --- 今日行程 ---
  const val TODAY_TRIPS_PREFS_NAME = "hkparking_copilot"
  const val TODAY_TRIPS_JSON_KEY = "today_trips_json"
  const val TODAY_TRIPS_HKT_DATE_KEY = "today_trips_hkt_date"
  const val TODAY_TRIP_NOTES_MAX_CHARS = 140  // 截斷上限

  // --- 打分權重（客戶端只備查；權威分在雲端 .env）---
  const val SCORE_WEIGHT_DIST = 50.0
  const val SCORE_WEIGHT_VAC = 40.0
  const val SCORE_D_MAX_M = 1500

  // --- 車載網絡防禦（ApiClient.kt；口播耗時另計；見 glossary）---
  const val API_TIMEOUT_MS = 2500L
  const val API_MAX_ATTEMPTS = 3  // 普通逾時總嘗試；429 另計（見 glossary / specs/03）
  const val API_RETRY_INTERVAL_MS = 1000L
  const val RATE_LIMIT_RETRY_DELAY_MS = 5000L  // 無 Retry-After；之後只再試一次
  const val RATE_LIMIT_MAX_WAIT_MS = 10000L    // Retry-After 等候上限
  const val RATE_LIMIT_RETRY_AFTER_MIN_S = 1L  // coerceIn 下限
  const val RATE_LIMIT_RETRY_AFTER_MAX_S = 10L // coerceIn 上限
  const val MAX_RESPONSE_BYTES = 256 * 1024    // 256 KiB；超限當 networkError
  const val API_IO_POOL_CORE = 2
  const val API_IO_POOL_MAX = 6                // 滿載拒絕 → networkError
  const val ON_SITE_GPS_ACCURACY_MAX_M = 100
  // MVP：不設 outbox / DataStore / SQLite 通用離線同步；唯一本機保留項為今日行程待確認（網絡恢復自動送出；香港時間 00:00 清除）
  // 數據新鮮度：只讀伺服器回傳 data_age_seconds／is_stale／source_status；客戶端嚴禁以手機時鐘計算
}
