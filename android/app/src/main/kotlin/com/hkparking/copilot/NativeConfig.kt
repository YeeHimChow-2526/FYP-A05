package com.hkparking.copilot

// 來源：實作版 Doc v2.9.1 PART 2 §二.1（行程期間全部行車常數；Kotlin 單一事實來源）
object NativeConfig {
  // --- 車載卡片尺寸（原生 FloatingCardView）---
  const val OVERLAY_MAX_CARD_HEIGHT_DP = 175            // 卡片高度上限 (dp)
  const val OVERLAY_MAX_SCREEN_COVERAGE = 0.4           // 卡片高度 = min(OVERLAY_MAX_CARD_HEIGHT_DP, OVERLAY_MAX_SCREEN_COVERAGE × 螢幕高度)
  const val CARD_PAGE_TRANSITION_MS = 350               // 追問「有冇第二個」切換下一張卡片之動畫時長（不自動彈卡、不設倒數）

  // --- FGS 啟動握手 ---
  const val FGS_BOOTSTRAP_TIMEOUT_MS = 2000L            // FgsBootstrapActivity 等候 FGS_STARTED 上限；逾時照樣 finish() 並記錄失敗原因（MIC_READY 為獨立訊號）

  // --- 導航觸發、命中置信度與語音主動狀態機（TripStateMachine.kt）---
  const val NAV_CONFIDENCE_HIGH_THRESHOLD = 0.8         // 高置信度門檻 (>=0.8 靜默鎖定)
  const val NAV_CONFIDENCE_LOW_THRESHOLD = 0.5          // 低置信度門檻 (<0.5 丟棄 Drop)
  const val DISTANCE_SANITY_TOLERANCE_MULTIPLIER = 1.2  // 容許 GPS 飄移微小公差倍數
  const val TRIGGER_DISTANCE_M = 1000                   // 進入目的地 1km 踩入預備區發起唯一 JIT；FGS 啟動當下已 ≤1km 則即時觸發
  const val SILENCE_DRIVE_SPEED_KMH = 20                // 判定車輛已正式出發最低時速
  const val SILENCE_DRIVE_DURATION_S = 30               // 連續高於上述時速達此秒數觸發主動追問
  const val SILENCE_DRIVE_DISTANCE_M = 400              // 備用行駛距離判定條件
  // ALERT_BREATHING（琥珀呼吸）口播追問之聆聽沿用下方收音計時常數（LISTEN_*）

  // --- 粵語 STT 噪音防禦、極簡口播與本機字典樹 ---
  const val LISTEN_OPENING_WAIT_S = 3.0                 // 提示音／TTS 播完且音訊輸入就緒後開始計；期間未偵測到人聲即關閉聆聽（起始值，真機調校）
  const val LISTEN_END_SILENCE_S = 0.8                  // 偵測到人聲後連續靜音達此值即結束本句（講完自動停；起始值，真機調校）
  const val LISTEN_MAX_UTTERANCE_S = 8.0                // 由首次偵測到人聲起計之單句上限（起始值，真機調校）
  const val STT_RESULT_TIMEOUT_S = 5.0                  // 音訊已停止輸入但引擎未回傳結果之等候上限；逾時按「聽唔清」處理
  // 收音計時由本機語音控制層（VAD）擁有，不只依賴 Android 靜音 extras；未啟用熱詞時本句完結即停止 AudioRecord 並降級回 location 類型
  const val VOICE_CAPABILITY_CHECK_TIMEOUT_MS = 1500L   // 每程開程靜默重查離線粵語 STT／TTS 之逾時；與 FGS 握手並行，絕不阻塞 FGS_STARTED
  const val SPEECH_LOCALE_DEFAULT = "zh-HK"             // 本產品 zh-HK＝粵語；語音轉接層對應引擎 locale／voice，絕不靜默回退普通話
  const val STT_CONFIDENCE_THRESHOLD = 0.6              // 粵語 STT 信心度門檻 (低於門檻單向口播「太嘈聽唔清，稍後再試」即刻閉嘴休眠；信心度缺失或 −1 不當作 0，另行分支；分數不可跨引擎直接比較，按目標引擎真機校準)；粵語 STT 延遲真機實測，不設預設數值
  const val TRIE_LOOKUP_BUDGET_MS = 150                 // 本機字典樹由文字查出坐標 <150ms

