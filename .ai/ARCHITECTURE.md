---
title: "[v2.9.1 AI 全局知識] 香港泊車語音副駕 — 開發 Agent 總綱"
---

# 0. 文件定位與同步

> 本文件由實作版 v2.9.1 生成；如有衝突，以實作版為準。升版時與其餘八份文件同步升版。

文件版本：v2.9.1｜生成日期：2026-10-20（香港時間）｜用途：AI coding agent 單一去重全局知識檔（Markdown 友善）。

本文件只收錄當前設計之硬規格與鐵律；每項驗收細節以實作版正文為準。客戶端（Android 原生 Kotlin＋Flutter App 畫面）細節見客戶端資料包：`hk_parking_client_context/AGENTS.md`（NEVER／ALWAYS 鐵律）、`docs/specs/01_ui_and_voice_fsm.md`、`docs/specs/02_native_bridge_and_fgs.md`、`docs/specs/03_api_and_local_trie.md`、`docs/specs/04_mock_and_sim_shell.md`、`docs/testing/01_test_plan.md`、`docs/reference/android_manifest_notes.md`，常數檔 `android/app/src/main/kotlin/com/hkparking/copilot/NativeConfig.kt` 及 `lib/core/constants/app_config.dart`。

## 0.1 其餘八份文件

| # | 文件 | Google Doc ID | 角色 |
|---|---|---|---|
| 1 | [v2.9.1 實作版] 香港泊車語音副駕 — 系統架構與業務落地方案 | 1wzfVsSobPfCQW-TNcLnRmy9LsEB5KdUSbaUJwOfeEPo | 真理源（實作以此正文為準） |
| 2 | [v2.9] 香港泊車語音副駕 — 系統架構與業務落地方案（決定版） | 1ps7OfKJTLf2kjhQKv8yx4wzY_Z8kkF9gFP5SfnxRMf0 | 完整版（含 PART 4 架構演進日誌，只供審計） |
| 3 | 香港泊車語音副駕 v2.9.1 工作重要度排行 | 1SOFWlaC8wJsNhQdTLu6NClhQmCUNcXiFO52ua2AAIXI | 46 條任務項 P0–P3、配套組合、放棄順序 |
| 4 | 泊車副駕 測試清單 v2.9.1 | 1OaMcyeEU8m0HKuhhCLf2DCJX9iY0h1vu-ar7WGdd148 | Phase A／Phase B 測試步驟 |
| 5 | 只改 App｜角色包 | 1tkPdtSaO6lvDIaMFAwEddneDIEBBvyPrIVFVApGfGkE | 角色閱讀切片 |
| 6 | 只改雲端 API｜角色包 | 1JGIFdfswpUbr5tdYs4lL18glbOvuDrmB2nKOmT0Y-0o | 角色閱讀切片 |
| 7 | 只改數據抓數｜角色包 | 125YyZiSM7R2a4GRFFT4Mt1g5GjINA28TAV71zXmChdk | 角色閱讀切片 |
| 8 | 只改合規權限｜角色包 | 1TlgY65z-y6fTtzBGiHXPNjh71hogMranSR6_tMEo7bI | 角色閱讀切片 |

連結格式：`https://docs.google.com/document/d/<Doc ID>/edit`。

## 0.2 按角色閱讀本文件

- 只改 App：§1、§3、§4.1–4.2、§5、§6、§7、§8、§16（android/、lib/）；另讀客戶端資料包 AGENTS.md。
- 只改雲端 API：§1、§4.3、§9、§10、§11、§15、§16（server/）。
- 只改數據抓數：§1、§4.3、§12、§13、§14、§15。
- 只改合規權限：§1、§5（權限矩陣、起步權限檢查、Manifest）、§6.3（麥克風前台服務邊界）。

# 1. 寫 Code 規範（Coding Agent 鐵律）

1. 常數單一事實源：行車／語音常數一律引用 `NativeConfig.kt`；雲端參數一律引用 `.env`（W_DIST／W_VAC／D_MAX_METERS 啟動時必須 int()／Pydantic 強制轉型後先入打分，禁止以字串直接參與運算）；Flutter App 畫面參數一律引用 `app_config.dart`。業務碼禁止 magic number；新門檻先加入對應常數檔再引用。
2. `TripStateMachine.kt` 為膠囊／語音狀態機唯一擁有者；Flutter 只經 MethodChannel 讀取並顯示原生狀態，禁止於 Flutter 持有行車邏輯或建立第二個狀態機。
3. Flutter 只負責 `features/onboarding/`、`features/ignition/`（含設定）、`features/trip_history/` 及 App 內畫面；行程期間全部車內邏輯（巡航、定位、語音、卡片、重試、到場判定）於原生 Kotlin（`FloatingBubbleService.kt` 宿主）執行，行程期間 Flutter 不在背景運行（行車全程原生 Kotlin，無背景 Flutter 引擎）。
4. `source_status` 只得兩值：`"ok"`｜`"degraded"`。
5. `speech_locale` 預設 `zh-HK`（本產品 zh-HK＝粵語）；語音轉接層把 speech_locale 對應至引擎 locale／voice，絕不靜默回退普通話；App 本版只送 zh-HK、不提供語言選擇器；伺服器遇不支援之值明確回退 zh-HK 並回 `speech_locale_fallback = true`。
6. 數據新鮮度只讀伺服器回傳之 `data_age_seconds`／`is_stale`／`source_status`；客戶端嚴禁以手機時鐘計算。
7. location 類型 FGS 只於司機明確互動後啟動（六種起步方法＋現場即泊）；全程不申請 `ACCESS_BACKGROUND_LOCATION`、不使用系統 Geofencing API。
8. 嚴禁 BroadcastReceiver→Activity trampoline；嚴禁於 Service 內請求執行期權限；起步入口嚴禁彈出權限對話框（只檢查）；microphone 類型只可於用戶手勢內啟動，嚴禁延後（如 Handler 延遲、WorkManager 排程）。
9. FGS 啟動握手不設固定 sleep；FGS 啟動延遲與粵語 STT 延遲一律真機實測，文件與程式不寫死數字。
10. Pre-FGS 僅 Heads-up 通知：不繪製懸浮膠囊、不顯示 GREY／AMBER overlay；冷態單擊膠囊不構成 FGS 啟動；語音「出發」／「我要泊車」只於本程麥克風 FGS 已升級後生效（或經 Hey Google 冷態穿透）。
11. 網絡失敗一律 `GREY_BREATHING`，即使本程未有終點亦不轉 `ALERT_BREATHING`。
12. 語音優先：畫面不自動彈卡、不自動輪播、不設倒數；不擅改司機原有 Google Maps 路線；1km 預備區內不主動跳窗、不主動口播。
13. MVP 離線策略：不設 DataStore／Room／WorkManager／SQLite 持久補發隊列，不設通用離線同步；release 離線即放棄；唯一本機保留項為今日行程「待確認」（SharedPreferences）。
14. STT 只用裝置端路徑，不設雲端 STT 後備；信心度缺失或 −1 不當作 0；副駕 TTS 播放期間出現之「好」不得視作確認。
15. 狀態機只處理內部指令碼（`CommandCodes.kt`）；「好／好的／Yes」只綁定當前有效之上一條問題；所有口播及畫面文字經語言模板輸出（本版只填 zh-HK）。
16. 客戶端不嵌入 Firestore SDK；全部經 Cloud Run REST（原生 `net/ApiClient.kt`）；`firestore.rules` 為 deny-all，後端以 Admin SDK 讀寫。
17. 型別：lat／lng 為 float64／Double；`vacancies` 為 int，`-1`＝未知／源斷線之 sentinel，禁止 null；`distance_meters` 為 int（公尺，四捨五入）。
18. 排除只來自官方數據；眾包回報一律不作排除；客戶端照播伺服器 `spoken_cue`，不自行拼句、不自行排除或重排選項。
19. `L_i`、最終分數與 `cards` 每次請求即時計算、一律不入快取；GCS 區域快照與 manifest.json 必須按 generation 記憶體快取。
20. 虛擬鎖並非預約保證；UI／口播不得講成已預約或已留位。
21. 不收集裝置 ID；眾包去重只用 session_id＋來源 IP 5 分鐘群組；IP 取自負載平衡器傳入之真實客戶端地址，不接受 Request Body 自填。
22. GCS 區域快照、manifest.json 與每小時 NDJSON 一律以 ifGenerationMatch 寫入；Ingestion Job 每輪單一執行實例（task_count = 1）。
23. BigQuery 建表必須按日期分區（PARTITION BY snapshot_date）並設分區到期。
24. 實際密鑰不得明文寫入文件或版本庫（`INTERNAL_API_KEY` 只用佔位符）；內部 API Key 只作基本識別，並非安全邊界。
25. B-1 路徑 1A（Hey Google／App Actions 冷態入口）屬實驗性能力，只於驗證通過後啟用，不列入必要交付；B-1 路徑 1B 屬必要交付。
26. `RECORD_AUDIO` 只屬全語音模式之必要權限；播報＋手動／純手動模式不以 RECORD_AUDIO 作開程條件、不升級 microphone 類型。
27. 系統「大概位置」視為未授權精確位置。
28. 原生 `sim/` 只限 debug 建置，位於 debug source set `android/app/src/debug/kotlin/com/hkparking/copilot/sim/`，fixtures 位於 `android/app/src/debug/assets/mock/`；`src/main` 只引用 `di/Bindings.kt`（debug／release 各一份），無執行期旗標；`// MOCK:`／`// SIM:` 註解只准出現於 `sim/` 與 `mock/`；生產路徑不留 TODO。
29. MethodChannel／REST 欄位變更先回寫實作版，再同步客戶端資料包與本文件。

# 2. 產品定位與核心原則

- 核心原則：「零多餘摩擦、行車安全優先、符合真實用車習慣、零閒置雲端成本、無帳號無痕追蹤」。
- 數據範圍：政府開放資料（~550 間動態場、18,000+ 支咪錶）＋本地私有商場字典（~100 間封閉商場，標註限高與會員生態如 The Point、KLUB 11）；端雲共用 POI 真理源 `assets/hk_poi_master.json`（全港約 1,000 個核心商場與地標）。
- 用車習慣：司機只於「上車點火出發」時主動開啟；行車全程退居背景，不干擾 Google Maps 主導航。
- 語音優先：TTS 只口播首選（位置、空位、價錢）；卡片只於司機追問「詳細啲」／「有冇第二個」時出現；拍卡屬可選。
- 司機偏好三段：`INDOOR_ONLY`（只泊室內場）／`SMART_HYBRID`（智能推薦）／`METERS_FOCUS`（街錶優先）。
- 架構按全港設計；處理與寫入範圍由 `ENABLED_REGIONS`（試點 KE）控制。

# 3. 狀態與燈號唯一真理表

狀態機：`TripStateMachine.kt`（唯一擁有者）。膠囊外圈色獨立於空位 Status Pip。膠囊與灰／琥珀 overlay 只於 location FGS 已啟動（收到 FGS_STARTED）後存在。下表為本文件唯一狀態與燈號真理表；表內只列常數名，數值見 §4 常數總表；其他章節只引用狀態名及常數名。

| 狀態／燈號 | 外觀 | 含義 | 進入條件／觸發 | 維持／離開 |
|---|---|---|---|---|
| Pre-FGS | 無膠囊；只有 Heads-up 通知 | FGS 未啟動 | 藍牙 ACL 連線（A-1）、Maps 導航偵測（A-3）只發通知 | 預備區 JIT 預取、APPROACH_RADIUS_M 追蹤、到場判定、ALERT 出發判定全部停用；司機點擊合法入口並通過權限檢查才啟動 FGS |
| CRUISING＝GREY_STEADY | 灰穩態（不呼吸） | 巡航待命 | 收到 FGS_STARTED 後繪出膠囊；首選口播無回應且未有空位數據 | 進入預備區（TRIGGER_DISTANCE_M）或啟動當下已 ≤ TRIGGER_DISTANCE_M 即時觸發 JIT |
| AMBER_STEADY | 琥珀穩態（不呼吸） | 數據未齊 | 背景 JIT 查詢進行中且未取得完整空位；B-2 Trie Miss 背景解析中 | 成功 → NEARBY_READY 或設定終點；逾時 → GREY_BREATHING |
| GREY_BREATHING | 灰呼吸 | 網絡有問題（系統端） | 任何請求逾時（API_TIMEOUT_MS）：背景 JIT、司機主動要求、B-2 背景解析；HTTP 429 等候後單次重試仍失敗（RATE_LIMIT_RETRY_DELAY_MS／RATE_LIMIT_MAX_WAIT_MS，§10.1） | 自動重試合共 API_MAX_ATTEMPTS 次、間隔 API_RETRY_INTERVAL_MS（總耗時公式見 NativeConfig.kt 註解，口播耗時另計）；之後停止自動重試，等待下一個查詢觸發或司機要求；維持至請求成功、App／FGS 結束或行程結束 |
| ALERT_BREATHING | 琥珀呼吸 | 需要司機回應終點 | 四項觸發（見 §3.2），只於 location FGS 運行中 | 每次觸發只口播一次；維持至設定終點或行程結束 |
| NEARBY_READY | 綠／黃／紅穩態 Status Pip | 空位鬆緊（綠充足／黃緊湊／紅緊張已備後備） | JIT 成功 | 首選口播無回應即維持 pip，不追加口播 |
| EXPANDED | 原生 FloatingCardView | 追問出卡（一次一張） | 「詳細啲」／「有冇第二個」（切換 CARD_PAGE_TRANSITION_MS）；STT 不可用時拍膠囊 | 保留至確認、「唔使／收埋」、手勢滑走或進入 NAV_TO_GATE／APPROACH_MUTED；不設自動收起計時 |
| NAV_TO_GATE | 導航中 | 前往閘口 | 「好／去呢個」或拍卡（commit） | 進入到場判定 |
| APPROACH_MUTED | 到場靜音 | 只靜音主動口播；行程繼續；鎖保留；拍膠囊仍可轉場／問「有冇第二個」 | (a) APPROACH_CORE_RADIUS_M 內且車速連續 APPROACH_CONSECUTIVE_S ＜ APPROACH_SPEED_THRESHOLD_KMH；或 (b) APPROACH_RADIUS_M 內 ＜ DEAD_RECKONING_SPEED_KMH 且最後有效 fix 年齡 ＞ GPS_FIX_STALE_S（仍在請求 fix） | 距離持續 ＞ APPROACH_EXIT_DISTANCE_M 達 APPROACH_EXIT_HOLD_S 即退回巡航（真機調校）；無 GPS（地庫）維持靜音；車速 ＜ RADAR_MUTE_SPEED_KMH 強制靜音避讓倒車雷達 |
| PARKED_CONFIRMED | 淡出 | 泊妥確認 | 目標場 APPROACH_RADIUS_M 內（或地庫 GPS 斷訊狀態）＋熄匙訊號（車載藍牙 ACL 斷開或車充斷電） | 寫今日行程候選 → 在線送 release（離線放棄）→ TEARDOWN_FADE_MS 內淡出並停止 FGS；onTripEnded(PARKED_CONFIRMED) |
| 目標範圍外熄匙 | 淡出 | 結束本程 | 熄匙訊號但不在目標範圍 | 不寫候選；onTripEnded(ENGINE_SHUTDOWN) |
| 行程中撤銷必要權限 | 停止 | 結束本程 | 本模式開程必要權限被撤銷 | 停止 FGS；onTripEnded(PERMISSION_REVOKED)；補授權通知盡力發出 |
| 觸發：行車未設終點 | → ALERT_BREATHING | 觸發條件 (1) | 無終點且時速 ＞ SILENCE_DRIVE_SPEED_KMH 連續 SILENCE_DRIVE_DURATION_S，或累積行駛 SILENCE_DRIVE_DISTANCE_M | Pre-FGS 不採樣、不觸發 |
| 觸發：FGS 啟動握手 | — | Activity 等候 FGS_STARTED | startForegroundService 後 | 收到 FGS_STARTED 即 finish()；FGS_BOOTSTRAP_TIMEOUT_MS 逾時照樣 finish() 並記錄失敗原因；MIC_READY 為獨立訊號 |
| 觸發：語音能力靜默重查 | — | 每程開程 | 與 FGS 握手並行 | VOICE_CAPABILITY_CHECK_TIMEOUT_MS；逾時沿用最近一次已確認結果，從未確認者按不可用處理；絕不阻塞 FGS_STARTED |
| 觸發：收音計時 | — | 聆聽一句 | 提示音／TTS 播完且音訊輸入就緒 | LISTEN_OPENING_WAIT_S 無人聲即關閉；LISTEN_END_SILENCE_S 靜音結束本句；LISTEN_MAX_UTTERANCE_S 單句上限；STT_RESULT_TIMEOUT_S 逾時按「聽唔清」 |

