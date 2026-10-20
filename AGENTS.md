# 客戶端（Android 原生 Kotlin + Flutter App 畫面）Context v2.9.1

> 香港「**泊車副駕**」— 客戶端代理記憶。行程期間**全部車內邏輯於原生 Kotlin 執行**；Flutter 只負責 App 內畫面（設定、新手教學、今日行程）。  
> **真理源**：Google Doc 實作版 **v2.9.1**（ID `1wzfVsSobPfCQW-TNcLnRmy9LsEB5KdUSbaUJwOfeEPo`）。  
> 本包只覆蓋 App／客戶端契約；雲端算分公式、BQ／ETL、Terraform、Firestore schema、Gemini 仲裁、W-B..W-F **唔收錄**。衝突時以 Doc 正文為準。

## 架構分工（決定版）

| 層 | 負責 | 唔負責 |
|----|------|--------|
| **原生 Kotlin**（`FloatingBubbleService.kt` 為行程宿主） | FGS、P 膠囊（WindowManager）、`TripStateMachine.kt`（**膠囊／語音狀態機唯一擁有者**）、原生追問卡 `FloatingCardView.kt`、定位與 `DistanceTracker.kt`、`net/ApiClient.kt` 分級重試、語音能力檢查（`voice/VoiceCapabilityChecker.kt`）、裝置端粵語 STT／TTS、熱詞與 AudioRecord、指令碼（`voice/CommandCodes.kt`）、`poi/LocalPoiTrie.kt`、`trip/TodayTripStore.kt`、熄匙處理 | App 內設定／教學畫面 |
| **Flutter**（只隨 App 畫面運行） | 設定（三段偏好、本程熱詞開關）、6 頁新手教學＋權限狀態橫額＋頁面 5 語音能力顯示、今日行程專頁（候選一撳確認、3 格反饋）、主畫面「出發」／起步頁／現場即泊入口（經 MethodChannel 交原生開程） | 任何行車邏輯；行程期間 Flutter 不在背景運行，只經 bridge 讀取並顯示原生狀態 |

常數：行車常數 → `android/app/src/main/kotlin/com/hkparking/copilot/NativeConfig.kt`；App 畫面參數 → `lib/core/constants/app_config.dart`。

## 產品哲學

- 核心原則：**零多餘摩擦、行車安全優先、符合真實用車習慣、零閒置雲端成本、無帳號無痕追蹤**。
- 司機只喺「上車點火出發」主動開啟；行車全程退居背景，**唔干擾** Google Maps 主導航（Maps 導航發聲照常，AudioFocus 被搶時暫停本 App 收音）。
- **語音優先**：TTS 只口播首選（位置／空位／價錢）；畫面**唔自動彈卡**、唔設倒數；原生卡片只喺追問「詳細啲」／「有冇第二個」時出現；拍卡可選。
- location 類型 FGS **只**喺司機明確互動後啟動；**Pre-FGS** 僅 Heads-up 通知——無膠囊、無 GREY／AMBER overlay；膠囊只於 Service 回報 `FGS_STARTED` 後出現。
- **行程語音模式三選一**（互斥）：全語音（STT＋TTS）／播報＋手動（只 TTS）／純手動；由頁面 5 及每程開程靜默能力檢查判定；`minSdk 33` 只係安裝下限（詳見 `01` §5）。
- 結合政府開放資料＋本機商場字典樹（Kotlin）；三段偏好 `INDOOR_ONLY`／`SMART_HYBRID`／`METERS_FOCUS`。

## Spec 索引（細則）

