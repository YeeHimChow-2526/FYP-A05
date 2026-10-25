# 規格改動待辦（SPEC_CHANGES）— v2.9.1

用途：記低需要改規格嘅發現；人類定期帶給規格守護者，改好 Doc 後清空。

最後更新：2026-10-25

## 記錄規則

- 寫入者：Gemini（首席架構師）為主；真機測試結果或 Pi 執行時發現嘅規格問題，由 Kenny 或 Gemini 代記。
- 必記：規格矛盾或缺漏、真機調整任何常數、新增或升級入 App 嘅套件（凡會改 `pubspec.yaml`、`build.gradle(.kts)` 嘅依賴）。
- 開發工具（IDE 插件、模擬器設定、repomix）唔使記。
- 每條用下面模板，新條目加喺「條目」最底；規格守護者改好 Google Doc 後，Kenny 清空「條目」一節。

## 條目模板

```markdown
### SC-001
- 日期：YYYY-MM-DD
- 來源：Gemini／真機測試／Pi
- 裝置（如適用）：型號｜Android 版本｜語音服務版本
- 發現：
- 建議改動：
- 影響任務編號／常數名／檔案：
- 類別：規格矛盾／常數調整／新增依賴／其他
- 狀態：待處理
```

## 條目

### SC-001
- 日期：2026-10-20
- 來源：Pi
- 裝置（如適用）：不適用
- 發現：Step 1.2 需跑 JVM 單元測試，`android/app/build.gradle.kts` 原本沒有 `testImplementation`。
- 建議改動：只加測試依賴 `kotlin("test")` 與 `junit:junit:4.13.2`，不加入任何 App 執行期套件。
- 影響任務編號／常數名／檔案：W-A.1、W-A.15；`android/app/build.gradle.kts`
- 類別：新增依賴
- 狀態：待處理

### SC-002
- 日期：2026-10-20
- 來源：Pi
- 裝置（如適用）：不適用
- 發現：§4.4「現場即泊 GPS 水平精度 ≤100m」未寫入 §4.1 `NativeConfig` 程式碼區塊。業務碼禁止硬編碼距離，故於 `NativeConfig.kt` 增補同值常數供 `TripStateMachine` 引用。
- 建議改動：實作版 PART 2 §二 `NativeConfig` 區塊補上 `ON_SITE_GPS_ACCURACY_MAX_M = 100`（數值不變，只係把 §4.4 門檻納入常數檔）。
- 影響任務編號／常數名／檔案：W-A.15；`ON_SITE_GPS_ACCURACY_MAX_M`；`NativeConfig.kt`
- 類別：規格缺漏
- 狀態：待處理

### SC-003
- 日期：2026-10-21
- 來源：真機測試（模擬器閘口 A）
- 裝置（如適用）：Pixel 8 模擬器｜Android 15（API 35）｜不適用
- 發現：P 膠囊冇底色，同桌面背景溶埋一齊，睇唔清楚。實作版只定義咗追問卡底色（`#121212`，約 80% 不透明 `0xCC121212`），原生 P 膠囊嘅尺寸、底色、外圈線寬、各狀態外圈色碼，同 Status Pip 嘅直徑同綠／黃／紅色碼都未定義。`app_colors.dart` 喺規格入面只有檔名，冇色碼，而且原生膠囊讀唔到 Dart 檔。
- 建議改動：喺實作版 `NativeConfig` 區塊加入膠囊視覺常數：底色 `0xCC121212`（唔支援半透明時用實色 `#121212`）、膠囊 48dp × 48dp、外圈線寬 3dp、Status Pip 直徑 10dp；灰、琥珀、綠、黃、紅五個色碼交規格守護者定。`app_colors.dart` 用同一組色碼。
- 影響任務編號／常數名／檔案：W-A.17；`NativeConfig.kt`、`FloatingBubbleService.kt`、`lib/core/constants/app_colors.dart`
- 類別：規格缺漏
- 狀態：待處理

### SC-004
- 日期：2026-10-21
- 來源：Pi
- 裝置（如適用）：不適用
- 發現：`NEARBY_READY` 之 `StatusPip` 未定義應讀 `/v1/recommend-and-route` 邊個欄位（例如首選卡 `cards[0].vacancies`）、以咩空位數字門檻判定 `GREEN`／`YELLOW`／`RED`，以及 `vacancies == -1`（未知／咪錶過期或 `source_status=degraded`）時應顯示邊種 Pip。現行程式維持 `pip = StatusPip.NONE`，未自行發明門檻。
- 建議改動：於實作版真理表／`NativeConfig` 定義 `NEARBY_READY` StatusPip 之來源欄位、`GREEN`／`YELLOW`／`RED` 空位門檻，以及 `vacancies == -1` 之 Pip 狀態。
- 影響任務編號／常數名／檔案：W-A.1、W-A.17；`StatusPip`；`TripStateMachine.kt`、`ApiClient.kt`、`FloatingBubbleService.kt`
- 類別：規格缺漏
- 狀態：待定