## 3.1 燈號優先規則

1. 網絡失敗一律 GREY_BREATHING 並口播網絡提示（司機主動要求時），優先於 ALERT_BREATHING；即使本程未有終點亦不轉 ALERT_BREATHING。
2. ALERT_BREATHING 只用於需要司機回應終點；數據未齊用 AMBER_STEADY；首選口播無回應退回 CRUISING／NEARBY_READY，不用 ALERT_BREATHING。
3. Pre-FGS 不顯示任何膠囊燈號（無灰／琥珀 overlay）。
4. 實作版只明文規定第 1 條之優先關係；其餘燈號由所屬狀態決定，互不重疊。

## 3.2 ALERT_BREATHING 四項觸發條件

1. 行車未設終點（見真理表「觸發：行車未設終點」）。
2. 模式 A 導航通知命中中置信度終點（NAV_CONFIDENCE_LOW_THRESHOLD ≤ 置信度 ＜ NAV_CONFIDENCE_HIGH_THRESHOLD）。
3. 地名需要澄清（如「邊一區？」）。
4. 司機主動發起之操作因非網絡原因失敗（如地名無法辨識）且本程仍未有終點。

共用規則：凡需司機語音作答之口播（含 ALERT_BREATHING 澄清、稀缺區擴大詢問），若當下為全語音模式而麥克風 FGS 尚未升級，口播句尾必須加「請拍一拍膠囊答我」；STT 不可用（播報＋手動／純手動）時不加此句，地名輸入留待泊好車後於 App 處理。解析未中一律不清除已鎖定終點，亦不因每次解析未中而轉 ALERT_BREATHING。

# 4. 常數總表

以下三個程式碼區塊逐字取自實作版 PART 2 §二，為常數單一事實源；§4.4 為唯一一張補充常數表（收錄未寫入常數檔之規格數值）。其他章節只引用常數名。

## 4.1 NativeConfig.kt（行車常數，Kotlin 單一事實來源）

```kotlin
// android/app/src/main/kotlin/com/hkparking/copilot/NativeConfig.kt（行程期間全部行車常數；Kotlin 單一事實來源）
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
  // MVP：不設 outbox / DataStore / SQLite 通用離線同步；唯一本機保留項為今日行程待確認（網絡恢復自動送出；香港時間 00:00 清除）
  // 數據新鮮度：只讀伺服器回傳 data_age_seconds／is_stale／source_status；客戶端嚴禁以手機時鐘計算
}
```

## 4.2 app_config.dart（Flutter App 畫面參數）

```dart
// lib/core/constants/app_config.dart（Flutter App 內畫面：設定、新手教學、今日行程；只讀原生狀態）
class AppConfig {
  static const int permissionTutorialPages = 6;          // 教學卡片總頁數 (含麥克風本地收音說明頁、語音優先示範動畫頁與授權步驟頁)
  static const String todayTripsResetTimeHkt = "00:00";  // 今日行程（含待確認）清除時間（香港時間；與原生 TodayTripStore 一致）
}
```

## 4.3 .env（GCP Cloud Run 環境變數）

```bash
# ==========================================
# GCP Cloud Run Environment Variables (.env)
# ==========================================
# 雲端內存粗篩周邊候選車場之最大半徑 (空間過濾)
SEARCH_RADIUS_METERS=1500

# 打分公式權重與距離歸一化上限（D_max 與 SEARCH_RADIUS_METERS 對齊）
W_DIST=50
W_VAC=40
D_MAX_METERS=1500
# 啟動時必須 int()／Pydantic 強制轉型 W_DIST／W_VAC／D_MAX_METERS 後先入打分；禁止以字串直接參與運算

# 路面咪錶聚合成單一卡片之最大群聚直徑
METER_CLUSTER_RADIUS_METERS=150

# 商業激戰區咪錶聚合之最低剩餘空位門檻 (防範假空位)
METER_HOTSPOT_MIN_VACANCIES=2

# 住宅區咪錶聚合之最低剩餘空位門檻
METER_RESIDENTIAL_MIN_VACANCIES=2

# 打分公式空位比率之飽和分母 min((V - L) / X, 1.0)
VACANCY_SCORE_DENOMINATOR=15

# 數據過期階梯式門檻與扣分（按 data_age_seconds，即 source_snapshot_ts 之年齡）
STALE_TIER1_MINUTES=15
STALE_TIER1_PENALTY_POINTS=5
STALE_TIER2_MINUTES=25
STALE_TIER2_PENALTY_POINTS=20

# 資源匱乏區（稀缺保底模式）閥值
SCARCITY_POOL_MIN_COUNT=3
SCARCITY_BONUS_POINTS=30
SCARCITY_EXPAND_MULTIPLIER=2.0
# 低水位提示：私家車有效空位（privateCar.vacancy − L_i）≤ 此值即口播「隨時會滿」並只點名一個後備（不設百分比規則；不另設扣分）
LOW_WATERMARK_VACANCIES=3

# 司機眾包回報扣分權重 (基於中和 100m 距離紅利 46.7 分推導)
CROWD_REPORT_SINGLE_PENALTY=45
# ≥2 獨立來源：排序封頂（不得排在首個未被回報之選項之前）＋黃旗口播；眾包永不排除（排除只來自官方數據）
CROWD_REPORT_RANK_CAP_MIN_SOURCES=2
CROWD_REPORT_DECAY_MINUTES=60

# 慣性霸位黑點門檻 (Chronic Hotspots)
CHRONIC_BLOCKED_WINDOW_DAYS=7
CHRONIC_BLOCKED_MIN_REPORTS=3
CHRONIC_BLOCKED_PENALTY_POINTS=40

# 安全防禦與鑑權
# 佔位符：實際密鑰不得明文寫入規格文件或版本庫
# 注意：內部 API Key 打包於 APK 內，可被反編譯取出，只作基本客戶端識別，並非安全邊界
INTERNAL_API_KEY="<INTERNAL_API_KEY>"

# 速率限制由入口 Cloud Armor 負責 (按來源 IP，約 60 次/分鐘/IP，可調)，於 Terraform 變數 cloud_armor_rate_limit_per_ip_per_minute 設定，於 LB 層計數
# 香港流動網絡普遍採用 CGNAT，多名司機可共用同一公網 IP，故門檻刻意放寬
# 記憶體快取範圍（一）：GCS 區域快照（咪錶／室內場）與 manifest.json 按 generation 快取 30–60 秒，減少 GCS Class B 操作與延遲
SNAPSHOT_MEMORY_CACHE_TTL_SECONDS=45
# 虛擬鎖即時 count() 只計 Top K 候選場（按距離、營業狀態、快照空位選出；每次 count() 最少計 1 次讀取，每請求約 5 次）；L_i、最終分數與 cards 每次請求即時計算，一律不入快取（不設鎖計數快取變數）
LOCK_COUNT_TOP_K_CANDIDATES=5
# 記憶體快取範圍（二）：carpark_perks_master、carpark_pain_points 按 carpark_id 快取
IN_MEMORY_CACHE_TTL_SECONDS=30

# 虛擬鎖 (virtual_locks/{session_id} 文件 + count() 聚合) 與會話生命週期
# count() 只計算 created_at 於此窗口內之鎖文件；expire_at = created_at + 此值，由 Firestore TTL 清理舊文件 (無排程器)
VIRTUAL_LOCK_TIMEOUT_MINUTES=30
# 未完成會話清理（commit 前）：session 自最後一次請求起閒置滿此值即刪除其內容（expire_at = last_seen_at + 此值；讀取以 expire_at 過濾，Firestore TTL 負責物理刪除；無排程器、無心跳）
ACTIVE_SESSION_IDLE_TIMEOUT_MINUTES=30
# 未完成會話清理（commit 後，status = COMMITTED）：閒置滿此值即刪除其內容（expire_at = last_seen_at + 此值）；未完成行程不設評分／回報機制
COMMITTED_SESSION_IDLE_TIMEOUT_HOURS=3
# 泊妥確認（PARKED_CONFIRMED）之行程保留完成行程紀錄（司機確認後 ratable = true 先可評分），保留至完成當日完結（香港時間，暫定可調；與今日行程專頁「當日行程」一致）；expire_at = 完成當日 HKT 午夜，讀取以 expire_at 過濾；Firestore TTL 物理刪除或延遲至多約 24 小時
COMPLETED_TRIP_RETENTION_POLICY=END_OF_DAY_HKT

# 政府開放數據抓取配置
DATA_GOV_VACANCY_ENDPOINT="DATA_GOV_HK_CARPARK_API"
DATA_GOV_FETCH_TIMEOUT_SECONDS=8
DATA_GOV_METER_OCCUPANCY_ENDPOINT="DATA_GOV_HK_OCCUPANCYSTATUS_CSV"
DATA_GOV_METER_STATIC_ENDPOINT="DATA_GOV_HK_PARKINGSPACES_CSV"
# 來源失敗指數退避（本輪內重試；連續失敗次數記入 manifest.json）
SOURCE_BACKOFF_BASE_SECONDS=2
SOURCE_BACKOFF_MAX_ATTEMPTS=4
# 處理與寫入範圍（架構按全港設計）
ENABLED_REGIONS=["KE"]
# 狀態快照桶：manifest.json、咪錶／室內場區域快照、meters_static_vYYYYMMDD.json、每小時歷史 NDJSON（寫入一律 ifGenerationMatch）
GCS_STATE_BUCKET="gs://hkparking-state-snapshots"
# 咪錶完整性檢查門檻（任何一項失敗即保留上一份快照並標記 source_status = degraded）
INTEGRITY_MIN_ROW_RATIO=0.95
INTEGRITY_MIN_REGION_ID_RATIO=0.90
# 數據新鮮度：data_age_seconds = server_now − source_snapshot_ts（伺服器端計算）；超過此值即 is_stale = true
SOURCE_STALE_THRESHOLD_SECONDS=600

# 抓取節奏（Ingestion Job，由 Cloud Scheduler 觸發；深夜 01:00–06:00 節流）
INGEST_CARPARK_INTERVAL_SECONDS=60
INGEST_METER_INTERVAL_SECONDS=120
INGEST_NIGHT_INTERVAL_SECONDS=300

# 冷資料時序分析：每網格 vacant_count／unavailable_count／valid_ratio；高峰每 5–15 分鐘、離峰每 30–60 分鐘一筆，另記狀態變化事件，近零空位或來源 degraded 時加拍；每小時寫 GCS NDJSON，每日一次 Load Job 載入（job_id = load_{table}_{YYYYMMDD}_{attempt}；目的地分區 table$YYYYMMDD，WRITE_TRUNCATE）
# ⚠️ 建表時必須設定按日期分區
BQ_DATASET="hkparking_history"
BQ_TABLE="grid_occupancy_history"
BQ_PARTITION_EXPIRATION_DAYS=90
HISTORY_PEAK_INTERVAL_MINUTES=10
HISTORY_OFFPEAK_INTERVAL_MINUTES=45
BQ_DAILY_LOAD_TIME_HKT="01:30"

# 優惠水牌相片臨時桶 (7 天 TTL)
GCS_TEMP_PHOTOS_BUCKET="gs://hkparking-temp-perk-photos"

# 商場優惠雙軌仲裁
GEMINI_VISION_MODEL="gemini-2.5-flash"
PERKS_OFFICIAL_CONFIDENCE=0.9
PERKS_CROWD_BASE_CONFIDENCE=0.3
PERKS_VERIFIED_THRESHOLD=0.8
```

## 4.4 補充常數表（未入常數檔之規格數值）

| 項目 | 數值 | 適用 |
|---|---|---|
| 現場即泊 GPS 水平精度 | ≤ 100m（GPS 不可用或 ＞100m 視作失敗） | 現場即泊、B-1 現場講法 |
| Cloud Armor 限流 | 約 60 次／60 秒／IP，可調（Terraform 變數 cloud_armor_rate_limit_per_ip_per_minute），超限回 429 | LB 入口 |
| recommend-and-route 目標延遲 | p95 ＜ 500ms | W-B.1 |
| API SLA 告警 | p95 連續 5 分鐘 ＞ 500ms 或 5xx 錯誤率 ＞ 5% | Cloud Monitoring |
| Ingestion 告警 | Ingestion Job 連續 3 次執行失敗，或 manifest source_status = degraded 持續逾 10 分鐘 | Cloud Monitoring |
| 每日預算熔斷 | 每日 $5 美元預算警報＋Cloud Billing 預算告警（正常每日約 $1.3 美元，細項待試點量測） | 維運 |
| 深夜節流時段 | 01:00–06:00（香港時間），抓取間隔 INGEST_NIGHT_INTERVAL_SECONDS | Ingestion |
| 優惠仲裁排程 | 每日 04:00（job-perks-arbitration） | perks_worker |
| 水牌相片暫存 | GCS_TEMP_PHOTOS_BUCKET，7 天 TTL 自動銷毀 | trip-report 相片 |
| 打分：商場優惠 M_i | +15 分 | §9.2 |
| 打分：長期痛點 P_i | 一般 −20；慣性黑點 CHRONIC_BLOCKED_PENALTY_POINTS；無則 0；稀缺區豁免 | §9.2 |
| 打分：隨機擾動 R_i | 0 至 5 分 | §9.2 |
| 眾包來源群組 | 同一 IP、同一 carpark_id 5 分鐘內多個 session_id 只計 1 個來源；窗口 CROWD_REPORT_DECAY_MINUTES | crowd-report |
| 慣性黑點降級 | 連續 14 天零投訴 | W-B.9 |
| 短期時效事件時限 | 1 小時（只適用單次霸位、違泊等） | trip-report |
| 卡片視覺 | 底色 #121212，約 80% 不透明（例如 0xCC121212），背景模糊半徑約 12–16（Android 12+ setBackgroundBlurRadius；不支援即實色 #121212） | FloatingCardView |
| ETL 坐標 | HK1980 指紋：坐標介乎 800,000 至 870,000；邊界盒：緯度 [22.153, 22.563]、經度 [113.835, 114.441]，越界 INVALID_GEO | ingestion_worker |
| Firestore TTL 實際刪除延遲 | 到期後一般 24 小時內 | 讀取一律以 expire_at／created_at 過濾 |
| 試點量測期 | 1–2 週 | §15 |