  // --- 軟件距離追蹤與到場判定 (抗 GPS 飄移；須 location FGS 已由用戶互動啟動) ---
  const val APPROACH_RADIUS_M = 150                     // 外圈接近半徑 (切換 HIGH_ACCURACY 1Hz)；PARKED_CONFIRMED 目標場範圍
  const val APPROACH_CORE_RADIUS_M = 80                 // APPROACH_MUTED 核心命中半徑
  const val APPROACH_SPEED_THRESHOLD_KMH = 8            // 判定進場排隊之低車速上限
  const val APPROACH_CONSECUTIVE_S = 3                  // 連續低於門檻達此秒數進入 APPROACH_MUTED
  const val DEAD_RECKONING_SPEED_KMH = 15               // 地庫前減速斷訊容許上限
  const val GPS_FIX_STALE_S = 5                         // 「無訊號」定義：仍在請求 fix，但最後一筆有效 fix 年齡 > 此秒數；150m 內低速時安全降級進入 APPROACH_MUTED
  const val APPROACH_EXIT_DISTANCE_M = 200              // APPROACH_MUTED 退回巡航距離門檻（真機調校）
  const val APPROACH_EXIT_HOLD_S = 10                   // 距離持續超過上述門檻達此秒數先退回巡航（真機調校；無 GPS 時維持靜音）
  const val HIGH_FREQ_EXIT_RADIUS_M = 200               // 高頻採樣退出半徑：持續至行程結束，或距離回退 > 此值
  const val RADAR_MUTE_SPEED_KMH = 8                    // 避讓倒車雷達強制靜音車速門檻
  const val TEARDOWN_FADE_MS = 500                      // 熄匙：完成到場判定與今日行程寫入後，淡出並停止 FGS 之時長

  // --- 分級定位採樣 (FGS 運行期間；省電 vs 精度) ---
  // >1km：BALANCED，約每 30s；≤1km：BALANCED，約每 5s；≤150m：HIGH_ACCURACY，1Hz
  // 實車耗電驗收目標：典型單程約額外 1–3% 電量（視機型 / Android 版本；以真機錄得為準）
  const val LOC_INTERVAL_FAR_MS = 30000L                // 距離目的地 >1km
  const val LOC_INTERVAL_NEAR_MS = 5000L                // 距離目的地 ≤1km 且 >150m
  const val LOC_INTERVAL_APPROACH_MS = 1000L            // 距離目的地 ≤150m (1Hz)
  const val LOC_PRIORITY_FAR = "BALANCED"
  const val LOC_PRIORITY_NEAR = "BALANCED"
  const val LOC_PRIORITY_APPROACH = "HIGH_ACCURACY"

  // --- 打分權重（與雲端 .env W_DIST / W_VAC / D_MAX_METERS 對齊；客戶端僅作文件單一事實源備查）---
  const val SCORE_WEIGHT_DIST = 50.0                    // W_dist
  const val SCORE_WEIGHT_VAC = 40.0                     // W_vac
  const val SCORE_D_MAX_M = 1500                        // D_max（公尺）

  // --- 車載網絡防禦（原生 ApiClient.kt；主動口播重試公式：3×2.5s 逾時 + 2×1s 間隔 ≈ 9.5s；口播耗時另計）---
  const val API_TIMEOUT_MS = 2500L                      // 單次請求逾時門檻
  const val API_MAX_ATTEMPTS = 3                        // 逾時重試總次數 (背景查詢靜默 + GREY_BREATHING；主動要求先口播「網絡有啲慢，我再試吓」，仍失敗「網絡唔順，遲啲再試」；GREY_BREATHING 持續至請求成功、App／FGS 結束或行程結束；背景查詢 3 次均失敗即停止自動重試，等待下一個觸發或司機要求)
  const val API_RETRY_INTERVAL_MS = 1000L               // 兩次嘗試之間的間隔（共 2 段間隔）
  const val RATE_LIMIT_RETRY_DELAY_MS = 5000L           // HTTP 429（Cloud Armor 超限）且冇 Retry-After 時之等候時長；等候後只重試一次（429 唔計入上述分級重試、唔即時重試）
  const val RATE_LIMIT_MAX_WAIT_MS = 10000L             // 429 回應 Retry-After（秒）之等候上限；單次重試仍失敗 → GREY_BREATHING，同網絡失敗分支，等司機拍膠囊重試
  const val ON_SITE_GPS_ACCURACY_MAX_M = 100            // §4.4 現場即泊 GPS 水平精度上限；不可用或大於此值視作失敗
  // MVP：不設 outbox / DataStore / SQLite 通用離線同步；唯一本機保留項為今日行程待確認（網絡恢復自動送出；香港時間 00:00 清除）
  // 數據新鮮度：只讀伺服器回傳 data_age_seconds／is_stale／source_status；客戶端嚴禁以手機時鐘計算
}