| 檔案 | 讀呢個當你要… |
|------|----------------|
| [`docs/specs/01_ui_and_voice_fsm.md`](docs/specs/01_ui_and_voice_fsm.md) | 原生語音／追問出卡 FSM、行程語音模式與能力檢查、手動降級介面、AudioRecord → STT 閘門、收音計時、指令碼與語言模板、到場雙態、燈號；Flutter 6 頁教學、權限橫額、今日行程 |
| [`docs/specs/02_native_bridge_and_fgs.md`](docs/specs/02_native_bridge_and_fgs.md) | MethodChannel（只於 App 畫面開啟時）、FGS 啟動握手、起步入口、Kotlin 元件、W-A.1/2/4/9/13/14/16/17 |
| [`docs/specs/03_api_and_local_trie.md`](docs/specs/03_api_and_local_trie.md) | REST 端點＋DTO、`ApiClient.kt` 重試、今日行程待確認本機保留、Kotlin 字典樹、低水位與眾包口播 |
| [`docs/specs/04_mock_and_sim_shell.md`](docs/specs/04_mock_and_sim_shell.md) | 原生 `sim/`（只限 debug）fixtures／注入事件、模擬器／真機測試分工、記憶體 PSS 量度 |
| [`docs/testing/01_test_plan.md`](docs/testing/01_test_plan.md) | 測試清單 v2.9.1：Phase A 開發前語音鏈驗證（A–E）＋Phase B 產品整合 29 項（一→六）；Google Doc ID `1OaMcyeEU8m0HKuhhCLf2DCJX9iY0h1vu-ar7WGdd148` |
| [`docs/reference/android_manifest_notes.md`](docs/reference/android_manifest_notes.md) | Manifest **參考**文字（唔聲稱已喺專案落地） |
| [`android/.../NativeConfig.kt`](android/app/src/main/kotlin/com/hkparking/copilot/NativeConfig.kt) | **行車常數單一事實源**；**MUST cite，no hardcode** |
| [`lib/core/constants/app_config.dart`](lib/core/constants/app_config.dart) | Flutter App 畫面參數（教學頁數、今日行程清除時間） |

## 目錄地圖（目標 repo）

```
android/app/src/main/
  AndroidManifest.xml                 # location|microphone；無 ACCESS_BACKGROUND_LOCATION
  res/xml/shortcuts.xml               # Hey Google App Actions；destination 可選；現場講法
  kotlin/com/hkparking/copilot/
    FloatingBubbleService.kt          # 行程宿主：膠囊、FGS_STARTED／MIC_READY 回報、AudioRecord／熱詞
    FgsBootstrapActivity.kt           # 通知／冷啟動透明跳板；等候 FGS_STARTED 先 finish()（上限 2000ms）
    ShareIntentReceiverActivity.kt    # B-2 Maps 分享
    MapsNotificationListener.kt       # 模式 A 導航通知（Pre-FGS Heads-up）
    DistanceTracker.kt                # 軟件距離；非 Geofencing；APPROACH_MUTED 判定
    EngineShutdownReceiver.kt         # 藍牙／車充；先完成到場判定再 0.5s 停止 FGS
    TripStateMachine.kt               # ★ 膠囊／語音狀態機唯一擁有者
    FloatingCardView.kt               # 原生追問卡（一次一張；全卡盲撳）
    voice/SttService.kt · voice/TtsService.kt · voice/AudioPriorityManager.kt
    voice/VoiceCapabilityChecker.kt   # 離線粵語 STT／TTS 能力檢查 → 行程語音模式
    voice/CommandCodes.kt             # 內部指令碼＋語言模板（本版只填 zh-HK）
    net/ApiClient.kt                  # 3×2.5s + 2×1s；GREY_BREATHING
    poi/LocalPoiTrie.kt               # 本機字典樹由文字查出坐標 <150ms
    trip/TodayTripStore.kt            # 今日行程候選／待確認（SharedPreferences；HKT 00:00 清除）
    NativeConfig.kt                   # ★ 行車常數單一事實源
    sim/                              # 只限 debug 建置：虛擬搖桿、fixtures、注入事件
assets/hk_poi_master.json
lib/
  main.dart                           # App 入口 — 保持乾淨
  core/constants/app_config.dart      # App 畫面參數
  core/constants/app_colors.dart
  core/preferences/preference_manager.dart
  core/platform/overlay_channel.dart  # com.hkparking.copilot/overlay（只讀原生狀態、發 App 內指令）
  features/onboarding/                # 6 頁教學 + 權限橫額
  features/ignition/                  # 出發／起步頁／現場即泊入口＋設定
  features/trip_history/              # 今日行程（讀原生 TodayTripStore；一撳確認；3 格反饋）
```

## CRITICAL NEVER