# 5. 客戶端：起步入口、權限與 FGS

## 5.1 六種起步方法＋現場即泊

合法起步入口全部屬用戶前台互動；啟動前一律檢查本模式必要權限（§5.5）；入口一律不彈權限對話框。每次成功開始即建立新 session_id（§5.8）。

| 碼 | 入口 | 啟動路徑 | 麥克風類型（全語音模式） |
|---|---|---|---|
| A-1 | 車載藍牙點火 Heads-up 通知「🅿️ 需要搵車位就撳我」或動作按鈕「開始泊車助手（啟用語音）」 | ACTION_ACL_CONNECTED 只發通知 → PendingIntent → FgsBootstrapActivity | 點擊正文或按鈕一律 location\|microphone |
| A-2 | App 內「出發」按鈕 | Flutter → startBubbleService()（同一用戶手勢） | 未關本程熱詞 location\|microphone；已關則 location |
| A-3 | 純導航 Heads-up 通知（未駁藍牙、未開副駕，Maps 開始導航時） | PendingIntent → FgsBootstrapActivity | 一律 location\|microphone |
| B-1 | Hey Google／App Actions 透明跳板（路徑 1A），或熱態熱詞／單擊膠囊（路徑 1B） | shortcuts.xml → 透明跳板 Activity | 未關本程熱詞 location\|microphone；已關則 location |
| B-2 | Google Maps 分享 | ShareIntentReceiverActivity | 一律只開 location（入口不直接升級 microphone） |
| B-3 | App 起步頁選定目的地確認出發 | Flutter → startBubbleService() | 未關本程熱詞 location\|microphone；已關則 location |
| 現場即泊 | App 內以當前 GPS 作 destination；或 B-1 語句含現場講法 | 與「出發」同級 | 未關本程熱詞 location\|microphone；已關則 location |

播報＋手動／純手動模式（含未授 RECORD_AUDIO）一律只以 location 類型啟動 FGS。司機忽略通知則本程不開 FGS、無膠囊；後備為 App 內「出發」或 Hey Google 冷態穿透。

## 5.2 模式 A／模式 B

- 模式 A（全自動接盤，推薦）：必要權限＝模式 B 必要權限＋通知存取權（NotificationListenerService）。只過濾 package 為 `com.google.android.apps.maps` 之導航狀態；其餘所有應用通知於 Native 層直接 drop，絕不讀取或儲存。使用路徑 A-1／A-2／A-3。
- 模式 B（零隱私顧慮手動備案）：開程必要權限三項——ACCESS_FINE_LOCATION（使用期間）、POST_NOTIFICATIONS（Android 13+）、SYSTEM_ALERT_WINDOW；RECORD_AUDIO 只屬全語音模式必要權限；BLUETOOTH_CONNECT 為唯一可選權限。使用路徑 B-1／B-2／B-3。
- 現場即泊：司機已抵達目的地附近，以當前 GPS 作 destination，即時 JIT 推薦（等同啟動當下已 ≤ TRIGGER_DISTANCE_M），繪出膠囊並可口播首選；GPS 精度要求見 §4.4，失敗則不啟動 FGS、不推薦，TTS「暫時搵唔到你位置，請稍後再講一次」。

## 5.3 B-1 語音直達與入口優先聲明

入口優先聲明（決定版）：「B-1 路徑 1A：Hey Google／App Actions 冷態入口屬實驗性能力，須按指定 intent、語言及裝置驗證通過後方可啟用，不列入本版必要交付或免手操作保證。正文相關冷態流程均以驗證通過為前提。本限制不適用於 B-1 路徑 1B 已開程後的本機語音互動。」驗證結果記入測試清單 Phase B 第 12 項。

- 路徑 1A（冷態零接觸，螢幕無膠囊）：「Hey Google，用泊車助手去朗豪坊」經 App Actions（res/xml/shortcuts.xml；destination 參數可選，配置現場講法）穿透喚醒透明跳板 Activity → 權限檢查 → startForeground → 收到 FGS_STARTED 後繪出 P 膠囊。
- 路徑 1B（熱巡航微縮態，螢幕已有膠囊）：麥克風 FGS 已升級時以本機離線熱詞「泊車助手，去朗豪坊」或語音「出發」／「我要泊車」喚醒；未升級則單擊膠囊即時升級麥克風收音。原生粵語 STT 轉出文字後，本機字典樹查出坐標（TRIE_LOOKUP_BUDGET_MS）。

B-1 語句三支：

1. (a) 含目的地名稱 → 按 B-1 流程入軌，以該地名作 destination。
2. (b) 無地名但明確包含現場講法（「附近」「呢度」「而家位置」等）→ 現場即泊（當前 GPS 作 destination、即時 JIT、建立 session_id；權限檢查與麥克風類型同上）。
3. (c) 無地名亦無現場講法 → 目的地解析失敗：不啟動、不開 FGS、不推薦、不建立 session_id；TTS「聽唔到目的地，請再講一次」。

## 5.4 B-2 Google Maps 分享流

1. ShareIntentReceiverActivity（透明跳板）接收分享文字（通常為地點標題＋maps.app.goo.gl 短網址；只有掉針才含明文 lat/lng）；啟動 FGS 前先檢查必要權限，缺項即不啟動 FGS、finish() 退回 Google Maps 並發補授權通知。
2. 先以地點標題離線比對 LocalPoiTrie.kt（掉針則以 Regex 直接讀 lat/lng）；命中即啟動 FGS 並繪出 P 膠囊。
3. Trie 未命中：立即啟動 location FGS、繪出 AMBER_STEADY 膠囊，收到 FGS_STARTED 後 finish()（絕不阻塞退回 Google Maps）；短網址展開／雲端 Geocoding 於已運行之 FGS 作用域（或 WorkManager expedited work）內由原生 ApiClient.kt 執行（全程 Kotlin，毋須等待 Flutter），絕不掛在已銷毀之 Activity 作用域；不以固定 sleep 做主方案。
4. 背景解析沿用 ApiClient.kt 分級重試：成功即設定終點，由 AMBER_STEADY 更新膠囊並按距離觸發 JIT；網絡失敗 → GREY_BREATHING，口播一次「網絡唔順，遲啲再試」，司機拍膠囊即重試解析；可連線但無法解析（觸發條件 4）或結果含糊（觸發條件 3）→ ALERT_BREATHING，依共用規則口播「去邊度？請拍一拍膠囊答我」。

## 5.5 FGS 啟動邊界與握手

- 通知點擊路徑（決定版）：PendingIntent → 短命透明 Activity（FgsBootstrapActivity）→ 檢查必要權限 → 齊全即 startForegroundService／startForeground；缺項即 finish() 並發補授權通知。
- 握手：Activity 保持存在，直至 Service 回報 FGS_STARTED（startForeground 已成功；經 bound Service 或同進程回呼傳遞）先 finish()；等候上限 FGS_BOOTSTRAP_TIMEOUT_MS，逾時照樣 finish() 並記錄失敗原因。ShareIntentReceiverActivity 用同一握手。
- MIC_READY（AudioRecord 已就緒）為獨立訊號：麥克風初始化失敗時只停用語音輸入（聆聽與熱詞），本程以 location 類型繼續並照常顯示膠囊；TTS 可用則照常口播（按播報＋手動模式），TTS 不可用則按純手動模式；onMicSessionChanged 回報 MIC_FAILED。
- 語音能力每程靜默重查（VOICE_CAPABILITY_CHECK_TIMEOUT_MS）與握手並行，絕不阻塞 FGS_STARTED。
- 收到 FGS_STARTED 後：繪出 P 膠囊，可升級麥克風類型並啟用距離追蹤；啟動當下已 ≤ TRIGGER_DISTANCE_M 即時觸發 JIT。
- Google Maps 導航語音不受影響（Maps 自有服務）；Maps 發聲搶 AudioFocus 時暫停本程 AudioRecord，結束後恢復。

## 5.6 起步權限檢查、行程中撤銷與 OEM 殺程

- 起步權限檢查（決定版）：六種起步方法及現場即泊一律於 startForeground 前檢查本模式必要權限。App 外入口（A-1／A-3 經 FgsBootstrapActivity、B-1、B-2）缺任何一項即不啟動 FGS、即時 finish()；如已有通知權限，發補授權通知：「泊車副駕需要補授權：[缺項]。請泊好車後再處理。」App 內入口（A-2、B-3、現場即泊）缺項即不啟動 FGS，提示司機返回主畫面頂部權限狀態橫額。
- 必要權限按行程語音模式判定：缺 RECORD_AUDIO 時本程按 STT 不可用處理（TTS 可用＝播報＋手動，否則純手動）照常開程，以 location 類型啟動；橫額提示補授權以啟用語音輸入。
- 行程中撤銷（決定版，按模式）：任何本模式開程必要權限（精確位置、POST_NOTIFICATIONS、SYSTEM_ALERT_WINDOW；模式 A 另加通知存取權）被撤銷 → 即時停止 FGS 並結束本程（PERMISSION_REVOKED）；全語音模式下 RECORD_AUDIO 被撤銷 → 停止聆聽與熱詞、完全停止 AudioRecord、降級回 location，TTS 可用則按播報＋手動繼續本程；補授權通知盡力發出。系統因撤銷而終止 App 進程時，本程按既有規則放棄（abandon trip），不自動重啟。
- OEM 殺程（決定版）：行車途中 OEM／系統殺掉 location FGS，本程視為放棄（abandon trip）——不自動重啟 FGS、不恢復膠囊與熱詞；司機可經 App 內「出發」、App Shortcuts 或 Hey Google／App Actions 重新開程（新 session_id）。

## 5.7 雙模式權限矩陣、權限狀態橫額與新手教學

只設模式 A、模式 B 兩種權限組合；RECORD_AUDIO 另按行程語音模式判定；全程不申請背景定位；定位只於 App 可見或 location 類型前台服務運行期間（While-in-use）取得。

| 權限 | 用途 | 請求時機 | 必要性 | 未授權時 |
|---|---|---|---|---|
| 精確位置（ACCESS_FINE_LOCATION，只限使用期間；ACCESS_COARSE_LOCATION 隨同宣告） | JIT 算分、1km 預備區、150m 軟件距離追蹤與到場判定 | 頁面 5 授權步驟（系統對話框；選「精確位置」及「使用 App 時」）；每次開程前檢查 | 必要（模式 A／B） | 不開程；選「大概位置」視為未授權（橫額顯示「需要精確位置」）；App 外入口 finish() 並發補授權通知，App 內入口提示查看權限狀態橫額 |
| POST_NOTIFICATIONS（Android 13+） | 藍牙點火 Heads-up 通知、導航鎖定通知 | 頁面 5 授權步驟（Android 13+） | 必要（模式 A／B） | 不開程；經權限狀態橫額補授權（未有通知權限時無法發出補授權通知） |
| BLUETOOTH_CONNECT（Android 12+） | 監聽車載藍牙 ACL 連線 / 斷開 | 頁面 6 選擇模式 A 後配對車機藍牙時 | 可選（唯一可選權限） | 只影響自動點火 Heads-up：司機於 App 內點「出發」開程；熄匙以車充斷電或行程結束判定 |
| RECORD_AUDIO | 語音輸入（全語音模式）：本機熱詞與聆聽（地名、「詳細啲」等追問） | 頁面 5 授權步驟 | 全語音模式必要（模式 A／B；播報＋手動／純手動開程毋須） | 全語音模式不可用：本程按 STT 不可用處理（播報＋手動或純手動）照常開程，只以 location 類型啟動 FGS；經權限狀態橫額補授權以啟用語音輸入 |
| SYSTEM_ALERT_WINDOW（顯示在其他應用程式上層） | 懸浮 P 膠囊與追問卡片 | 頁面 5 授權步驟跳至系統設定（模式 A／B 皆同；按返回即回 App） | 必要（模式 A／B） | 不開程；權限狀態橫額「前往設定」跳至懸浮窗設定頁 |
| 通知存取權（NotificationListenerService，模式 A） | 只讀取 Google Maps 導航通知 | 頁面 6 點「前往授權模式 A」時 | 模式 A 必要 | 模式 A 不開程；權限狀態橫額「前往設定」跳至通知存取權設定頁；司機亦可於設定頁選用模式 B |
| FOREGROUND_SERVICE / FOREGROUND_SERVICE_LOCATION / FOREGROUND_SERVICE_MICROPHONE | location / microphone 類型前台服務 | 只需 Manifest 宣告（安裝時授予，無執行期彈窗）；須於 Play Console 填寫前台服務類型聲明 | 不適用 | 不適用 |

權限狀態橫額（決定版，permission_status_banner.dart）：

- 位置：主畫面頂部與頁面 5 授權步驟頂部各一個固定區域，只於缺少必要權限時顯示並列出缺項（選「大概位置」顯示「需要精確位置」）。
- 按鈕：「允許」（仍可彈出系統對話框）或「前往設定」（已被永久拒絕，跳至 App 權限設定頁）；懸浮窗與通知存取權固定顯示「前往設定」。
- 文案：「請泊好車後再處理」（補授權通知以同句結尾）；補授權一律經此橫額處理；系統權限對話框只於頁面 5 或橫額主動撳「允許」時彈出。

新手教學 6 頁 Story Cards（AppConfig.permissionTutorialPages）：

1. 頁面 1（破除疑慮）：只認 Google Maps，其餘通訊於 Native 記憶體第 1 毫秒直接丟棄，零儲存、零上傳。
2. 頁面 2（系統預防針）：預先警示 Android 官方紅色通用警告為免責常態，司機可隨時選用模式 B。
3. 頁面 3（麥克風本地收音說明）：麥克風只喺本機聽熱詞「泊車助手」；未命中熱詞前音訊只留本機記憶體滾動緩衝（唔落地、唔儲存、唔上傳）；知情同意與常駐通知顯示「正在聽」；講咗熱詞或單擊膠囊後先開始聆聽地名，講完自動停；麥克風只於 location FGS 已啟動且司機互動升級後先啟用、行程結束即釋放。
4. 頁面 4（語音優先示範動畫）：約 3 秒循環微動畫「口播首選 → 司機講『詳細啲』→ 彈出卡片並同步讀出」（Flutter 內建動畫，不新增動畫依賴）。
5. 頁面 5（授權步驟，出發前一次過完成）：先逐項講解位置、麥克風、通知、懸浮窗用途，提示位置選「精確位置」及「使用 App 時」（「只限這次」可用但每程要重新授權，並令模式 A 離 App 後難經通知一鍵開程；起步權限檢查兜底）；連續三個系統對話框：ACCESS_FINE_LOCATION → RECORD_AUDIO → POST_NOTIFICATIONS（Android 13+）；再跳 SYSTEM_ALERT_WINDOW 設定頁；頂部固定權限狀態橫額；同頁進行語音能力檢查（§6.1）並於支援但未安裝時提示下載語音包（建議連 Wi-Fi、出發前完成）。
6. 頁面 6（清晰透明決策）：右側主要操作「前往授權模式 A」（跳至通知存取權設定頁，返回後可選配對車機藍牙 BLUETOOTH_CONNECT）；左側次要操作「保持純淨模式 B」；設定頁常駐「新手教學與運作原理」入口。

