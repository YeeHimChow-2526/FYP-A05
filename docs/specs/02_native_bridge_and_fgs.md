# 02 — Native Bridge／FGS 啟動握手／起步入口（客戶端）

來源：實作版 Doc **v2.9.1** PART 1 §一／§三、PART 2 §一／§二.2／§三、PART 3 W-A.1／2／4／9／13／14／16／17／19／20／21。Manifest 細節見 `docs/reference/android_manifest_notes.md`；行車常數見 `android/app/src/main/kotlin/com/hkparking/copilot/NativeConfig.kt`。

## 1. MethodChannel（只於 App 畫面開啟時連接）

- Channel：`com.hkparking.copilot/overlay`
- Dart 包裝：`lib/core/platform/overlay_channel.dart`
- 行程期間全部車內邏輯於原生執行，Kotlin 為狀態機唯一擁有者；Flutter 只發 App 內指令、讀取並顯示原生狀態。

### Flutter → Native

| Method | 行為 |
|--------|------|
| `startBubbleService()` | 由 App 內入口（A-2「出發」、B-3 起步頁、現場即泊）於用戶手勢內呼叫；原生檢查必要權限齊全先啟動 location FGS＋繪 P 膠囊。App 外入口（FgsBootstrapActivity／ShareIntentReceiverActivity）直接於原生層啟動；**唔**可由冷態膠囊點擊呼叫 |
| `getVoiceCapability()` | 離線粵語 STT／TTS 能力檢查結果（已安裝可用／支援但未安裝／不支援／未知）及據此判定之行程語音模式；供教學頁面 5 顯示並提示下載 |
| `getPermissionStatus()` | 各必要權限＋`BLUETOOTH_CONNECT` 狀態（已授權／可再彈／永久拒絕；位置分精確／大概） |
| `startVoiceCapture(enableHotword)` | 只於全語音模式生效；同一用戶手勢內升級 microphone；`enableHotword=true` 啟用本程熱詞 |
| `stopVoiceCapture()` | 降級 location、釋放咪 |
| `setPreferences(preference, hotwordEnabled)` | 設定頁寫入三段偏好與本程熱詞開關（SharedPreferences） |
| `getTripState()` | 讀取原生 `TripStateMachine` 現時狀態（只讀顯示） |
| `getTodayTrips()` | 讀取原生 `TodayTripStore`（含 PARKED_CONFIRMED 候選與待確認狀態） |
| `confirmParkedCandidate(sessionId)` | 司機一撳確認；原生 `ApiClient.kt` 送 `/v1/sync/outbox`（`DRIVER_CONFIRMED`）；離線即本機保留、網絡恢復自動送出、HKT 00:00 清除 |
| `submitTripReport(payloadJson)` | 3 格反饋，經原生 `ApiClient.kt` 送 `/v1/feedback/trip-report`（只限已確認行程） |

### Native → Flutter（只讀事件，只於 App 畫面開啟時派發）

| Event | 含義 |
|-------|------|
| `onTripStateChanged(state, dataJson)` | 膠囊／語音狀態（CRUISING、NEARBY_READY、EXPANDED、NAV_TO_GATE、GREY_＊、AMBER_STEADY、ALERT_BREATHING、APPROACH_MUTED、PARKED_CONFIRMED） |
| `onTodayTripsChanged()` | 今日行程更新（新候選、確認送出、HKT 00:00 清除） |
| `onTripEnded(reason)` | `PARKED_CONFIRMED`／`ENGINE_SHUTDOWN`（目標範圍外熄匙）／`PERMISSION_REVOKED` |
| `onMicSessionChanged(isActive, reason)` | mic FGS 啟用／釋放（含 `MIC_FAILED`＝MIC_READY 失敗，只停用語音輸入，TTS 可用則保留口播） |

原生內部（**不經** MethodChannel）：距離目標、膠囊狀態更新、倒車雷達靜音、導航通知終點解析、距離事件、熄匙處理、每程開程語音能力靜默重查，以及單一 `AudioRecord` 經條件 NS／AEC 把 PCM 交 `voice/SttService.kt`（裝置端 STT；見 `01` §5.3）。

## 2. FGS 啟動握手（W-A.16，決定版）

```
PendingIntent／App Actions／分享 → FgsBootstrapActivity（或 ShareIntentReceiverActivity）
  → 檢查必要權限（缺項：finish()＋補授權通知）
  → startForegroundService / startForeground(type)
  → Activity 保持存在，等候 Service 經 bound Service 或同進程回呼回報 FGS_STARTED
       ├─ 收到 FGS_STARTED → finish()；繪 P 膠囊
       └─ 逾時 NativeConfig.FGS_BOOTSTRAP_TIMEOUT_MS = 2000 → 照樣 finish() 並記錄失敗原因
  → 語音能力靜默重查（VOICE_CAPABILITY_CHECK_TIMEOUT_MS）與握手並行，絕不阻塞 FGS_STARTED；開程時不彈下載提示
  → MIC_READY（AudioRecord 就緒）為獨立訊號（只限全語音模式）
       └─ 麥克風初始化失敗 → 只停用語音輸入（聆聽與熱詞）：照常顯示膠囊；TTS 可用則照常口播（播報＋手動），否則純手動
```