1. **NEVER** embed Firestore SDK／前端直連 Firestore（只打 Cloud Run REST，由原生 `ApiClient.kt` 發出）。
2. **NEVER** 申請 `ACCESS_BACKGROUND_LOCATION`；**NEVER** 使用系統 Geofencing API（150m 用 FGS 前台軟件距離）。
3. **NEVER** 喺六種起步入口或現場即泊彈系統權限對話框（只檢查；補授權經教學頁 5／橫額）。
4. **NEVER** BroadcastReceiver→Activity trampoline；**NEVER** 喺 Service 內請求執行期權限。
5. **NEVER** Pre-FGS 繪懸浮膠囊或 GREY／AMBER overlay；冷態單擊膠囊 **NEVER** 構成 FGS 啟動。
6. **NEVER** 喺 FgsBootstrapActivity 以固定 sleep 等候；**NEVER** 喺文件或程式寫死啟動延遲／STT 延遲數字（一律真機實測）。
7. **NEVER** 喺 Flutter 放行車邏輯或第二個狀態機；行程期間 Flutter 唔喺背景運行。
8. **NEVER** 自動彈推薦卡、自動輪播、或倒數迫選；**NEVER** 無故改寫司機原有 Maps 路線。
9. **NEVER** 網絡失敗顯示 `ALERT_BREATHING`——必須 `GREY_BREATHING`（**GREY_BREATHING > ALERT**）。
10. **NEVER** 設通用離線同步／DataStore／Room／WorkManager／SQLite 補發佇列；release 離線即放棄（唯一本機保留項見 ALWAYS 6）。
11. **NEVER** 喺業務碼 hardcode 距離／逾時／卡片尺寸——**MUST** 引用 `NativeConfig.kt`（App 畫面參數引用 `app_config.dart`）。
12. **NEVER** 喺生產路徑留 TODO／`# bridge` 註解垃圾；`// MOCK:`／`// SIM:` **只准**喺原生 `sim/`（debug 建置）。
13. **NEVER** 將系統「**大概位置**」視為已授權（＝未授權精確位置；橫額「需要精確位置」）。
14. **NEVER** B-1 無地名亦無現場講法時開 FGS／建 session；**NEVER** 現場即泊喺 GPS 差（>100m）時開程。
15. **NEVER** 喺咪錶 `is_stale=true` 或 `source_status=degraded` 時顯示／讀出咪錶空位數字；**NEVER** 用本機時鐘推算新鮮度；**NEVER** 將虛擬鎖講成已預約車位。
16. **NEVER** 收集裝置 ID（眾包去重只靠伺服器 session_id＋來源 IP 5 分鐘群組）。
17. **NEVER** 讓開程語音能力重查阻塞 `FGS_STARTED`；**NEVER** 開程時自動彈語音包下載提示。
18. **NEVER** 設雲端 STT 後備或靜默回退普通話；STT 只走裝置端路徑（開發前驗證閘門見 `01` §5.3）。
19. **NEVER** 將 STT 信心度缺失或 −1 當作 0；**NEVER** 將 TTS 播放期間出現之「好」視作確認。
20. **NEVER** 喺播報＋手動／純手動模式將 `RECORD_AUDIO` 當作開程條件，或升級 microphone 類型 FGS。

## CRITICAL ALWAYS

1. **ALWAYS** `startForeground` 前檢查本模式必要權限；App 外缺項 → `finish()`＋補授權通知「請泊好車後再處理」；App 內 → 指去權限狀態橫額。
2. **ALWAYS** FGS 啟動握手：Activity 保持存在直至 Service 經 bound Service／同進程回呼回報 `FGS_STARTED` 先 `finish()`；`FGS_BOOTSTRAP_TIMEOUT_MS = 2000` 逾時照樣 `finish()` 並記錄原因；`MIC_READY` 為獨立訊號，麥克風失敗即**只停用語音輸入**（照常顯示膠囊；TTS 可用則照常口播）。
3. **ALWAYS** 語音優先：只口播首選；追問先出原生卡；全卡方框盲撳；高度 `min(175dp, 0.4×螢幕高)`（`NativeConfig`）。
4. **ALWAYS** 燈號優先次序：網絡問題 **`GREY_BREATHING` > `ALERT_BREATHING`**；全語音模式下凡需司機語音作答且 mic FGS 未升級：TTS 句尾加 **「請拍一拍膠囊答我」**（STT 不可用時不作此要求；拍膠囊顯示手動卡「下一個／確認／取消」）。
5. **ALWAYS** `ApiClient.kt` 分級重試：**3×2.5s 逾時 + 2×1s 間隔 ≈ 9.5s**（口播耗時另計）；背景靜默／主動口播分流見 `03`。
6. **ALWAYS** 到場雙態：`APPROACH_MUTED`（只靜音主動口播、行程繼續、不釋放鎖）與 `PARKED_CONFIRMED`（目標場 150m 內或地庫斷訊＋熄匙 → 今日行程候選「可能已泊入 X」→ 在線送 release）。司機於今日行程一撳確認後伺服器先設 `ratable = true`；確認時離線即本機保留、網絡恢復自動送出、**HKT 00:00 清除**——此為唯一本機保留項。
7. **ALWAYS** 六種起步或現場即泊成功開始即建新 `session_id`；其後 recommend／commit／release／sync／feedback 皆帶此 id；每次開程新 id、無心跳。
8. **ALWAYS** 照播伺服器 `spoken_cue`：低水位（≤3 個有效空位）只點名一個後備；眾包 ≥2 來源黃旗「近期有司機回報疑似霸位，請留意現場」——客戶端唔自行拼句、唔自行排除選項。
9. **ALWAYS** 收音計時由本機 VAD 擁有：`LISTEN_OPENING_WAIT_S = 3`、`LISTEN_END_SILENCE_S = 0.8`、`LISTEN_MAX_UTTERANCE_S = 8`、`STT_RESULT_TIMEOUT_S = 5`（`NativeConfig`）；介面文案「講完自動停」。
10. **ALWAYS** 狀態機只處理內部指令碼（`CommandCodes.kt`）；「好／好的／Yes」只綁定當前有效問題；文字經語言模板輸出；`speech_locale` 只送 `zh-HK`；中英別名共用同一 POI ID。
11. **ALWAYS** 行程中撤銷按模式處理：本模式開程必要權限被撤銷 → 停止 FGS；全語音模式下 `RECORD_AUDIO` 被撤銷 → 停止 AudioRecord、降級 location、TTS 可用則按播報＋手動繼續。