## 5.8 Android Manifest（逐字）

```xml
<!-- 位置：只限使用期間 (While-in-use)；不宣告 ACCESS_BACKGROUND_LOCATION -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />

<!-- 模式 A／B 開程必要權限：POST_NOTIFICATIONS、SYSTEM_ALERT_WINDOW（連同上方精確位置）；RECORD_AUDIO 只屬全語音模式（語音輸入）必要權限；BLUETOOTH_CONNECT 為唯一可選權限 (見 PART 1 權限矩陣) -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />

<!-- Android 14+ 前台服務必須宣告類型及對應權限 (只需 Manifest 宣告，無執行期彈窗；須於 Play Console 申報) -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />

<service
    android:name=".FloatingBubbleService"
    android:exported="false"
    android:foregroundServiceType="location|microphone" />

<!-- 模式 A 必要權限：通知存取權 -->
<service
    android:name=".MapsNotificationListener"
    android:exported="true"
    android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
    <intent-filter>
        <action android:name="android.service.notification.NotificationListenerService" />
    </intent-filter>
</service>
```

- 最低系統版本（決定版）：minSdkVersion = 33（Android 13+），只屬安裝下限，並不保證裝置具備離線粵語 STT／TTS；語音能力一律以能力檢查判定；Android 14+ 麥克風前台服務 While-in-use 規則全數沿用。
- 動態類型：Manifest 宣告 location|microphone；FGS 平時只以 location 類型 startForeground；只於用戶觸發時刻以 startForeground(type = location|microphone) 升級；單次聆聽完結且未啟用熱詞即停止 AudioRecord，並以 location 類型重新 startForeground 降級釋放麥克風。
- Google Play Console：須填寫前台服務類型聲明（LOCATION／MICROPHONE）；詳細申報文案與示範影片規格另見獨立 Play Console 文件；常駐通知可見文案用「正在聽」。
- 風險備註：各品牌 OEM ROM 行為差異大，須於多部真機（不同品牌及 Android 版本）實測；實作前以 Android 官方文件核實前台服務類型、背景啟動豁免及 While-in-use 權限之最新規定。

## 5.9 session_id 與會話結束

- session_id 建立時機（決定版）：六種起步方法（A-1／A-2／A-3、B-1／B-2／B-3）任一開始，或現場即泊開始，即於客戶端（原生 Kotlin）建立本程 session_id；B-1 目的地解析失敗或現場即泊定位失敗則不建立。
- 其後所有 recommend／commit／release／sync／crowd-report／trip-report 皆帶此 id；行程結束銷毀 FGS 時結束會話；每次開程一律建立新 session_id，不設心跳、不重連同一 id。
- 會話結束（決定版）：行程以 PARKED_CONFIRMED 結束時 session 正常結束並保留完成行程紀錄（保留至完成當日完結，香港時間）；司機於今日行程一撳確認後先設 ratable = true 並開放評分。未完成之 session（OEM 殺 FGS、權限撤銷、目標範圍外熄匙）於閒置逾時後由後端刪除（ACTIVE_SESSION_IDLE_TIMEOUT_MINUTES／COMMITTED_SESSION_IDLE_TIMEOUT_HOURS，見 §10.2）；未完成行程不設評分／回報機制。

# 6. 語音

## 6.1 行程語音模式（決定版，三種互斥）

能力檢查（VoiceCapabilityChecker.kt）判定離線粵語 STT 及離線粵語 TTS 語音，以裝置端實際辨識／合成能力為準（TTS setLanguage 成功不足以作準），結果分「已安裝可用／支援但未安裝／不支援／未知」四類。首次使用於頁面 5 與權限一併檢查並提示下載；每程開程靜默重查（見 §3 真理表），開程時絕不自動彈出下載提示。

| 模式 | 條件 | 行為 | FGS 類型 |
|---|---|---|---|
| 全語音模式（FULL_VOICE） | STT 與 TTS 均可用，並已授 RECORD_AUDIO | 口播＋聆聽；熱詞只於熱詞能力可用且設定未關閉本程熱詞時啟用 | 按入口規則 location\|microphone |
| 播報＋手動模式（ANNOUNCE_MANUAL） | STT 不可用（或未授 RECORD_AUDIO）、TTS 可用 | 照常口播；不聆聽、不設熱詞 | 只 location |
| 純手動模式（MANUAL_ONLY） | TTS 不可用（不論 STT） | 不口播、不聆聽、不設熱詞 | 只 location |

手動降級介面例外（決定版）：只適用於 STT 不可用之播報＋手動／純手動模式，並於此情況下優先於「卡片只於語音追問時出現」及「全卡盲撳、不設按鈕行」——司機可拍膠囊顯示推薦卡片；卡片提供手動「下一個」／「確認」／「取消」按鈕；仍然不自動彈卡。凡原需「請拍一拍膠囊答我」之地名澄清，STT 不可用時一律不作此要求，地名輸入留待泊好車後於 App 處理。手動模式並非免手操作之替代方案，行車中以 Google Maps 既有導航為主。

## 6.2 喚醒詞、熱詞與麥克風前台服務邊界

- 熱詞：本機自研「泊車助手」／「泊車副駕」／「Hey 泊車」，100% 本機離線偵測；只於本程已由司機互動啟用麥克風前台服務後生效。未命中熱詞前音訊只於記憶體滾動緩衝，不錄製、不落地、不上傳。
- 熱態巡航：FGS 已啟動、膠囊於螢幕邊緣休眠時，單擊膠囊即可出聲。
- 類型動態升級：FGS 平時只以 location 類型運行；microphone 類型只於全語音模式使用；權限齊全且設定未關閉本程熱詞時，入口按 §5.1 以 location|microphone 啟動；FGS 已啟動後單擊 P 膠囊、Hey Google 透明跳板之同一用戶手勢亦可升級。設定已關閉本程熱詞時仍保留拍膠囊單次聆聽（講完自動停）。
- 背景啟動限制：麥克風類型前台服務不可在無用戶互動下由背景啟動；藍牙 ACL 自動觸發只發 Heads-up 通知，不開麥克風、不繪膠囊。
- 本程生效與釋放（trip-long hotword，決定版）：經通知／按鈕啟用語音後，本程即使 Google Maps 處於前台，本機離線熱詞仍持續有效直至行程結束；行程結束（熄匙／藍牙斷開銷毀 FGS）一併釋放。未啟用熱詞時，單擊膠囊之單次聆聽於本句完結即結束 STT 輸入、停止 AudioRecord、降級回 location 並釋放麥克風；已啟用本程熱詞時，每句完結只結束該次 STT 輸入，熱詞擷取照常持續。
- 耗電取捨：熱詞只限本程並隨行程結束釋放；設定提供「關閉本程熱詞」以節省電量（仍保留拍膠囊單次聆聽）。
- 知情同意與常駐通知：啟用語音／熱詞前之知情同意及 location|microphone FGS 常駐通知，正文使用「正在聽」。

## 6.3 單一 AudioRecord → STT 工程閘門（決定版，開發前驗證）

熱詞命中後同一 AudioRecord 連續錄音並把 PCM 交 STT（命中後不停錄、不重建，避免句首被吞），前提為引擎通過以下驗證：

1. 只用裝置端路徑（createOnDeviceSpeechRecognizer 或同等），不設雲端 STT 後備；單靠 EXTRA_PREFER_OFFLINE 不足以作準。
2. 驗收須證明結果來自注入之外部音訊，並非引擎自行開咪。
3. PCM 契約須寫明採樣率、聲道、編碼、管道／檔案描述符生命週期及格式轉換（例如 16 kHz／單聲道／PCM 16-bit，經 Android 13+ RecognizerIntent.EXTRA_AUDIO_SOURCE 或同等機制注入）。
4. 區分「結束本句 STT」與「停止 AudioRecord」：每句完結即結束 STT 輸入，本程熱詞開啟時熱詞擷取照常持續；來電、撤銷權限或行程結束時完全停止 AudioRecord。
5. 此為目標裝置／引擎組合之開發前驗證（測試清單 Phase A）；系統引擎未通過時改用可嵌入離線引擎（例如 SenseVoice／Paraformer／Whisper），或收窄語音支援範圍；不宣稱 Android 13+ 全面支援。

音訊路由：啟動收音時以 AudioManager.getDevices 判斷輸入路由——藍牙 SCO／BLE headset／車機外置 DSP 不掛 NoiseSuppressor／AcousticEchoCanceler；僅本機 Mic 且 isAvailable() 為真先掛（無硬件則優雅降級）；以 AudioDeviceCallback 監聽輸入設備變更；開程初期本機 Mic 建 AudioRecord 後若藍牙 SCO／HFP 就緒，必要時重建或重綁音訊流；來電／AudioFocus 被搶（含 Google Maps 導航發聲）暫停 AudioRecord，結束後恢復（AudioPriorityManager.kt）。

## 6.4 收音計時（決定版，起始值，真機調校）

- 常數：LISTEN_OPENING_WAIT_S、LISTEN_END_SILENCE_S、LISTEN_MAX_UTTERANCE_S、STT_RESULT_TIMEOUT_S（定義與數值見 §3 真理表及 §4.1）。
- 計時由本機語音控制層（VAD）擁有，不只依賴 Android 靜音 extras（部分引擎會忽略）。
- 司機體驗及介面文案：「講完自動停」。

## 6.5 指令碼與語言模板（決定版，三語介面預備；本版只上架粵語、不設語言設定介面）

- 狀態機只處理內部指令碼（FIND_PARKING、SHOW_DETAILS、NEXT_OPTION、CONFIRM_TARGET、CANCEL、SET_DESTINATION、ON_SITE 等）；STT 文字先經語言層對應為指令碼。
- 「好／好的／Yes」只綁定當前有效之上一條問題，於逾時、取消、終點／候選變更或對話中斷時即失效。
- 所有 spoken_cue、本機錯誤、權限提示、「拍膠囊」提示，以及車場名稱、距離、貨幣、收費格式均經語言模板輸出，本版只填 zh-HK。
- 語言規則見 §1 第 5 條（SPEECH_LOCALE_DEFAULT）。地標中英別名（如「朗豪坊」／「Langham Place」）共用同一 POI ID，不設三套地標庫。

## 6.6 STT 信心度與防自我辨識（決定版）

- STT_CONFIDENCE_THRESHOLD 只適用於引擎提供有效信心度之情況；低於門檻即單向口播「太嘈聽唔清，稍後再試」即刻閉嘴休眠。
- 信心度缺失或回傳 −1 時不當作 0，另行分支（只按指令碼匹配及當前問題處理）；不同引擎分數不可直接比較，門檻按目標引擎真機校準。
- 副駕 TTS 播放、Google Maps 語音、藍牙切換及來電中斷期間不作確認判定，尤其 TTS 播放期間出現之「好」不得視作確認。

## 6.7 標準句式（逐字）

- 單段式直達：「泊車助手，去朗豪坊」／「泊車助手，目的地海港城」→ 副駕：「收到，為你鎖定朗豪坊，搜尋緊周邊車位。」
- 雙段式：「泊車助手」→ 副駕：「喺度，想去邊度？」→ 司機：「又一城」→ 鎖定出發。
- 泊車追問：「泊車」／「搵位」→ 口播首選；「詳細啲」→ 顯示當前卡片並同步讀出詳情（收費、優惠、限高、警示標籤）；「有冇第二個」→ 口播並顯示下一個候選（無更多候選口播「暫時冇其他選擇」）；「好／去呢個」→ 確認導航；「唔使／取消」→ 收起並待命；「取消／唔好／等等」→ 口播期間隨時取消。
- 首選口播例：「前方首選朗豪坊停車場，一百八十米，剩十二個位，首小時三十二蚊。」
- 隱式回顯：「收到，為你鎖定【大區＋街道/地標特徵】[地標全稱]，搜尋緊周邊車位。」
- 地名反問：「邊一區？深水埗定旺角？」
- 行車未設終點：「出發緊？去邊度等我睇定車位？」
- 網絡：「網絡有啲慢，我再試吓」（主動要求逾時）；「網絡唔順，遲啲再試」（仍失敗，只口播一次）。
- B-1 失敗：「聽唔到目的地，請再講一次」；現場即泊定位失敗：「暫時搵唔到你位置，請稍後再講一次」。
- 補授權通知：「泊車副駕需要補授權：[缺項]。請泊好車後再處理。」
- 稀缺已滿：「該區僅有一場，目前已滿。要唔要幫你搵遠少少、大概三公里外嘅場？」
- 低水位例：「APM 只剩兩個位，隨時會滿，後備可以去 XX 停車場。」
- 眾包黃旗口播：「近期有司機回報疑似霸位，請留意現場」；單人回報卡片黃色標籤：「⚠️ 近期有司機回報疑似霸位」；慣性黑點卡片：「⚠️ 常有雪糕筒霸位」。
- 數據過期（室內場，15–25 分鐘）：「某場十五分鐘前剩 X 個位，數據未即時刷新，建議留意現場水牌」；咪錶過期／degraded：「附近可能有咪錶，資料暫時未更新」（UI：「咪錶資料暫時未更新」）。
- 通知文案：「🅿️ 需要搵車位就撳我」；動作按鈕：「開始泊車助手（啟用語音）」。

## 6.8 Google Maps 導航通知解析（模式 A，MapsNotificationListener.kt）

- 中英雙語指路詞 Regex 過濾（決定版）：排除中文「往／朝／靠左往／靠右往／出口」及英文「toward／towards／onto／exit／keep left／keep right／turn left／turn right／slight left／slight right／continue onto／then」等轉向與路牌雜訊（英文不分大小寫；中文涵蓋本版支援之繁體樣本），並配合 1km 預備區物理防火牆。
- 三級分流：高置信度（≥ NAV_CONFIDENCE_HIGH_THRESHOLD）→ 靜默鎖定；含糊候選 → 澄清（膠囊已顯示時以 ALERT_BREATHING 詢問；Pre-FGS 時沿用 Heads-up 通知詢問，不顯示琥珀膠囊）；只有轉向指示或無可用終點（＜ NAV_CONFIDENCE_LOW_THRESHOLD 丟棄）→ 忽略並保留現有終點。
- 解析未中一律不清除已鎖定終點；本程未有終點時只由「行車未設終點」路徑提示一次。
- Google Maps 介面語言與副駕語言互相獨立（Maps 設中文或英文均須解析）。

## 6.9 地名歧義仲裁

- 地名解析期零彈窗，卡片只留給推薦結果：本地 Trie Miss 即時穿透雲端 Geocoding；以隱式回顯格式語音確認；多數分享標題命中 Trie／全名即直接鎖定；僅短網址解析、店名或街道名含糊時先反問。
- 同名或含糊地名：純語音短句反問，膠囊同時轉 ALERT_BREATHING；麥克風 FGS 尚未升級時依共用規則句尾加「請拍一拍膠囊答我」，司機拍膠囊升級收音後單字回答即鎖定；STT 不可用時地名留待泊好車後輸入。
- 廣東話語音覆蓋：本地 STT 辨識到關鍵字（如「搵個錶位」、「想泊街錶」）時自動臨時覆蓋為 METERS_FOCUS（原生 SttService.kt 執行）。