- **不設固定 sleep**；啟動延遲一律真機實測，文件／程式唔寫固定數字。
- Google Maps 導航語音不受影響：Maps 發聲搶 AudioFocus 時暫停本 App 之 AudioRecord，結束後恢復。

## 3. 關鍵 Kotlin 元件

| 檔案 | 職責 |
|------|------|
| `FloatingBubbleService.kt` | 行程期間全部車內邏輯之宿主；`foregroundServiceType=location\|microphone`（平時 location，用戶手勢升級 mic）；WindowManager 膠囊（只於 FGS_STARTED 後）；回報 FGS_STARTED／MIC_READY；熱詞＋AudioRecord；AudioDeviceCallback；AudioFocus。記憶體以指定測試機 `dumpsys meminfo` 量度 PSS，上限按實測設定 |
| `TripStateMachine.kt` | 膠囊／語音狀態機**唯一擁有者** |
| `FloatingCardView.kt` | 原生追問卡（一次一張；全卡盲撳） |
| `FgsBootstrapActivity.kt` | 短命透明：通知／冷啟動 → 權限檢查 → `startForeground` → 等 FGS_STARTED；缺項 `finish()`＋補授權通知 |
| `ShareIntentReceiverActivity.kt` | Maps 分享透明跳板（B-2）；同一握手 |
| `MapsNotificationListener.kt` | 模式 A：只聽 `com.google.android.apps.maps`；Pre-FGS Heads-up |
| `DistanceTracker.kt` | FGS 前台軟件距離；分級採樣；APPROACH_MUTED 判定（W-A.4） |
| `EngineShutdownReceiver.kt` | 藍牙 ACL／車充；先完成到場判定（PARKED_CONFIRMED 寫候選）再 0.5s 停止 FGS；ACL 連線只發通知（W-A.9） |
| `voice/SttService.kt`・`voice/TtsService.kt`・`voice/AudioPriorityManager.kt` | 裝置端粵語 STT（外部 PCM 注入；延遲真機實測）／TTS／倒車雷達避讓與 AudioFocus |
| `voice/VoiceCapabilityChecker.kt` | 離線粵語 STT／TTS 能力檢查四類結果；判定三種互斥行程語音模式（W-A.19） |
| `voice/CommandCodes.kt` | 內部指令碼與語言模板（W-A.21；本版只填 zh-HK） |
| `net/ApiClient.kt` | Cloud Run REST；分級重試 |
| `poi/LocalPoiTrie.kt` | 本機字典樹由文字查出坐標 <150ms |
| `trip/TodayTripStore.kt` | 今日行程候選／待確認（SharedPreferences；HKT 00:00 清除） |

音訊（W-A.13）：單一 AudioRecord 兼熱詞＋地名聆聽（收音計時見 `01` §5.4，講完自動停）；命中後不停錄不重建，PCM 交裝置端 STT（開發前驗證閘門 W-A.20，見 `01` §5.3）；藍牙 SCO／BLE／車機 DSP **唔掛** NS／AEC；本機 Mic 且 `isAvailable()` 先掛。

## 4. 六種起步＋現場即泊

合法入口（全部用戶前台互動；`startForeground` **前**檢查必要權限；**入口不彈**權限對話框）：

| 代碼 | 路徑 |
|------|------|
| A-1 | 藍牙點火 Heads-up／「開始泊車助手（啟用語音）」→ `FgsBootstrapActivity` |
| A-2 | App 內「出發」（Flutter → `startBubbleService()`） |
| A-3 | 導航 Heads-up → `FgsBootstrapActivity` |
| B-1 | Hey Google／App Actions 透明跳板（或熱態熱詞／拍膠囊） |
| B-2 | Maps 分享 → `ShareIntentReceiverActivity` |
| B-3 | App 起步頁確認出發 |
| 現場即泊 | 開 App＋當前 GPS 作 destination；即時 JIT |

麥克風類型（全語音模式）：權限齊全且設定**未**關閉本程熱詞 → `location|microphone`；關閉本程熱詞則 B-1／A-2／B-3／現場即泊只 `location`（仍可拍膠囊單次聆聽，講完自動停）。A-1／A-3 一律 `location|microphone`。播報＋手動／純手動模式（含未授 `RECORD_AUDIO`）一律只以 `location` 開程。

### B-1 三支（決定版）

| 支 | 條件 | 結果 |
|----|------|------|
| (a) 有地名 | 語句含目的地 | 既有 B-1 流程；建 `session_id`；開 FGS |
| (b) 現場講法 | 無地名但含「附近」「呢度」「而家位置」等 | **現場即泊**；當前 GPS 作 destination；即時 JIT；建 `session_id` |
| (c) 失敗 | 兩者皆無 | **不啟動、不開 FGS、不推薦**；TTS「聽唔到目的地，請再講一次」；**不**建 `session_id` |