## B-1 三支（Hey Google／App Actions）

| 支 | 條件 | 行為 |
|----|------|------|
| **有地名** | 語句含目的地名稱 | 權限通過後開 FGS；建 `session_id`；鎖定 destination |
| **現場講法** | 無地名但含「附近」「呢度」「而家位置」等 | 進入**現場即泊**；當前 GPS 作 destination；即時 JIT；建 session |
| **失敗** | 兩者皆無 | **不啟動、不開 FGS、不推薦**；TTS「聽唔到目的地，請再講一次」；不建 session |

現場即泊（含 B-1 現場講法）：GPS 須可用且水平精度 ≤100m；否則同等失敗支（TTS「暫時搵唔到你位置，請稍後再講一次」）。

入口優先聲明（決定版）：「B-1 路徑 1A：Hey Google／App Actions 冷態入口屬實驗性能力，須按指定 intent、語言及裝置驗證通過後方可啟用，不列入本版必要交付或免手操作保證。正文相關冷態流程均以驗證通過為前提。本限制不適用於 B-1 路徑 1B 已開程後的本機語音互動。」驗證步驟見 `docs/testing/01_test_plan.md` Phase B 第 12 項。

## B-2 Trie-miss 背景解析失敗

1. 標題 `LocalPoiTrie.kt`／掉針命中 → 鎖定繪膠囊。  
2. Miss → 即 `startForeground(location)`＋`AMBER_STEADY`＋收到 `FGS_STARTED` 後 `finish()` 回 Maps；短網址／Geocoding 喺 **FGS 作用域**由原生 `ApiClient.kt` 跑。  
3. 沿用重試：**網絡失敗** → `GREY_BREATHING`＋口播一次「網絡唔順，遲啲再試」；司機拍膠囊重試。**可連線但無法解析／含糊** → `ALERT_BREATHING`＋「去邊度？請拍一拍膠囊答我」；B-2 **維持只開 location**。

## 測試策略（摘要，詳見 `04`）

- **模擬器**：GPX 路線回放（車速、距離、GPS 斷訊）、斷網、宿主機麥克風直通（粵語收音）、TTS、Maps 分享 Intent、`adb emu power ac off`（車充斷電）；配合原生 `sim/` 殼重播場景。
- **只限真機**：車載藍牙 ACL 斷開、OEM 背景殺程（Xiaomi／Samsung）、市區高樓 GPS 漂移、B-1 路徑 1A Hey Google（實驗性能力）、單一 AudioRecord → 裝置端 STT（Phase A 指定組合）、防自我辨識、實際記憶體上限。
- **完整測試清單**：`docs/testing/01_test_plan.md`（Phase A 通過只代表該指定組合可行，並不代表所有 Android 13+ 手機都可用）。
- **記憶體**：指定測試機 `adb shell dumpsys meminfo <package>` 量度行程中 PSS（FGS＋膠囊＋原生卡片），上限按實測寫入驗收門檻。
- **延遲**：FGS 啟動延遲與粵語 STT 延遲真機實測；本機字典樹由文字查出坐標 <150ms 為預設門檻。

## 客戶端路線圖