### SC-005
- 日期：2026-10-22
- 來源：Pi
- 裝置（如適用）：不適用
- 發現：審計 E1、E2、E3、E4、E6 屬 Step 2／Step 3 範圍，本步不改對應生產碼。包括 B-2 分享解析、LocalPoiTrie、DistanceTracker、熄匙 receiver 註冊、TodayTripStore、release 的 Fused／STT 實作、HTTP header 與逾時、真實座標傳遞、activeService。
- 建議改動：待後續 Step（Step 2／Step 3）處理。
- 影響任務編號／常數名／檔案：W-A.2、W-A.4、W-A.9、W-A.11、W-A.16；`ShareIntentReceiverActivity`、`LocalPoiTrie.kt`、`DistanceTracker.kt`、`EngineShutdownReceiver.kt`、`TodayTripStore.kt`、`CorePorts.kt`、`ApiClient.kt`、`MainActivity.kt`
- 類別：其他
- 狀態：待後續 Step（Step 2／Step 3）處理

### SC-006
- 日期：2026-10-22
- 來源：Pi
- 裝置（如適用）：不適用
- 發現：行程中更改目的地（Destination 變更）時，應否重置 `jitTriggered` 以允許新目的地觸發預備區 JIT？目前規格未明文定義，暫維持每程唯一一次之單向鎖。
- 建議改動：待決規格項。規格守護者需明文定義改目的地是否重設 1km JIT 鎖。
- 影響任務編號／常數名／檔案：W-A.1；`jitTriggered`；`TripStateMachine.kt`；`NativeConfig.TRIGGER_DISTANCE_M`
- 類別：規格缺漏
- 狀態：待決

### SC-007
- 日期：2026-10-25
- 來源：Pi
- 裝置（如適用）：不適用
- 發現：
  1. `sim` 事件表缺 `FORCE_SCARCITY` 同擴大後有位 fixture `recommend_scarcity_expanded.json`。
  2. 規格冇定義「擴大後仍無位」點讀，現行實作用 `ZhHkTemplates.NO_MORE_OPTIONS`。
- 建議改動：補 `FORCE_SCARCITY` 同 `recommend_scarcity_expanded.json`；明文定義擴大後仍無位嘅口播句。
- 影響任務編號／常數名／檔案：`FixtureApiTransport.kt`、`SimEventReceiver.kt`、`recommend_scarcity_expanded.json`、`ZhHkTemplates.NO_MORE_OPTIONS`、`docs/specs/04_mock_and_sim_shell.md`
- 類別：規格缺漏
- 狀態：待處理

### SC-008
- 日期：2026-10-25
- 來源：Pi
- 裝置（如適用）：不適用
- 發現：熱詞待命與單次聆聽窗未分離。`stopListening()` 應只收本次聆聽窗；`startListening(hotword=true)` 開啟的待命保持至行程拆除。`setUtteranceListener(null)` 與 `onDestroy` 要清 ports。pending 答完即收窗。原因：Step 2.2 smoke 收音卡死。SimShellTest 一個測試開頭補 `Bindings.clearPorts()`，隔離全局 voiceInput 待命狀態。
- 建議改動：規格明文寫低熱詞待命與聆聽窗分離、答完收窗，以及拆除時清除待命。
- 影響任務編號／常數名／檔案：`SimVoiceInput.kt`、`SttVoiceInput`、`FloatingBubbleService.kt`、`docs/specs/04_mock_and_sim_shell.md`
- 類別：規格缺漏
- 狀態：待處理

### SC-009
- 日期：2026-10-25
- 來源：Pi
- 裝置（如適用）：不適用
- 發現：客戶端解析 recommend／409／429 未有共用完整 JSON 解析。待同步 impl Doc §10.1／§10.3。
- 建議改動：客戶端解析（SC-009）：
  1. 回應用完整 JSON 解析（共用 net/ApiResponses.kt，debug fixture 同 release 用同一套），未知欄位一律忽略。
  2. recommend 回 2xx，但 body 唔係合法 JSON 物件、缺非空 `status`、`cards` 唔係陣列或者有非物件元素 → 當網絡失敗處理（分級重試再 GREY），禁止當 SUCCESS。
  3. 缺省值：`await_voice_reply` false、`source_status` "ok"、`vacancies` -1（禁止當 0）、`is_stale` false、`distance_meters` 0；卡嘅 `spoken_cue` 係空就用頂層 `spoken_cue`。
  4. 429 嘅等候秒數先用 `Retry-After` header，冇先用 body 嘅 `retry_after_seconds`；409 只讀頂層 `spoken_cue`。
  遺留項：
  - R9：release baseUrl 同 API key 未注入
  - R10：sim 預設 recommend 用 hardcode