# 7. 行車流程（原生 Kotlin）

## 7.1 軟件距離追蹤與分級採樣（DistanceTracker.kt）

- 只於 location FGS 運行期間以前台定位計距；不用系統 Geofencing API、不申請背景定位。
- 分級採樣：＞ TRIGGER_DISTANCE_M 用 LOC_PRIORITY_FAR／LOC_INTERVAL_FAR_MS；≤ TRIGGER_DISTANCE_M 且 ＞ APPROACH_RADIUS_M 用 LOC_PRIORITY_NEAR／LOC_INTERVAL_NEAR_MS；≤ APPROACH_RADIUS_M 用 LOC_PRIORITY_APPROACH／LOC_INTERVAL_APPROACH_MS。
- 高頻採樣持續至行程結束，或距離回退 ＞ HIGH_FREQ_EXIT_RADIUS_M。
- FGS 啟動當下已 ≤ TRIGGER_DISTANCE_M 即時觸發 JIT（不必等待再次踩入）。
- 實車耗電驗收目標：典型單程約額外 1–3% 電量（以真機錄得為準）。
- 寧願提早轉 APPROACH_MUTED 靜音，絕不於地庫死等霸屏干擾倒車。

## 7.2 預備區 JIT 與網絡分級重試（net/ApiClient.kt）

- 落客前不喧賓奪主：進入預備區不主動跳窗、不主動口播，只於背景靜默預取推薦，以 Status Pip 顯示空位鬆緊。
- 雲端維持 min_instance = 0；冷啟動延遲由客戶端分級重試吸收；絕不盲猜無空位車場。
- 背景自動查詢：查詢中 AMBER_STEADY；逾時靜默重試（API_MAX_ATTEMPTS、API_RETRY_INTERVAL_MS），膠囊 GREY_BREATHING，全程不口播、不用 ALERT_BREATHING；均失敗即停止自動重試，等待下一個查詢觸發或司機主動要求；成功轉 NEARBY_READY。
- 司機主動要求（語音「泊車」／「搵位」或拍開膠囊）：逾時即口播「網絡有啲慢，我再試吓」並 GREY_BREATHING，依同一公式重試；仍失敗口播「網絡唔順，遲啲再試」（只一次），維持 GREY_BREATHING 直至請求成功、App／FGS 結束或行程結束（可再手動重試）。
- HTTP 429（Cloud Armor 超限）：單獨處理、唔計入分級重試；等候 Retry-After（上限 RATE_LIMIT_MAX_WAIT_MS）或 RATE_LIMIT_RETRY_DELAY_MS 後只重試一次；仍失敗 → GREY_BREATHING，等司機拍膠囊重試（§10.1）。
- 重發安全：推薦算分為唯讀請求；鎖定寫入以 session_id 為文件鍵（重複寫入只得一份），任何請求重發皆不造成重複副作用。

## 7.3 語音優先口播與追問出卡

- API 回傳已排序 cards，每個候選附 spoken_cue；司機要求泊車後 TTS 只口播首選；畫面不自動彈卡、不自動輪播、不設倒數。
- 追問出卡一次一張：「詳細啲」彈出當前卡片並同步讀出詳情；「有冇第二個」口播並以 CARD_PAGE_TRANSITION_MS 平滑切換下一張（依排序；稀缺區最多 1–2 個）。拍卡屬可選（效果同「好／去呢個」）；任何車速均可追問及顯示卡片，不設車速分級。
- 未決待命（防擅改導航）：首選口播完畢（全語音模式且本程麥克風已啟用則按收音計時聆聽一次）司機無回應 → 膠囊退回 CRUISING／NEARBY_READY，不追加口播、不倒數；原 Google Maps 路線完全保持不變。
- 司機打斷保護：口播期間隨時支援語音取消與確認；手勢滑動卡片即刻停止口播、靜默待命。

## 7.4 車載卡片安全視野規範（FloatingCardView.kt）

- 單一卡片形態：不論車速同一版型；卡片高度 = min(OVERLAY_MAX_CARD_HEIGHT_DP, OVERLAY_MAX_SCREEN_COVERAGE × 螢幕高度)；Google Maps 保持主要視野，不遮擋主導航轉向區。
- 全卡盲撳：整張卡片方框即為點擊按鈕，不設按鈕行（Fitts's Law）；隨手一拍即鎖定前往閘口。STT 不可用時加設「下一個／確認／取消」手動按鈕（§6.1）。
- 視覺：見 §4.4「卡片視覺」（WindowManager.LayoutParams.setBackgroundBlurRadius，只模糊卡片視窗範圍）。
- 與卡片無關之車速邏輯：到場靜音與倒車雷達靜音（RADAR_MUTE_SPEED_KMH）、地庫斷訊降級（DEAD_RECKONING_SPEED_KMH）、出發判定（SILENCE_DRIVE_SPEED_KMH）。

## 7.5 到場靜音、泊妥確認與熄匙

- APPROACH_MUTED（決定版）：門檻見 §3 真理表；只靜音主動口播、避讓車載倒車雷達，行程繼續；司機拍膠囊仍可「轉場」或問「有冇第二個」；虛擬鎖不因 APPROACH_MUTED 釋放，APPROACH_MUTED 不呼叫 release。
- PARKED_CONFIRMED（決定版）：目標場範圍內（或地庫 GPS 斷訊狀態）偵測熄匙訊號（EngineShutdownReceiver.kt：車載藍牙 ACL 斷開或車充斷電）→ 先完成到場判定，於本機今日行程（trip/TodayTripStore.kt，SharedPreferences）寫入候選「可能已泊入 X」（session_id、車場名稱、時間）→ 在線即盡力送出 release（reason = PARKED_CONFIRMED），離線即放棄（鎖至多保留至 expire_at）→ TEARDOWN_FADE_MS 內無痕淡出並停止 FGS。
- 司機其後於今日行程一撳確認（confirmParkedCandidate）→ /v1/sync/outbox（DRIVER_CONFIRMED）→ ratable = true 並開放評分。確認時如離線，確認保留於本機並於網絡恢復自動送出；香港時間 00:00（AppConfig.todayTripsResetTimeHkt）與今日行程一併清除，之後一律不送。此為唯一本機保留項。
- 目標範圍外熄匙即結束本程，不寫候選。虛擬鎖只於 PARKED_CONFIRMED 或 VIRTUAL_LOCK_TIMEOUT_MINUTES 到期時釋放。
- 記憶體：以指定測試機 `adb shell dumpsys meminfo <package>` 量度行程中 PSS（FGS＋膠囊＋原生卡片），上限按實測設定並寫入驗收門檻。

## 7.6 行車未設終點主動喚起（Amber Pulse Alert）

- 只於 location FGS 已運行期間生效；觸發條件見 §3 真理表；膠囊切換 ALERT_BREATHING。
- 副駕主動開口一次「出發緊？去邊度等我睇定車位？」，提示音後按收音計時聆聽（只限全語音模式且本程麥克風 FGS 已啟用）。
- 全語音模式未啟用麥克風：只口播並維持 ALERT_BREATHING，司機單擊膠囊作答。播報＋手動：只口播一次；純手動：只顯示 ALERT_BREATHING；兩者終點留待泊好車後輸入。
- 司機於 LISTEN_OPENING_WAIT_S 內無回應即閉嘴，膠囊維持 ALERT_BREATHING 直至設定終點或行程結束，嚴禁二次打擾。

# 8. MethodChannel 合約

Channel Name：`com.hkparking.copilot/overlay`（Dart 包裝 lib/core/platform/overlay_channel.dart）；只於 App 畫面開啟時連接；行程期間全部車內邏輯於原生執行，Kotlin 為狀態機唯一擁有者。

Flutter → Android Native：

- `startBubbleService()`：由 App 內入口（A-2「出發」、B-3 起步頁、現場即泊）於用戶手勢內呼叫，原生層檢查必要權限齊全先啟動 location FGS 並繪出懸浮 P 膠囊；App 外入口（FgsBootstrapActivity／ShareIntentReceiverActivity）直接於原生層啟動；不得由冷態膠囊點擊呼叫。
- `getPermissionStatus()`：回傳本模式各必要權限及 BLUETOOTH_CONNECT 之授權狀態（已授權／可再彈對話框／已永久拒絕；位置區分精確／大概），供權限狀態橫額與起步檢查使用。
- `getVoiceCapability()`：回傳離線粵語 STT／TTS 能力檢查結果（四類）及據此判定之行程語音模式，供頁面 5 顯示並提示下載。
- `startVoiceCapture(enableHotword: Boolean)`：只於全語音模式生效；由 Flutter 介面點擊（如起步頁「出發」／收音鍵）於同一點擊事件內即時呼叫，原生層以 startForeground(type) 升級麥克風類型；enableHotword = true 時啟用本程本機離線熱詞。
- `stopVoiceCapture()`：結束收音，降級回 location 類型並釋放麥克風。
- `setPreferences(preference, hotwordEnabled)`：設定頁寫入三段偏好與本程熱詞開關（SharedPreferences），原生於下次請求讀取。
- `getTripState()`：讀取原生 TripStateMachine 現時狀態（只讀顯示）。
- `getTodayTrips()`：讀取原生 TodayTripStore 今日行程（含 PARKED_CONFIRMED 候選與待確認狀態）。
- `confirmParkedCandidate(sessionId)`：司機於今日行程一撳確認；原生 ApiClient.kt 送出 /v1/sync/outbox（DRIVER_CONFIRMED），離線即保留於本機並於網絡恢復自動送出（香港時間 00:00 清除）。
- `submitTripReport(payloadJson)`：今日行程 3 格反饋，經原生 ApiClient.kt 送出 /v1/feedback/trip-report（只限已確認行程）。

Android Native → Flutter（只讀事件，只於 App 畫面開啟時派發）：

- `onTripStateChanged(state, dataJson)`：膠囊／語音狀態變更（CRUISING、NEARBY_READY、EXPANDED、NAV_TO_GATE、GREY_STEADY 待命、GREY_BREATHING 網絡有問題、AMBER_STEADY 數據未齊、ALERT_BREATHING 需要司機回應終點、APPROACH_MUTED、PARKED_CONFIRMED）。
- `onTodayTripsChanged()`：今日行程紀錄更新（新候選、確認送出、香港時間 00:00 清除）。
- `onTripEnded(reason)`：本程結束（PARKED_CONFIRMED、ENGINE_SHUTDOWN〔目標範圍外熄匙〕、PERMISSION_REVOKED〔行程中必要權限被撤銷，已停止 FGS〕）。
- `onMicSessionChanged(isActive, reason)`：回報麥克風前台服務啟用／釋放（如 NOTIFICATION_TAP、BUBBLE_TAP、TRIP_END、MIC_FAILED〔MIC_READY 失敗，只停用語音輸入，TTS 可用則保留口播〕）。

原生內部（不經 MethodChannel）：距離目標設定、膠囊狀態更新、倒車雷達靜音、導航通知終點解析、距離事件、熄匙處理，以及單一 AudioRecord（熱詞兼短時地名收音）經 NoiseSuppressor／AcousticEchoCanceler 處理後之音訊交 SttService.kt（不限定特定 STT 引擎，惟須通過 §6.3 工程閘門）。

# 9. 推薦演算法（Cloud Run／recommend.py）

## 9.1 偏好分流

- INDOOR_ONLY：直接過濾並排除所有路面咪錶聚落。
- METERS_FOCUS：提升咪錶聚落空位及距離加權，室內場降為次選後備。
- SMART_HYBRID：排序首兩個候選固定為優質室內場，第三個候選為周邊最優咪錶聚落（首選以語音口播；其後候選於司機追問「有冇第二個」時逐一口播及出卡）。
- 咪錶聚合：METER_CLUSTER_RADIUS_METERS 內聚合成單一卡片；商業激戰區最低剩餘空位 METER_HOTSPOT_MIN_VACANCIES；住宅區 METER_RESIDENTIAL_MIN_VACANCIES。

## 9.2 核心打分公式

```text
S_i = W_dist * (1 - D_i / D_max) + W_vac * min((V_i - L_i) / 15, 1.0) + M_i - P_i - C_i + R_i + B_scarcity - S_stale
```

- W_dist＝W_DIST、W_vac＝W_VAC、D_max＝D_MAX_METERS（與 SEARCH_RADIUS_METERS／粗篩半徑一致）；飽和分母 15＝VACANCY_SCORE_DENOMINATOR。
- V_i − L_i：實時空位減去 In-flight 虛擬佔用數。L_i（virtual_locked_count）：先按距離、營業狀態與快照空位選出 Top 3–5 候選場（LOCK_COUNT_TOP_K_CANDIDATES），只為該批候選每次請求即時以 count() 聚合 virtual_locks 中 carpark_id = i 且 created_at 於 VIRTUAL_LOCK_TIMEOUT_MINUTES 窗口內之鎖文件；L_i 一律不入快取。查詢時 count() 只供排序與顯示，並非預約保證；防超賣於 commit 建鎖時處理。
- M_i：商場優惠加成（讀取官方優先離線雙軌仲裁之主表）。
- P_i：長期痛點與慣性黑點（數值見 §4.4；稀缺區豁免；無冷資料時 P_i = 0，Null-Safe）。
- C_i：即時眾包（窗口 CROWD_REPORT_DECAY_MINUTES 內有回報即扣 CROWD_REPORT_SINGLE_PENALTY；無則 0）。
- R_i：流量打散隨機擾動項（§4.4）。
- B_scarcity：稀缺區唯一性保底分 SCARCITY_BONUS_POINTS。
- S_stale：數據過期階梯式懲罰，按 data_age_seconds = server_now − source_snapshot_ts（政府數據實際更新時間之年齡）計算；processed_at 只記系統處理寫入時間，不參與新鮮度判定。數據年齡 STALE_TIER1_MINUTES–STALE_TIER2_MINUTES 扣 STALE_TIER1_PENALTY_POINTS（室內場口播誠實提示，咪錶聚落一律用保守句式）；＞ STALE_TIER2_MINUTES 扣 STALE_TIER2_PENALTY_POINTS。

## 9.3 排除規則（只來自官方數據）

- 不列入候選：新鮮官方空位 0 或 -1（設備維護）；咪錶狀態 O（已佔用）或 NU（暫停使用）。
- 過期（is_stale）官方 0 不作排除；V_i − L_i ≤ 0 只按公式計分，不作排除；眾包回報一律不作排除；稀缺區唯一選項照常口播，再詢問是否擴大範圍。

## 9.4 新鮮度與咪錶誠實口播（決定版）

- data_age_seconds ＞ SOURCE_STALE_THRESHOLD_SECONDS 即 is_stale = true。
- 咪錶卡 is_stale 或 source_status = degraded：vacancies 回 -1；UI 顯示「咪錶資料暫時未更新」；口播與畫面一律不讀咪錶實數，只用保守句式「附近可能有咪錶，資料暫時未更新」。

## 9.5 低水位提示（決定版）