| Stage | 範圍 |
|-------|------|
| **1 原生核心** | `NativeConfig.kt`、`LocalPoiTrie.kt`、`ApiClient.kt`（重試）、`session_id`、`TodayTripStore.kt` 單元測試 |
| **2 FGS＋握手** | `FgsBootstrapActivity`／`ShareIntentReceiverActivity` ↔ `FloatingBubbleService`：`FGS_STARTED`、`MIC_READY`（失敗只停用語音輸入、TTS 照常）、語音能力靜默重查並行（W-A.16） |
| **3 狀態機＋膠囊** | `TripStateMachine.kt`、膠囊燈號、`FloatingCardView.kt` 追問出卡、到場雙態（W-A.1／W-A.17） |
| **4 行車感知＋語音** | `DistanceTracker.kt`、MapsListener（中英雙語過濾）、熄匙、AudioRecord／熱詞／AudioFocus、語音能力檢查與行程語音模式、STT 閘門、指令碼（W-A.2/4/9/13/14/19/20/21） |
| **5 Flutter 畫面** | 6 頁教學、權限橫額、設定、今日行程（讀原生紀錄、一撳確認、3 格反饋；W-A.11） |

每個 Stage 先喺模擬器＋`sim/` 殼鎖死行為，再按真機清單驗收（W-A.18）。

## 六種起步＋現場即泊（速查）

| 碼 | 入口 |
|----|------|
| A-1 | 藍牙點火 Heads-up／「開始泊車助手（啟用語音）」→ `FgsBootstrapActivity` |
| A-2 | App 內「出發」（Flutter 經 bridge 交原生） |
| A-3 | 導航 Heads-up → `FgsBootstrapActivity` |
| B-1 | Hey Google／App Actions（或熱態熱詞／拍膠囊） |
| B-2 | Google Maps 分享 → `ShareIntentReceiverActivity` |
| B-3 | App 起步頁確認出發 |
| 現場即泊 | 開 App＋當前定位即時 JIT（與「出發」同級權限檢查） |

麥克風（全語音模式）：權限齊＋未關本程熱詞 → 多入口 `location\|microphone`；關熱詞則 B-1／A-2／B-3／現場即泊只 `location`。A-1／A-3 一律帶 mic。播報＋手動／純手動模式一律只 `location`。

## W-A 任務錨點（客戶端）

- **W-A.1** FloatingBubbleService 行程宿主＋TripStateMachine 唯一擁有者  
- **W-A.2** MapsListener／ShareIntent／Bootstrap／橫額／教學頁 5  
- **W-A.4** DistanceTracker 分級採樣＋APPROACH_MUTED（80m＋<8km/h 3s；或 150m 內 <15km/h 且 fix 年齡 >5s；>200m 達 10s 退回巡航）  
- **W-A.9** EngineShutdown：先完成到場判定再 0.5s 停止；release 在線盡力送、離線放棄  
- **W-A.11** Flutter 今日行程：讀原生 TodayTripStore；一撳確認  
- **W-A.13** 單一 AudioRecord／熱詞／NS-AEC／AudioDeviceCallback／AudioFocus／收音計時（本機 VAD）  
- **W-A.14** mic FGS 類型、B-1 三支、shortcuts 現場講法、權限邊界  
- **W-A.16** FGS 啟動握手（FGS_STARTED、2000ms、MIC_READY 失敗只停用語音輸入、能力重查並行）  
- **W-A.17** 到場雙態與今日行程確認  
- **W-A.18** 測試策略（模擬器／真機、PSS；完整清單 `docs/testing/01_test_plan.md`）  
- **W-A.19** `VoiceCapabilityChecker.kt` 能力檢查＋三種行程語音模式＋手動降級介面  
- **W-A.20** 單一 AudioRecord → 裝置端 STT 開發前驗證（測試清單 Phase A）  
- **W-A.21** `CommandCodes.kt` 指令碼與語言模板（speech_locale = zh-HK）

## Agent 開工檢查清單

- [ ] 已讀本檔全部 NEVER／ALWAYS  
- [ ] 狀態機／語音／卡片 → `01`；Native／FGS → `02`；API／Trie → `03`；sim／測試 → `04`  
- [ ] 語音相關改動已對照 `docs/testing/01_test_plan.md` 對應項目  
- [ ] 新行車門檻只加 `NativeConfig.kt`；App 畫面參數只加 `app_config.dart`  
- [ ] Channel／REST 欄位變更必須回寫實作版 Doc，並更新本 Context 包  