現場即泊 GPS：須可用且水平精度 ≤100m；否則失敗——不開 FGS、不推薦；TTS「暫時搵唔到你位置，請稍後再講一次」。

入口優先聲明（決定版）：「B-1 路徑 1A：Hey Google／App Actions 冷態入口屬實驗性能力，須按指定 intent、語言及裝置驗證通過後方可啟用，不列入本版必要交付或免手操作保證。正文相關冷態流程均以驗證通過為前提。本限制不適用於 B-1 路徑 1B 已開程後的本機語音互動。」驗證步驟見 `docs/testing/01_test_plan.md`（Google Doc「泊車副駕 測試清單 v2.9.1」，ID `1OaMcyeEU8m0HKuhhCLf2DCJX9iY0h1vu-ar7WGdd148`） Phase B 第 12 項。

### B-2 Trie Miss 背景解析

1. 標題 `LocalPoiTrie.kt`／掉針 Regex → 命中即鎖定繪膠囊。
2. Miss：即開 location FGS＋`AMBER_STEADY`，收到 FGS_STARTED 後 `finish()` 退回 Maps；短網址／Geocoding 喺 **FGS 作用域**由 `ApiClient.kt` 跑。
3. 沿用 **3×2.5s＋2×1s** 重試：
   - 成功 → 設終點、更新膠囊、按距離 JIT
   - **網絡失敗** → `GREY_BREATHING`＋口播一次「網絡唔順，遲啲再試」；拍膠囊重試
   - **可連線但無法解析／含糊** → `ALERT_BREATHING`＋「去邊度？請拍一拍膠囊答我」（B-2 **維持只開 location**）

## 5. Pre-FGS／工程禁令

- Pre-FGS：僅通知；無膠囊、無 GREY／AMBER；點擊前唔做 1km／150m／到場追蹤。
- 嚴禁：BR→Activity trampoline；Service 內請求執行期權限；入口彈權限對話框；無用戶互動背景開 mic FGS；FgsBootstrapActivity 固定 sleep。
- **不申請** `ACCESS_BACKGROUND_LOCATION`；**不用**系統 Geofencing。
- 「大概位置」＝未授權精確位置。
- 本模式必要權限按行程語音模式判定：`RECORD_AUDIO` 只屬全語音模式；缺 `RECORD_AUDIO` 照常開程（TTS 可用＝播報＋手動，否則純手動），以 `location` 類型啟動，橫額提示補授權以啟用語音輸入。
- 行程中撤銷（按模式）：本模式開程必要權限（精確位置、`POST_NOTIFICATIONS`、`SYSTEM_ALERT_WINDOW`；模式 A 另加通知存取權）被撤銷 → 停止 FGS、結束本程（`PERMISSION_REVOKED`）；全語音模式下 `RECORD_AUDIO` 被撤銷 → 停止聆聽與熱詞、完全停止 AudioRecord、降級 `location`，TTS 可用則按播報＋手動繼續本程。系統終止進程時按既有規則 abandon trip。
- `minSdkVersion = 33` 只屬安裝下限；語音能力一律以能力檢查判定。
- OEM 殺 FGS＝abandon trip；司機經 App／Shortcuts／Hey Google（路徑 1A，驗證通過後）重開（新 session）。

## 6. DistanceTracker（W-A.4）摘要

- >1km：BALANCED／30s；≤1km：BALANCED／5s；≤150m：HIGH_ACCURACY／1Hz（見 NativeConfig）。
- 啟動當下已 ≤1km → 即時 JIT。
- APPROACH_MUTED：80m＋連續 3s <8km/h；或 150m 內 <15km/h 且最後 fix 年齡 >5s。
- 距離持續 >200m 達 10s 退回巡航（真機調校）；無 GPS 維持靜音。
- 高頻採樣持續至行程結束或距離回退 >200m。

## 7. 客戶端流程（文字）

```
合法入口 → 權限檢查 → startForeground(location[|microphone，只限全語音模式]) → FGS_STARTED（≤2000ms 等候；語音能力靜默重查並行）
  → 繪 P 膠囊 → session_id；MIC_READY 失敗則只停用語音輸入（TTS 照常）
  → （可選）設終點／現場即泊
  → ≤1km 即時或踩入 JIT → ApiClient recommend
  → 語音優先口播／追問出原生卡 → commit
  → DistanceTracker → APPROACH_MUTED（靜音、行程繼續）
  → 熄匙（目標場 150m 內或地庫斷訊）→ PARKED_CONFIRMED：寫今日行程候選 → 在線 release（離線放棄）→ 0.5s 停止 FGS
  → 今日行程一撳確認 → /v1/sync/outbox（DRIVER_CONFIRMED）→ ratable = true（離線本機保留、HKT 00:00 清除）
```