- 私家車有效空位（只用 privateCar.vacancy − L_i，不計其他車種）≤ LOW_WATERMARK_VACANCIES 時（不設百分比規則），spoken_cue 以低水位句式口播並只點名一個後備。
- 數據過期或 degraded 時一律不讀數字；稀缺區唯一選項照常推薦並附同一警示；不另設扣分，空位分公式維持不變；偶有一兩個位誤差屬可接受。

## 9.6 街錶假空位：A+C 混合漸進防刷與慣性黑點

- 單人即時回報：扣 CROWD_REPORT_SINGLE_PENALTY，卡片打黃色標籤並口播提示。
- 多來源排序封頂（眾包永不排除）：窗口內先以來源 IP 做 5 分鐘群組（同一 IP、同一 carpark_id 5 分鐘內多個 session_id 只計 1 個來源），再以 distinct session_id 去重；累積 ≥ CROWD_REPORT_RANK_CAP_MIN_SOURCES 個獨立來源時，該選項排序封頂——不得排在首個未被回報之選項之前——並口播黃旗提示；扣分維持單人值；同一 session_id 重複回報不累加。
- 回報時效依真實事件時間戳計算，CROWD_REPORT_DECAY_MINUTES 後自然衰減失效。日後防造假路徑：Play Integrity（後期階段，只用於 crowd-report；屆時 Request 增設 integrity_token），仍不收集裝置 ID。
- Cloud Armor 限流與排序封頂規則分離，不參與「獨立來源」計數。
- 慣性霸位黑點（Chronic Blocked Hotspot）：同一咪錶聚落於滾動 CHRONIC_BLOCKED_WINDOW_DAYS 窗口內累積 ≥ CHRONIC_BLOCKED_MIN_REPORTS 位獨立司機反饋提及「雪糕筒／有人看場／長期佔用／不法霸位」等特徵 → 標記為慣性霸位黑點並寫入 carpark_pain_points，長期施加 CHRONIC_BLOCKED_PENALTY_POINTS 常態懲罰並於卡片註明「⚠️ 常有雪糕筒霸位」；連續 14 天零投訴方予降級。
- 事後補評時間戳解耦（Anti-Stale False Alarm）：1 小時時限只適用於短期時效事件（如單次霸位、違泊），事件已逾 1 小時即歸檔、不干預當前即時推薦算分；長期物理屬性痛點、7 天霸位黑點累計及優惠上報不受 1 小時限制，照常處理。

## 9.7 資源匱乏區（稀缺區）總量覆蓋加權

1. 觸發：SEARCH_RADIUS_METERS 粗篩後候選池總數 ＜ SCARCITY_POOL_MIN_COUNT。
2. 總量覆蓋：咪錶聚合門檻放寬至 ≥ 1 支即可推薦；候選最多 1–2 個；本地字典帶大區標籤，跨海與繞路比超標自動剔除。
3. 打分翻轉：豁免痛點懲罰，給予 B_scarcity。
4. 全區 0 位兩步處理：唯一場已滿時 recommend-and-route 回 SUCCESS，附稀缺已滿 spoken_cue（麥克風 FGS 尚未升級時句尾加「請拍一拍膠囊答我」）與 await_voice_reply = true；客戶端按收音計時聆聽（STT 不可用時不聆聽，按無回應處理）。
5. 司機答「好」→ 同一 session 重發 expand_scarcity = true，後端以 SCARCITY_EXPAND_MULTIPLIER 將搜尋半徑擴大（SEARCH_RADIUS_METERS × SCARCITY_EXPAND_MULTIPLIER）；「唔使／等等」＝排隊等候；超時無回應維持首選已滿狀態；畫面不自動彈卡、不設進度條倒數。

## 9.8 POI 真理源與三態響應合約（AI Agent／Skill 擴充）

- 推薦核心採 Composable／API-First 設計，支援 OpenAI Function Calling、MCP Server 與 Agentic Tools。
- assets/hk_poi_master.json：手機端編譯為原生 LocalPoiTrie.kt（行程中於 Kotlin 查詢）；Cloud Run 於啟動時駐留 Python 記憶體（In-Memory Dict，＜150KB）。外部 AI Agent 只提供 dest_name 時，後端直接在記憶體字典補齊經緯度，杜絕外部大模型坐標幻覺。
- SUCCESS：完全命中，回傳 cards 陣列，自帶 resolved_destination 與 spoken_cue 純粵語口播句。
- NEED_CLARIFICATION：存在嚴重歧義且缺乏坐標，回傳 clarification_prompt 與 options 陣列。
- NOT_FOUND：未識別地標，回傳 suggestions 相近地標推薦。

# 10. 後端 REST API 合約

## 10.1 通用規則

- 所有請求經 External Application Load Balancer 入口之 Cloud Armor 按來源 IP 限流（§4.4）；Cloud Run ingress 只接受 LB 流量。
- Header：`X-Internal-API-Key: <INTERNAL_API_KEY>`（佔位符）與 `Content-Type: application/json`；容器只驗證內部 API Key（main.py 中間件），容器內不設限流。
- 各欄型別以本節 Request／Schema／.env 欄位註解為準。

錯誤碼與特殊回應：

| 端點 | 回應 | 條件 | 客戶端處理 |
|---|---|---|---|
| 全部 | 429 | Cloud Armor 超限 | 唔即時重試、唔計入分級重試：回應帶 Retry-After（秒）即等候該秒數（上限 RATE_LIMIT_MAX_WAIT_MS），否則等候 RATE_LIMIT_RETRY_DELAY_MS，之後只重試一次；仍失敗（再 429、逾時或其他錯誤）→ GREY_BREATHING，同網絡失敗分支，司機拍膠囊重試；口播沿用 GREY 分支 |
| /v1/session/commit | 409，status = "LOCK_FULL"，附誠實 spoken_cue | 同一 transaction 內有效剩餘 V_i − L_i ≤ 0（V_i = -1 未知時照常寫入） | 口播 spoken_cue；司機可講「有冇第二個」另選 |
| /v1/feedback/trip-report | 404 | 未知 session_id | — |
| /v1/feedback/trip-report | 409 | session 存在但未經司機確認（ratable = false，包括 PARKED_CONFIRMED 候選未確認、APPROACH_MUTED 或未到場） | 靜默處理、不口播 |
| recommend／commit／release／sync／crowd-report | 200（不回錯誤） | 未知 session_id | 伺服器視作新行程並建立記錄 |
| /v1/recommend-and-route | 200 SUCCESS＋await_voice_reply = true | 稀缺區唯一場已滿 | 按 §9.7 聆聽 |

## 10.2 會話生命週期

- 客戶端（原生 Kotlin）每次開程建立新 session_id，不設心跳、不重連同一 id；每個帶 session_id 之請求刷新 active_sessions.last_seen_at 及 expire_at。
- recommend／commit／release／sync／crowd-report 收到未知 session_id 即視為新行程並建立記錄（不回錯誤）；release 收到未知 session_id 時建立 status = PARKED_CONFIRMED、ratable = false 之記錄（待司機確認）；sync/outbox（DRIVER_CONFIRMED）收到未知 session_id 時建立 status = PARKED_CONFIRMED、ratable = true 之記錄；兩者均保留至完成當日完結（香港時間）。
- 未完成之 session 於閒置逾時後刪除其內容（active_sessions 文件）：commit 前閒置 ACTIVE_SESSION_IDLE_TIMEOUT_MINUTES；commit 後（status = COMMITTED）閒置 COMMITTED_SESSION_IDLE_TIMEOUT_HOURS；完成行程紀錄按 COMPLETED_TRIP_RETENTION_POLICY 保留至完成當日 HKT 午夜。
- 未完成或未確認行程不設評分／回報機制；virtual_locks/{session_id} 只於 PARKED_CONFIRMED（release）或 created_at 窗口到期時釋放（APPROACH_MUTED 不釋放），並沿用 TTL 清理。

## 10.3 POST /v1/recommend-and-route（JIT 算分推薦核心）

Request Body：

```json
{
  "session_id": "sess_8f9a2b",
  "origin_lat": 22.3152194,
  "origin_lng": 114.1685231,
  "dest_lat": 22.3193012,
  "dest_lng": 114.1694123,
  "dest_name": "朗豪坊",
  "preference": "SMART_HYBRID",
  "expand_scarcity": false,
  "speech_locale": "zh-HK"
}
```

Response（200 OK）：

```json
{
  "status": "SUCCESS",
  "speech_locale": "zh-HK",
  "source_status": "ok",
  "source_snapshot_ts": "2026-10-18T14:20:00+08:00",
  "processed_at": "2026-10-18T14:20:41+08:00",
  "server_now": "2026-10-18T14:21:30+08:00",
  "data_age_seconds": 90,
  "cards": [
    {
      "carpark_id": "CP_LANGHAM",
      "name": "朗豪坊商場停車場",
      "type": "INDOOR",
      "vacancies": 12,
      "distance_meters": 180,
      "fee_summary": "首小時 $32 / 消費滿 $200 免 1 小時",
      "spoken_cue": "前方首選朗豪坊停車場，一百八十米，剩十二個位，首小時三十二蚊。",
      "is_stale": false
    }
  ]
}
```

- 欄位型別：origin_lat／origin_lng／dest_lat／dest_lng 為 float64／Double；vacancies 為 int（-1＝未知／源斷線之 sentinel，禁止 null）；distance_meters 為 int（公尺，四捨五入後）。
- expand_scarcity：bool，可選，預設 false；false 或省略時維持 SEARCH_RADIUS_METERS 粗篩。
- speech_locale：string，可選，預設 "zh-HK"；Response 回傳實際使用之 speech_locale；不支援之值一律明確回退 zh-HK，並以 speech_locale_fallback = true（bool）註明；所有 spoken_cue 按該語言模板輸出（本版只有 zh-HK）。
- await_voice_reply：bool；稀缺已滿時為 true（Response 仍為 SUCCESS）。
- 新鮮度欄位：server_now／source_snapshot_ts／processed_at 為 ISO 8601（含 +08:00）字串；source_snapshot_ts＝政府數據實際更新時間，processed_at＝系統處理寫入時間，兩者分開記錄；data_age_seconds 為 int（= server_now − source_snapshot_ts，伺服器端計算）；source_status 為 "ok"｜"degraded"；頂層欄位取本次回應所用快照中最舊／最差者；cards[].is_stale 為 bool（該卡所用快照 data_age_seconds ＞ SOURCE_STALE_THRESHOLD_SECONDS 即 true）。
- 邏輯（唯讀，可安全重發）：記憶體 POI 作粗篩及偏好過濾 → 讀 manifest.json 與對應區域快照（咪錶／室內場；按 generation 記憶體快取 SNAPSHOT_MEMORY_CACHE_TTL_SECONDS）→ 優惠、痛點經 IN_MEMORY_CACHE_TTL_SECONDS 記憶體快取 → 選出 Top K 候選 → Firestore AsyncClient + asyncio.Semaphore（限制併發）+ asyncio.gather() 每次請求即時 count() 取得 L_i（每次 count() 最少計 1 次讀取，每請求約 5 次讀取）→ 打分排序 → 輸出 cards 與 spoken_cue。
- 「不入快取」只適用於虛擬鎖計數：L_i、最終分數與 cards 每次請求即時計算；GCS 快照與 manifest.json 則必須按 generation 快取。
- 兩部車可同時讀到剩 1 位：防超賣由 commit 負責。
- 改目的地與鎖：Request 之目的地（dest_lat／dest_lng／dest_name）與 active_sessions 所記導航目標不一致時，於算分前刪除 virtual_locks/{session_id}；同目的地重查保留鎖；commit 改選覆蓋鎖之行為不變。
- 目標 p95 見 §4.4；cards 為已排序候選，客戶端只口播首選，追問時先逐一出卡。

## 10.4 POST /v1/session/commit（虛擬鎖定）

```json
{"session_id": "sess_8f9a2b", "target_carpark_id": "CP_LANGHAM"}
```

- 以 session_id 為文件鍵寫入 virtual_locks/{session_id}（carpark_id、created_at、expire_at = created_at + VIRTUAL_LOCK_TIMEOUT_MINUTES），更新 active_sessions 狀態為 COMMITTED 並刷新 last_seen_at／expire_at。
- 建鎖防超賣（決定版；預設＝Firestore transaction 讀後寫）：同一 transaction 內讀取 virtual_locks/{session_id} 現有鎖、目標場 created_at 窗口內之有效鎖數（不計本 session 自身之鎖）與最新快照空位 V_i；V_i − L_i ＞ 0（或 V_i = -1 未知）先寫入，否則即時失敗回 409 LOCK_FULL。
- 容許之替代方案：固定槽位鎖文件 virtual_locks/{carpark_id}_slot_{n}（create-if-absent，已佔即失敗）；須另設 session→slot 對照，故只列為容許選項，預設以 transaction 實作。
- 重發安全：同一 session 重複 commit 同一場只得一份鎖；改選其他車場即於 transaction 內覆蓋。虛擬鎖並非預約保證。

## 10.5 POST /v1/session/release（泊妥確認釋放）

```json
{"session_id": "sess_8f9a2b", "target_carpark_id": "CP_LANGHAM", "reason": "PARKED_CONFIRMED"}
```

- reason 只接受 "PARKED_CONFIRMED"（客戶端於目標停車場範圍內或地庫 GPS 斷訊狀態偵測熄匙訊號時送出；APPROACH_MUTED 不呼叫 release）。
- 直接刪除 virtual_locks/{session_id}（文件不存在之刪除為 no-op，重試安全，無需 Transaction），更新狀態為 PARKED_CONFIRMED（保留完成行程紀錄，ratable = false 待司機確認）。
- 客戶端離線即放棄本次 release，鎖由 VIRTUAL_LOCK_TIMEOUT_MINUTES 窗口自然失效。

## 10.6 POST /v1/sync/outbox（司機確認泊妥）

```json
{"session_id": "sess_8f9a2b", "event_type": "DRIVER_CONFIRMED", "timestamp": 1791823900}
```

- 司機於今日行程一撳確認候選「可能已泊入 X」後呼叫：直接覆蓋 active_sessions 狀態為 PARKED_CONFIRMED 並設 ratable = true，同時刪除 virtual_locks/{session_id}（不存在即 no-op），重發天然等冪。
- 客戶端確認時如離線：確認保留於本機 SharedPreferences，網絡恢復即自動送出；香港時間 00:00 與今日行程一併清除，之後一律不送。客戶端與伺服器均不設通用離線佇列。

## 10.7 POST /v1/feedback/crowd-report（司機眾包回報）

```json
{"session_id": "sess_8f9a2b", "carpark_id": "MTR_MK_01", "report_type": "BLOCKED_OCCUPIED", "client_timestamp": 1791823900}
```

- session_id 必填；規則見 §9.6（單人扣分加黃標、IP 5 分鐘群組＋distinct session_id 去重、≥2 來源排序封頂＋黃旗、永不排除、同一 session_id 重複回報不累加）。
- 防濫用：入口 Cloud Armor 按來源 IP 限流；日後 Play Integrity 只用於 crowd-report。

## 10.8 POST /v1/feedback/trip-report（行程反饋與優惠上報）