- 影響任務編號／常數名／檔案：Step 2.2b；`net/ApiResponses.kt`、`HttpApiTransport`、`FixtureApiTransport.kt`、`docs/specs/03_api_and_local_trie.md`
- 類別：規格缺漏
- 狀態：待處理

### SC-010
- 日期：2026-10-25
- 來源：Pi
- 裝置（如適用）：不適用
- 發現：首選確認後 commit／409／Google Maps 導航未有客戶端契約。待同步 impl Doc。
- 建議改動：客戶端導航（SC-010）：
  1. 「好／去呢個」只喺 `RECOMMENDATION_CONFIRM` 窗有效；等首選 TTS `onFinished(true)` 且 `isMicActive` 先開窗；開窗前 `speechHeard=false`。
  2. commit 200 → `NAV_TO_GATE`＋`NavigationPort`；URI `google.navigation:q=<URLEncoder UTF-8>&mode=d`，package `com.google.android.apps.maps`，`FLAG_ACTIVITY_NEW_TASK`。
  3. 409 → 口播 spoken_cue；唔 NAV／唔 Maps；記錄已滿 id；其後「有冇第二個」重查並過濾；空則「暫時冇其他選擇」。
  4. FloatingCardView／手動掣／本地切卡 → 2.4；R11 唔屬本步。
  遺留項：
  - R11：仍遺留（FloatingCardView／手動掣／本地切卡，不屬 Step 2.3）
- 影響任務編號／常數名／檔案：Step 2.3；`TripStateMachine.kt`、`NavigationPort`、`FloatingBubbleService.kt`、`docs/specs/03_api_and_local_trie.md`
- 類別：規格缺漏
- 狀態：待處理

### SC-011
- 日期：2026-10-25
- 來源：Pi
- 裝置（如適用）：不適用
- 發現：追問卡出卡、本地切卡、確認窗逾時後仍可確認、409 不收卡，未有客戶端契約。待同步 impl Doc。
- 建議改動：客戶端追問卡（SC-011）：
  1. `FloatingCardView` 只喺追問或手動降級拍膠囊時出；高度跟 `OVERLAY_MAX_CARD_HEIGHT_DP`／`OVERLAY_MAX_SCREEN_COVERAGE`；底色 `#121212` 約 80%；不設自動收起。背景模糊用 window blur-behind（API 31+ 兼且裝置支援），否則用實色；唔准對卡本身用 RenderEffect。
  2. `EXPANDED` 燈 `GREY_STEADY`。到場取樣唔強制踢走 `EXPANDED`；非 EXPANDED 進入 `APPROACH_MUTED` 先收卡。收卡還原：仍符合到場 → `APPROACH_MUTED`；有推薦 → `NEARBY_READY`；否則 `CRUISING`。
  3. 卡可見或 `EXPANDED` 時，「好」經 parse 綁 `RECOMMENDATION_CONFIRM`（自聽／低信心唔確認）。逾時唔收卡。取消無論 pending／是否播緊都停口播並收卡。窗內「詳細啲／有冇第二個」先打斷再出卡／切卡。
  4. 已有滿鎖 id 時「下一個」立即過濾重查，唔本地翻頁、唔等 350ms。否則本地切卡，`CARD_PAGE_TRANSITION_MS` 前後對 epoch；收卡／取消／進 `APPROACH_MUTED`／commit 成功／熄匙拆除令 epoch 失效。
  5. 409 不收卡。`GREY_BREATHING` 拍膠囊唔自動出卡。詳情：stale 或咪錶 degraded 讀 `METER_STALE_SPOKEN`；否則 `cardDetails`。
  6. Sim：`CARD_TAP`／`CARD_SWIPE`／`CARD_NEXT`／`CARD_CONFIRM`／`CARD_CANCEL` 經 debug `Bindings.dispatchSim`；`FORCE_TWO_INDOOR` → `recommend_success_two_indoor.json`。預設 langham 不變。
  遺留項：
  - R11：熄匙 `tts.stop` 仍唔屬本步
  - `GREY_BREATHING` 拍膠囊仍只設 `manualRetryRequested`，冇新增重試迴圈
- 影響任務編號／常數名／檔案：Step 2.4；`FloatingCardView.kt`、`FloatingBubbleService.kt`、`TripStateMachine.kt`、`CommandCodes.kt`、`docs/specs/01_ui_and_voice_fsm.md`、`docs/specs/04_mock_and_sim_shell.md`
- 類別：規格缺漏
- 狀態：待處理