```json
{"carpark_id": "CP_LANGHAM", "rating_tag": "GOOD", "perk_status_tag": "VALID", "perk_notes": "滿$200免1小時", "session_id": "sess_8f9a2b", "trip_timestamp": 1791823900, "photo_url": "gs://hkparking-temp-perk-photos/photo123.jpg"}
```

- 接受經司機確認泊妥（status = PARKED_CONFIRMED 且 ratable = true）之行程，於完成當日完結（香港時間）前均可提交；未知 session_id 回 404；未經司機確認回 409（客戶端靜默處理、不口播）。
- 依 session_id 讀取保留之完成行程紀錄，以 trip_timestamp 與該紀錄之真實泊妥時間戳比對；1 小時時限規則見 §9.6。
- 後端不設今日行程查詢端點；今日行程清單只讀本機 TodayTripStore。

# 11. Firestore 配置

Firestore 只存放會話、虛擬鎖、優惠與痛點；咪錶與室內場空位存於 Cloud Storage 區域快照（manifest.json 為唯一版本指標）；Firestore 不設空位文件、版本指標文件或即時推送監聽。Firestore 寫入只來自 active_sessions、virtual_locks 與司機反饋（crowd-report／trip-report 及仲裁回寫 carpark_pain_points／carpark_perks_master），與政府數據抓取輪數無關。

| Collection | 文件鍵 | 欄位／內容 |
|---|---|---|
| active_sessions | {session_id} | 導航目標 dest_lat／dest_lng／dest_name（供 recommend 比對）、status（COMMITTED／PARKED_CONFIRMED 等）、ratable（只於司機確認後為 true）、last_seen_at、expire_at（commit 前＝last_seen_at + ACTIVE_SESSION_IDLE_TIMEOUT_MINUTES；commit 後＝last_seen_at + COMMITTED_SESSION_IDLE_TIMEOUT_HOURS；PARKED_CONFIRMED＝完成當日香港時間午夜）、完成行程紀錄 |
| virtual_locks | {session_id} | carpark_id、created_at、expire_at（= created_at + VIRTUAL_LOCK_TIMEOUT_MINUTES）；virtual_locked_count = 該場 created_at 於窗口內之鎖文件 count() 聚合 |
| carpark_perks_master | {carpark_id} | 官方優先之商場優惠主表（含計票欄位） |
| carpark_pain_points | {carpark_id} | 排隊痛苦指數、限高標籤、慣性黑點標籤 |

- TTL Policies：啟用 active_sessions.expire_at 與 virtual_locks.expire_at；TTL 只負責清理，讀取一律以 expire_at 過濾，count() 一律以 created_at 窗口過濾；無需排程器。
- 複合索引（firestore.indexes.json）：virtual_locks: carpark_id ASC + created_at ASC（供 count() 聚合與 commit transaction 讀取）。
- 陣列索引：Firestore 不存放整份讀取之陣列（vacant_ids／unavailable_ids 只存於 GCS 快照）；如有整份讀取之陣列欄位，須以單欄位索引豁免停用陣列索引；GCS 快照採用 bitset／壓縮編碼前須先評估解碼與除錯成本。
- 安全規則（firestore.rules）：

```text
allow read, write: if false;
```

# 12. 數據管道：DATA.GOV.HK ETL 與 GCS 區域快照

## 12.1 Ingestion Job 執行模型

- Cloud Run Job（task_count = 1、parallelism = 1、task_timeout 小於排程間隔），由 Cloud Scheduler 經 Cloud Run Admin API 觸發；每輪單一執行實例。
- 抓取節奏：車位 INGEST_CARPARK_INTERVAL_SECONDS、咪錶 INGEST_METER_INTERVAL_SECONDS；深夜（§4.4）節流至 INGEST_NIGHT_INTERVAL_SECONDS；試點前以預量測結果定案。
- 記憶體只作本輪暫存；上一輪 hash／ETag、checkpoint manifest.json 與歷史緩衝檔一律存於 GCS_STATE_BUCKET。
- 上線前預量測 occupancystatus.csv：KE 區每 2 分鐘狀態變化之咪錶比例、來源實際更新頻率與檔案大小，用以決定抓取間隔與網格大小。

## 12.2 處理步驟

1. 靜態主檔解耦：咪錶靜態檔 parkingspaces.csv 每日下載一次，預計算區域／網格／車種後寫入 GCS meters_static_vYYYYMMDD.json；每輪只更新動態部分（室內場 carpark_id、vacancies INT〔來源 null→-1 後先寫入，禁止 null／缺欄〕；咪錶 vacant_ids／unavailable_ids），絕不覆蓋實體座標；時間戳只隨真實數據更新，不設定時刷新時間戳之心跳機制。
2. 條件下載與退避：以上一輪 ETag／Last-Modified（存於 manifest.json）發出 If-None-Match／If-Modified-Since；回 304 或內容 hash 相同即刻退出本輪（跳過清洗與 hash 計算，不寫入任何物件）；來源失敗以指數退避重試（SOURCE_BACKOFF_BASE_SECONDS、最多 SOURCE_BACKOFF_MAX_ATTEMPTS 次），仍失敗則保留上一份快照並於 manifest 記錄連續失敗次數。
3. 坐標轉換：坐標落入 HK1980 指紋範圍（§4.4）即以 pyproj `Transformer.from_crs("EPSG:2326", "EPSG:4326")` 轉換；香港邊界盒驗證，越界標記 INVALID_GEO 拒絕入庫。
4. 動態空位洗滌白名單（Pydantic 模型）：-1 或 "N/A" 標記為 UNAVAILABLE（設備維護），打分直接排除；咪錶狀態 O／NU 同屬官方排除；過期官方 0 不作排除；空位數大於總泊位數判定為數據溢出，保留上一份正常快照。
5. 咪錶完整性檢查（每輪「只存空置 ID」之前，四項全過先寫快照）：(1) 表頭／欄位齊全且可解析，並處理檔首日期行；(2) 來源行數 ≥ 靜態咪錶總數 × INTEGRITY_MIN_ROW_RATIO；(3) 已啟用區域之咪錶 ID 出現率 ≥ INTEGRITY_MIN_REGION_ID_RATIO；(4) 來源時間戳不早於上一輪。任何一項失敗：保留上一份快照，於 manifest 標記 source_status: degraded 並記錄 processed_at；來源未列出之咪錶一律計作 unavailable（未知），絕不當作已佔用。
6. 寫入範圍：ENABLED_REGIONS。

## 12.3 GCS 狀態快照寫入契約（gs://hkparking-state-snapshots）

| 物件 | 內容 |
|---|---|
| manifest.json | 唯一版本指標：各區快照 generation、source_snapshot_ts、processed_at、source_status、ETag／Last-Modified、上輪 hash、連續失敗次數 |
| 咪錶區域快照（每區一份） | vacant_ids、unavailable_ids、source_snapshot_ts、processed_at |
| 室內場區域快照（每區一份） | carpark_id、vacancies（INT；-1＝未知）、source_snapshot_ts、processed_at |
| meters_static_vYYYYMMDD.json | 區域／網格／車種預計算（每日） |
| history/grid_occupancy/dt=YYYYMMDD/hour=HH.ndjson | 每網格歷史記錄（每小時） |

- 寫入次序：先以 ifGenerationMatch = 0 建立新快照物件（重試等冪）→ 再以 ifGenerationMatch = manifest 現行 generation 更新 manifest.json（指向新快照 generation，並記 source_snapshot_ts、processed_at、source_status）；前提條件失敗即表示另一執行已寫入，本輪放棄並結束。
- API 讀取 manifest 後按 generation 讀快照並記憶體快取（SNAPSHOT_MEMORY_CACHE_TTL_SECONDS）。
- 過期快照物件按生命週期規則清理（保留日數按試點量測設定）。
- 靜態／慢變數據（場名、商場優惠主表、充電桩標籤等）另設獨立排程，與空位抓取解耦。

# 13. 冷資料 BigQuery 時序分區表

- ⚠️ 建表時必須設定按日期分區（PARTITION BY snapshot_date，香港日期）並設分區到期 BQ_PARTITION_EXPIRATION_DAYS（time_partitioning type = DAY，field = snapshot_date）；可加 CLUSTER BY region, grid_id。
- 記錄內容：只記每網格聚合 vacant_count、unavailable_count、valid_ratio（不存 18,000 支咪錶全量狀態；室內場以 carpark_id 作網格）。
- 記錄間隔：高峰每 5–15 分鐘（HISTORY_PEAK_INTERVAL_MINUTES）、離峰每 30–60 分鐘（HISTORY_OFFPEAK_INTERVAL_MINUTES）一筆；另記狀態變化事件，近零空位或來源 degraded 時加拍一筆。
- 寫入：記錄先以 ifGenerationMatch 寫入 GCS 每小時 NDJSON；每日 BQ_DAILY_LOAD_TIME_HKT 以一個 Load Job 將前一日（香港日期）全部每小時 NDJSON 載入目的地分區 `grid_occupancy_history$YYYYMMDD`，write_disposition = WRITE_TRUNCATE；job_id = `load_{table}_{YYYYMMDD}_{attempt}`（如 load_grid_occupancy_history_20261018_1；重試 attempt + 1）；排程 job-history-daily-load；由 history_loader.py 執行。
- 去重與等冪：由分區覆寫（WRITE_TRUNCATE 同一日分區）保證，並非依賴 job_id；重跑任何一日只會以完整 NDJSON 覆寫該日分區。
- 查詢：直接查詢原生分區表，WHERE 指定 snapshot_date 分區控制掃描量；採按需（On-Demand）SQL。Scheduled Query 分析車位跌至 0 之時間斜率，推算排隊痛苦指數並回寫 carpark_pain_points。
- 成本留意：批次載入免費，但儲存、查詢掃描量與 GCS 暫存物件仍計費；每表每日 1 個 Load Job（重試另計）。

表 `hkparking_history.grid_occupancy_history` Schema：

| 欄位 | 型別 | 說明 |
|---|---|---|
| snapshot_date | DATE | 分區欄位（香港日期）；分區到期 BQ_PARTITION_EXPIRATION_DAYS |
| source_snapshot_ts | TIMESTAMP | 政府數據實際更新時間 |
| processed_at | TIMESTAMP | 系統處理寫入時間 |
| region | STRING | 如 KE |
| grid_id | STRING | 咪錶網格；室內場填 carpark_id |
| entity_type | STRING | METER_GRID｜CARPARK |
| vacant_count | INT64 | 禁止 NULL |
| unavailable_count | INT64 | 含來源未列出之未知咪錶；禁止 NULL |
| total_spaces | INT64 | — |
| valid_ratio | FLOAT64 | 有效數據比例 |
| source_status | STRING | ok｜degraded |
| record_type | STRING | PERIODIC｜STATE_CHANGE｜NEAR_ZERO｜DEGRADED |
| fetch_latency_ms | INT64 | — |

# 14. 商場優惠離線雙軌仲裁（perks_worker.py）

- 官方地產集團爬蟲定期維護主表（carpark_perks_master）為主；司機於今日行程填寫統一 3 格表單反饋為輔（評價 Chips、優惠 Chips、選填備註與選填相片）。
- Gemini（GEMINI_VISION_MODEL）只作離線分類草擬助手，產出審核草稿，絕不直接自動改寫主庫；信心度參數 PERKS_OFFICIAL_CONFIDENCE／PERKS_CROWD_BASE_CONFIDENCE／PERKS_VERIFIED_THRESHOLD。
- 分類：（一）短期時效事件（單次霸位、違泊）依該程真實時間戳比對，過期（＞1 小時）即自動忽略、不影響即時打分；（二）長期物理屬性（窄路、極暗）沉澱為痛點標籤；（三）高風險慣性霸位黑點按 §9.6 升級。
- 選填水牌相片存入 GCS_TEMP_PHOTOS_BUCKET（7 天 TTL）。排程見 §4.4。

# 15. IaC、監控與預算

- Terraform（W-F.1）：Cloud Run v2 Service（min_instance_count = 0、startup_cpu_boost = true、ingress = INGRESS_TRAFFIC_INTERNAL_LOAD_BALANCER）；Ingestion Cloud Run Job；External Application Load Balancer（Serverless NEG 指向 Cloud Run）＋Cloud Armor 安全策略（1 條 throttle 規則，enforce_on_key = IP）；Cloud Scheduler；BigQuery Dataset 與按日期分區表；Firestore TTL Policies；GCS 暫存相片桶（7 天生命週期）與狀態快照桶；Cloud Billing 預算告警；IAM。
- Cloud Scheduler：job-ingestion-peak（日間車位／咪錶）、job-ingestion-night（深夜）、job-meters-static-daily（每日 parkingspaces.csv）、job-history-daily-load（BQ_DAILY_LOAD_TIME_HKT）、job-perks-arbitration（§4.4）；靜態／慢變數據另表排程；虛擬鎖清理不設排程器（由 TTL 負責）。
- Cloud Monitoring 告警：API SLA 與 Ingestion 告警門檻見 §4.4。
- 每日預算熔斷見 §4.4；BigQuery 儲存與查詢、GCS 操作與 ALB 數據處理等細項成本待試點量測。
- 試點量測（1–2 週）：按每活躍用戶每日量度 Firestore 讀／寫／刪、BigQuery 儲存與查詢位元組、Cloud Run CPU／記憶體／執行時間、來源檔大小／失敗率／延遲，之後再調 TTL、抓取與歷史間隔及快取；實作前以 GCP 配額頁覆核。

# 16. 專案目錄樹（每檔一行職責；邏輯見對應章節）

```text
android/app/src/main/
├── AndroidManifest.xml # 權限與 FGS 類型宣告（§5.8）
├── res/xml/shortcuts.xml # App Actions 捷徑；destination 可選；現場講法（§5.3）
└── kotlin/com/hkparking/copilot/
    ├── FloatingBubbleService.kt # 行程期間全部車內邏輯之原生宿主：膠囊、FGS_STARTED／MIC_READY、熱詞＋AudioRecord（§5.5、§6）
    ├── FgsBootstrapActivity.kt # 短命透明 Activity：權限檢查 → startForeground → 等候 FGS_STARTED（§5.5）
    ├── DistanceTracker.kt # 軟件距離追蹤、分級採樣、到場判定（§7.1、§3）
    ├── EngineShutdownReceiver.kt # 熄匙訊號 → 到場判定 → 停止 FGS（§7.5）
    ├── MapsNotificationListener.kt # 模式 A 導航通知接盤，只發 Heads-up（§6.8）
    ├── ShareIntentReceiverActivity.kt # 模式 B Google Maps 分享透明跳板（§5.4）
    ├── TripStateMachine.kt # 膠囊／語音狀態機唯一擁有者（§3）
    ├── FloatingCardView.kt # 原生追問卡片（§7.4）
    ├── voice/SttService.kt # 裝置端粵語 STT（外部 PCM 注入）（§6.3）
    ├── voice/TtsService.kt # 粵語口播合成隊列（zh-HK 模板）（§6.5）
    ├── voice/VoiceCapabilityChecker.kt # 離線粵語 STT／TTS 能力檢查與行程語音模式（§6.1）
    ├── voice/CommandCodes.kt # 內部指令碼與語言模板（§6.5）
    ├── voice/AudioPriorityManager.kt # 倒車雷達避讓與 AudioFocus 暫停（§3、§6.3）
    ├── net/ApiClient.kt # Cloud Run REST、API Key、分級重試（§7.2、§10）
    ├── poi/LocalPoiTrie.kt # 本機雙語字典樹（含行政大區標籤）（§9.8）
    ├── trip/TodayTripStore.kt # 今日行程候選與待確認本機保留（§7.5）
    └── NativeConfig.kt # 行車常數單一事實來源（§4.1）
android/app/src/debug/ # 只限 debug 建置（Android 只將 src/debug 編入 debug 建置）
├── kotlin/com/hkparking/copilot/
│   ├── sim/ # 模擬殼：SimVoiceInput、SimLocationSource、FixtureApiTransport、SimEventReceiver、虛擬搖桿（§18）
│   └── di/Bindings.kt # object Bindings：綁 sim 實作（與 release 版 API 相同）
└── assets/mock/*.json # fixtures（AssetManager 路徑 mock/<name>.json）
android/app/src/release/
└── kotlin/com/hkparking/copilot/di/Bindings.kt # object Bindings：綁 SttVoiceInput、FusedLocationSource、HttpApiTransport
assets/
└── hk_poi_master.json # 端雲共用 POI 靜態真理源（§9.8）
lib/
├── main.dart # App 入口與全局服務初始化
├── core/
│   ├── constants/app_config.dart # Flutter App 畫面參數（§4.2）
│   ├── constants/app_colors.dart # 高對比車載調色盤
│   ├── preferences/preference_manager.dart # 三段偏好記憶（SharedPreferences）
│   └── platform/overlay_channel.dart # MethodChannel 包裝（§8）
└── features/
    ├── onboarding/ # 雙模式權限教學與授權步驟（§5.7）
    │   ├── permission_tutorial_dialog.dart # 6 頁 Story Cards
    │   ├── mode_selection_sheet.dart # 模式 A／B 抉擇與藍牙配對卡片
    │   └── permission_status_banner.dart # 權限狀態橫額
    ├── ignition/ # 「出發」／起步頁／現場即泊入口與設定
    └── trip_history/ # 今日行程專頁（讀原生 TodayTripStore；一撳確認）
        └── quick_feedback_bar.dart # 統一 3 格結構化反饋（submitTripReport）
server/
├── Dockerfile # Python 3.11-slim 映像檔（Scale-to-zero）
├── requirements.txt # fastapi, uvicorn, firebase-admin, google-cloud-storage, pyproj, google-cloud-bigquery, pydantic
├── main.py # FastAPI 入口與內部 API Key 驗證中間件
├── api/
│   ├── recommend.py # POST /v1/recommend-and-route（§9、§10.3）
│   ├── sync.py # POST /v1/sync/outbox（§10.6）
│   └── feedback.py # POST /v1/feedback/crowd-report 與 trip-report（§10.7–10.8）
├── workers/
│   ├── ingestion_worker.py # Cloud Run Job：抓取、洗滌、GCS 區域快照、manifest、歷史 NDJSON（§12）
│   ├── history_loader.py # 每日 BigQuery Load Job（§13）
│   └── perks_worker.py # 優惠雙軌仲裁與 Gemini 草稿助手（§14）
└── config/
    └── .env # 雲端環境變數（§4.3）
```

# 17. 關鍵設計決定（每項一行理由，勿「優化」走）

- 室內場／咪錶空位快照放 GCS 唔放 Firestore：KE 試點約 28 室內場 × 每日 720 輪 ≈ 20,160 次寫入，已超出 Firestore 每日 20,000 次免費寫入額度。
- manifest.json 為唯一版本指標並以 ifGenerationMatch 寫入：單一寫入者、重試等冪，前提條件失敗即放棄本輪。
- 人群回報唔一票否決、單人扣 CROWD_REPORT_SINGLE_PENALTY：單一來源扣分剛好中和 100m 極近咪錶之距離紅利（約 +46.7），把假位打沉至約 1.3km 偏遠場水準，周邊正常場即可穩健超越，極端全區皆滿時仍可作帶警示之後備。
- ≥2 來源只排序封頂、去重靠 session_id＋IP 5 分鐘群組：維持無帳號、無痕承諾（不收集裝置 ID）；香港流動網絡 CGNAT 令同一 IP 可屬多名司機。
- 飽和分母 VACANCY_SCORE_DENOMINATOR：大場剩 10 位亦易爆滿。
- 不申請背景定位、150m 用軟件距離追蹤：系統 Geofencing API 與背景持續定位於 Android 10+ 須 ACCESS_BACKGROUND_LOCATION。
- location FGS 只於司機互動後啟動、Pre-FGS 只發通知：Android 背景定位／While-in-use 限制之工程約束。
- FgsBootstrapActivity 等候 FGS_STARTED 先 finish()：Activity 喺 startForeground 完成前 finish() 會令 Android 14+ 起步入口唔穩定。
- Cloud Run min_instance = 0：零閒置雲端成本；冷啟動延遲由客戶端分級重試吸收。
- Cloud Armor 門檻刻意放寬：香港流動網絡普遍採用 CGNAT，多名司機共用同一公網 IP。
- Cloud Run ingress 只收 LB 流量：防止繞過 Cloud Armor 直連 run.app 網址。
- 內部 API Key 唔當安全邊界：打包於 APK 內可被反編譯取出。
- L_i 不入快取：任何實例之 commit／release 於下一次請求即反映。
- GCS 快照與 manifest 必須按 generation 快取：否則 GCS Class B 操作與延遲上升。
- count() 只計 Top K 候選：每次 count() 最少計 1 次讀取，控制每請求約 5 次讀取。
- commit 以 transaction 讀後寫防超賣：查詢時兩部車可同時讀到剩 1 位。
- 鎖文件以 session_id 為鍵：重發只得一份、改選即覆蓋、release 與改目的地均按 session_id 刪除；固定槽位須另設 session→slot 對照，故只屬容許選項。
- Firestore TTL 只負責清理、讀取以 expire_at／created_at 過濾：TTL 一般於到期後 24 小時內才實際刪除。
- BigQuery 必須按日期分區：未分區之表每次查詢均全表掃描、按需計費隨數據線性暴增，且建表後不能直接轉成分區表（須重建）。
- BQ 去重靠 WRITE_TRUNCATE 分區覆寫：重跑任何一日只會以完整 NDJSON 覆寫該日分區，不依賴 job_id。
- BigQuery 只記每網格聚合：不存 18,000 支咪錶全量狀態。
- 新鮮度按 source_snapshot_ts 計：反映政府數據實際更新時間之年齡；processed_at 只記系統處理時間。
- 來源未列出之咪錶計作 unavailable：絕不當作已佔用，避免誤判。
- 1 小時時限只限短期時效事件：杜絕事後補評之時空錯位誤殺。
- 寧早 APPROACH_MUTED：絕不於地庫死等霸屏干擾倒車。
- 單一 AudioRecord 命中熱詞後不停錄、不重建：避免句首被吞。
- 熱詞只限本程並可關閉：熱詞常駐持續佔用麥克風與 CPU，帶來額外耗電。
- 起步入口只檢查、不彈權限對話框：請於出發前完成授權，駕駛期間切勿操作手機（「請泊好車後再處理」）。
- 卡片高度上限 OVERLAY_MAX_CARD_HEIGHT_DP／OVERLAY_MAX_SCREEN_COVERAGE、全卡盲撳：Google Maps 保持主要視野；Fitts's Law 盲撳。
- 「好」只綁定當前有效問題、TTS 期間唔作確認：防自我辨識與誤確認。
- TTS setLanguage 成功不足以作準：能力檢查以裝置端實際辨識／合成能力判定。
- Phase A 開發前驗證：Phase A 通過只代表該指定裝置／引擎組合可行，並不代表所有 Android 13+ 手機均可用。
- 不設通用離線同步：MVP 精簡；release 離線放棄，接受虛擬鎖至多保留至 expire_at 或下次政府數據刷新前之短暫空位誤差。
- Gemini 只出草稿、絕不自動改寫主庫：官方優先雙軌仲裁。
- POI 由後端記憶體補齊經緯度：杜絕外部大模型坐標幻覺。

# 18. 測試摘要（細則見測試清單 Doc 1OaMcyeEU8m0HKuhhCLf2DCJX9iY0h1vu-ar7WGdd148／客戶端資料包 docs/testing/01_test_plan.md）

## 18.1 Phase A — 開發前語音鏈驗證（指定裝置／引擎組合）

- 目的：開發前確認目標裝置＋引擎組合可支援全語音模式；未通過即改用可嵌入離線引擎（例如 SenseVoice／Paraformer／Whisper），或收窄語音支援範圍。
- 範圍：A. 能力檢查（四類結果、不阻塞 FGS_STARTED）；B. 裝置端 STT（飛行模式、無雲端後備）；C. 外部 PCM 注入證明（引擎沒有自行開咪）；D. TTS 與防自我辨識；E. 收音計時觀察。
- Phase A 通過只代表該指定裝置／引擎組合可行，並不代表所有 Android 13+ 手機均可用。

## 18.2 Phase B — 跑通之後嘅產品整合測試（29 項，按一→六次序）

- 一、模式與權限（1–5）；二、語音互動（6–11）；三、入口與 Maps（12–16，第 12 項＝B-1 路徑 1A 實驗性驗證）；四、行程與到場（17–20）；五、推薦與資料（21–24）；六、OEM／真機必測（25–29）。
- 每項紀錄：裝置型號｜Android 版本（含安全性修補日期）｜語音服務｜TTS 引擎及粵語語音包版本｜行程語音模式｜結果｜備註。

## 18.3 模擬器／真機分工

- Android 模擬器：GPX 路線回放（車速、距離、GPS 斷訊）、網絡失敗、宿主機麥克風直通、TTS、Google Maps 分享 Intent、`adb emu power ac off` 模擬車充斷電；原生 sim/ 模擬殼（只限 debug，位於 `android/app/src/debug/`：虛擬搖桿、fixtures、注入事件；adb 注入 action `com.hkparking.copilot.SIM_EVENT`）重播場景。
- 只限真機：車載藍牙 ACL 斷開、OEM 背景殺程（Xiaomi／Samsung）、市區高樓 GPS 漂移、粵語 Hey Google（B-1 路徑 1A 實驗性驗證）、實際記憶體上限、離線粵語 STT／TTS 能力檢查、裝置端 STT 外部 PCM 注入、收音計時與防自我辨識。
- 延遲：FgsBootstrapActivity 至 FGS_STARTED 之啟動延遲與粵語 STT 延遲一律真機實測；本機字典樹查表以 TRIE_LOOKUP_BUDGET_MS 為預設門檻。

# 19. PART 3 路線圖任務表

全專案 4 個階段、19 步、44 項工程任務（以任務編號計；W-E.2 與 W-F.2 各拆 Part 1／Part 2，合共 46 條任務項），按「依賴優先、垂直切片」編排。每項驗收細節見實作版 PART 3；重要度 P0–P3 見工作重要度排行 Doc。狀態欄 [ ] 供逐項剔選。

## 19.1 Phase 1 — 垂直骨幹與端雲閉環（第 1–3 步）

| 任務 | 標題 | 步 | 狀態 |
|---|---|---|---|
| W-D.1 | 四大集合＋Seed 腳本 | 1 | [ ] |
| W-D.2 | 會話／鎖 TTL 政策 | 1 | [ ] |
| W-D.3 | 鎖計數複合索引 | 1 | [ ] |
| W-D.4 | 安全規則禁前端直連 | 1 | [ ] |
| W-C.1 | 政府數據抓取快照 | 2 | [ ] |
| W-C.2 | 髒數據洗滌與完整性 | 2 | [ ] |
| W-C.3 | HK1980 轉 WGS84 | 2 | [ ] |
| W-B.1 | recommend 算分核心 | 3 | [ ] |
| W-B.12 | speech_locale | 3 | [ ] |
| W-B.2 | commit 虛擬鎖 | 3 | [ ] |
| W-B.3 | release 釋放鎖 | 3 | [ ] |

## 19.2 Phase 1 — 垂直骨幹與端雲閉環（第 4–5 步）

| 任務 | 標題 | 步 | 狀態 |
|---|---|---|---|
| W-A.1 | 懸浮膠囊＋狀態機 | 4 | [ ] |
| W-A.2 | Maps 接盤與分享跳板 | 4 | [ ] |
| W-A.3 | 本機字典樹與常數檔 | 4 | [ ] |
| W-A.15 | session_id 生命週期 | 4 | [ ] |
| W-A.16 | FGS 啟動握手 | 4 | [ ] |
| W-A.4 | 軟件距離追蹤 | 5 | [ ] |
| W-A.5 | 在線即送 release | 5 | [ ] |
| W-A.17 | 到場雙態與今日確認 | 5 | [ ] |
| W-A.18 | 測試策略與量度 | 5 | [ ] |
| W-B.4 | sync/outbox 確認 | 5 | [ ] |

## 19.3 Phase 2 — 行車交互精細化與香港特色場景（第 6–10 步）

| 任務 | 標題 | 步 | 狀態 |
|---|---|---|---|
| W-A.6 | 偏好三段開關 | 6 | [ ] |
| W-A.7 | 語音優先口播出卡 | 7 | [ ] |
| W-A.8 | 倒車雷達避讓 | 8 | [ ] |
| W-A.9 | 熄匙無痕銷毀 | 8 | [ ] |
| W-A.10 | 無終點主動喚起 | 9 | [ ] |
| W-A.13 | 單一 AudioRecord | 9 | [ ] |
| W-A.14 | 麥克風 FGS 與權限 | 9 | [ ] |
| W-A.19 | 語音能力檢查 | 9 | [ ] |
| W-A.20 | STT 開發前驗證 | 9 | [ ] |
| W-A.21 | 指令碼與語言模板 | 9 | [ ] |
| W-A.12 | 網絡分級重試 | 10 | [ ] |

## 19.4 Phase 3 — 商業激戰區強化、安全防禦與眾包反饋（第 11–13 步）

| 任務 | 標題 | 步 | 狀態 |
|---|---|---|---|
| W-B.6 | LB＋Cloud Armor | 11 | [ ] |
| W-B.7 | A+C 混合防刷 | 12 | [ ] |
| W-B.8 | 稀缺區保底 | 12 | [ ] |
| W-B.9 | 慣性霸位黑點 | 12 | [ ] |
| W-B.11 | 低水位提示 | 12 | [ ] |
| W-A.11 | 今日行程與反饋 | 13 | [ ] |
| W-B.5 | crowd-report | 13 | [ ] |
| W-B.10 | trip-report 補評 | 13 | [ ] |

## 19.5 Phase 4 — 冷資料分析、離線仲裁與 IaC 自動維運（第 14–19 步）

| 任務 | 標題 | 步 | 狀態 |
|---|---|---|---|
| W-E.1 | 歷史 NDJSON→BQ | 14 | [ ] |
| W-E.2 (Part 1) | 排隊痛苦指數分析 | 15 | [ ] |
| W-E.2 (Part 2) | 優惠雙軌仲裁 | 16 | [ ] |
| W-F.1 | Terraform IaC | 17 | [ ] |
| W-F.2 (Part 1) | 監控與 SLA 告警 | 18 | [ ] |
| W-F.2 (Part 2) | 預算熔斷與節流驗收 | 19 | [ ] |
