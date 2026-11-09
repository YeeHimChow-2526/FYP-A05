# 真模式交接（同學版）

**現況以第 0 節為準（2026-11-09）。下面 2026-11-08 各節係歷史，衝突時以第 0 節為準。** 極短版：`docs/HANDOFF_SHORT.md`。架構：`docs/CODE_MAP.md`。DEMO 導航來源以本節第 0 節為準。風險證據：`docs/testing/r9_ui_handoff_risk_2026-11-09.txt`。GPX 步驟唔再抄一遍：`docs/testing/gpx/README.md`。

## 0. 2026-11-09 現況

### 建置同執行
- `flutter pub get`，然後 `flutter build apk --debug`。模擬器覆蓋安裝用 `adb install -r`，唔卸載、唔清資料。
- 設定只經 `android/app/build.gradle.kts` `hkpSetting()`：Gradle `-P` → `android/local.properties`（gitignore，由本機自己放）→ 環境變數 → 空。空白 URL／key 當離線，唔會把值寫入文件。
- 鍵名：`sdk.dir`、`flutter.sdk`、`flutter.buildMode`、`flutter.versionName`、`flutter.versionCode`（Flutter／SDK 路徑）；`HKP_API_BASE_URL`、`HKP_API_KEY`、`MAPS_API_KEY`；`HKP_USE_REAL_API`、`HKP_USE_REAL_NAVIGATION`、`HKP_USE_SIM_LOCATION`；可選 `HKP_ACCEPTANCE_TAG`、`HKP_ACCEPTANCE_LABEL`。
- 2026-11-09 乾淨目錄驗收冇設 `HKP_ACCEPTANCE_TAG`，debug 包名係 `com.hkparking.copilot`，versionCode 1。設咗 tag 先會變成 `com.hkparking.copilot.acceptance.<tag>`。呢個開關保留。

### DEMO 同正常
- 設定頁 `demo_mode_switch`。debug 先可以切；行程中唔切；release 不准 DEMO。標籤：`正常｜即時數據｜Google 導航` 或 `DEMO｜示範數據（fixture）｜Google 導航`。
- 正常：裝置／模擬器 GPS ＋ 真 HTTP（`HKP_USE_REAL_API`）＋ 真 Navigation SDK（`HKP_USE_REAL_NAVIGATION`）。可能收到雲端 DEMO 卡；客戶端把示範卡設成不可預留（按鈕「示範數據，唔可以預留」）。冇改雲端目錄。
- DEMO：本地 fixture（`FixtureApiTransport`、`android/app/src/debug/assets/mock/`）＋ 真 Google 地圖上重播 `lib/app/demo_trip.dart`，唔打雲端鎖位。預設推薦係 `recommend_success_langham.json` 一張朗豪坊卡。
- 正常模式行車 GPX：`docs/testing/gpx/README.md`（太子→朗豪坊、深水埗→海港城）。2026-11-09 模擬器核心驗收用嘅係 `adb emu geo fix` 同現場即泊，**冇播呢兩份 GPX**。

### 測試（分輪，唔好混）
- **行程反饋修正加舊入口封口（2026-11-09 已推上 完整app）**：`flutter analyze` 零問題；`flutter test` 282 通過、0 失敗。Android `:app:testDebugUnitTest -PHKP_USE_REAL_NAVIGATION=false` 同 `=true` 都是 348 項、0 失敗、0 錯誤、86 skipped。`:app:compileReleaseKotlin` 通過。release 帶 `hkp_feedback_fixture` extra 未實際啟動。模擬器 emulator-5554 用 debug 測試注入，冇呼叫生產寫入 API。HTTP 失敗句沒有在模擬器出現。
- **上一輪乾淨目錄（同一基準、當時未包含今次測試修正）**：Android `:app:testDebugUnitTest` 344 項、0 失敗、86 skipped。emulator-5554 核心項通過：DEMO 選場後導航改去朗豪坊商場停車場；正常模式香港 GPS，示範卡不可預留；第三次海富要畫面確認，取消同逾時不轉，同意只一次；手動結束後新行程重設計數；殺 App 後模式保留、舊行程畫面不留。詳見當時專案外 `r9-clean-accept-2026-11-09.txt`，呢份唔再抄日誌。

### 行為同限制
- 第三次唔同停車場必須明確確認。未同意不 commit、不發新路線，已確認目標維持上一間。畫面「確認／取消」已驗。真咪「好／唔好」未驗。
- UI 待辦：底欄壓住 Navigation SDK ETA／留白過大；推薦卡曾出現 `BOTTOM OVERFLOWED BY 9.0 PIXELS`。交 UI，未改佈局。
- 行程反饋：畫面仍組 flat JSON 做本機判斷。對外 HTTP 只送 `session_id`、`trip_timestamp`、`feedback`。確認同步要明確成功先送；404／409／422 拒絕後不送、不標已提交，重開仍然有效。舊紀錄沒有 `confirmSyncSent` 時不當作已同步。升級前已同步但未交反饋的舊列，可能不能再提交；沒有補送或恢復。HTTP 失敗顯示「未能確認提交結果」，不表示伺服器一定未收到，也不會變成「已提交反饋」。反饋沒有自動補送。這句只由 widget 測試覆蓋，模擬器 fixture 固定成功，不能當成模擬器已驗。
- 舊 overlay 三個行程入口 `getTodayTrips`、`confirmParkedCandidate`、`submitTripReport` 已停用，回 `LEGACY_ENTRY_DISABLED`／「舊入口已停用」，不假成功。不再 flush、不改正式 prefs、不發 HTTP。正常今日行程仍走 Dart `TripRuntime`。`onTodayTripsChanged` 事件保留。定位、語音、前台服務沒有停。`docs/specs/` 同 `.ai/` 嘅舊段落已加「已停用（2026-11-09）」標註，原文冇刪。
- 雲端（`15aab20`，未改、未部署）仍接受重複提交，不驗證 feedback 內容，不強制香港午夜截止。這三項未完成。車場身份不在這條 request 裡核對。
- 未驗 live API。模擬器反饋用 debug 測試注入（`hkp_feedback_fixture`），不接觸生產 API，不能當成線上通過。未驗：藍牙熄匙先至有「可能已泊入」、橫向、成功空推薦（`parse=ok cards=0`）。手動結束唔係泊妥。
- Navigation SDK 導航語言未驗證為粵語。模擬器出現英文 Then／街名。App 自己嘅粵語 TTS 唔可以當 SDK 係粵語。
- 舊 Kotlin 殼（包括 `FloatingBubbleService.kt`、`CorePorts.kt` 嘅 `SttVoiceInput` stub）同驗收身份開關刻意保留，唔係本輪可刪清單。

## 歷史（2026-11-08）

App 當時喺 `完整app` 工作樹。雲端紀錄見下面各節，**今輪冇改雲端**。
同一個 App 有 DEMO／正常兩種（設定 → DEMO 模式）；debug 先可以切。UI 流程（目的地輸入／啟動畫面）留畀 UI 同學。

## 1. 狀態
| 項目 | 已實作 | 本機測試過 | 機／雲端未驗證 |
|---|---|---|---|
| 目的地優先導航，確認成功先轉去停車場 | ✔ | ✔ Dart `TripController` G1–G6 | 真 Google 路線 |
| 權限政策（精確位置必要；通知／藍牙／咪可選；冇背景位置） | ✔ | ✔ `permission_flow_test` PF1–PF10、Kotlin `TripPermissionsTest`、b13 | Android 實機對話框（r4 喺模擬器驗） |
| 語音：manual／TTS／全語音；背景唔開咪 | ✔ | ✔ PF4–PF6、Kotlin v9 | 真 SpeechRecognizer＋真咪（只 release） |
| 後端 epoch／revision 鎖位、release 冪等 | ✔（PR #5，commit 15aab20） | ✔ 雲端 142 過／7 skip；Firestore emulator 7 過 | **live 驗證過**（2026-11-08 09:09 HKT）：commit／重覆 commit（replayed）／release／重覆 release（ALREADY_CLOSED），用 DEMO 車場＋smoke session |
| 即時車位（TD）按需刷新 | ✔ | ✔ 單元測試 | **live 驗證過**：manifest generation 變咗、checked_at 09:08:51 HKT、TD source 09:07:03 HKT（runtime SA 寫 bucket 成功）。回應頂層 `source_snapshot_ts` 係最舊 region（靜態 DEMO），以每張卡 `source_updated_at` 為準 |
| Perks 爬蟲／AI、BigQuery 統計上線 | ✘ 唔喺範圍 | — | — |

## 2. 已部署（2026-11-08）
實值見 Kenny，唔放 repo。
- Live：`<REVISION>` 100%，URL `<CLOUD_RUN_URL>`，image `<REGISTRY_PATH>`（tag `dest-15aab20`）。
- 新 env：`SNAPSHOT_ON_DEMAND_REFRESH_ENABLED=true`、`CAPACITY_BOOTSTRAP_ENABLED=true`；其餘 env／secret／2 CPU 2Gi 保留。
- Rollback：`gcloud run services update-traffic backend-ai-service --region asia-east2 --to-revisions <PREVIOUS_REVISION>=100`。舊版讀新 GCS 物件已測（`test_rollback_compat.py`）；Firestore 舊版用 dict `.get` 讀，鎖文件欄位一致。語義差異：新 session `expire_at` 較長、`USER_ABORTED` 舊版唔識當終結 → rollback 後舊鎖數可能偏寬，未 live 測。
- 注意：`legacy_session_create_enabled=false` → 舊 App（`com.hkparking.copilot`，冇 session_started_at）開唔到新 session。
- Image 係喺 `vacancy1`（同一 Python 依賴）上面加一層 app code（crane），因為 `<DEPLOY_ACCOUNT>` 冇 Cloud Build bucket `<GCP_PROJECT_ID>_cloudbuild` 權限；之後正規做法係畀佢嗰個 bucket 權限再 `gcloud builds submit`。

## 2b. 舊後端（11/5）唔兼容嘅原因
Cloud Run `backend-ai-service`（asia-east2）而家 100% traffic 喺 `<PREVIOUS_REVISION>`（2026-11-05 23:27 HKT，image `<REGISTRY_PATH>:vacancy1`，cloud commit `3ce525f`）。
舊 request model `extra="forbid"`，唔識 `destination_epoch`、`lock_revision`、`idempotency_key`、`session_started_at`、`contract_version`；release `reason` 而家係 enum → 新 App 嘅 commit／release／sync 好大機會 422。

## 3. 部署步驟（參考；今次已做）
1. 雲端分支 commit → `gcloud builds submit server --tag <REGISTRY_PATH>:<新tag>`
2. `gcloud run deploy backend-ai-service --region asia-east2 --image <上面> --no-traffic --tag cand`
   → 用 cand URL 跑 smoke（recommend → commit → release）。
3. 按需刷新：加 env `SNAPSHOT_ON_DEMAND_REFRESH_ENABLED=true`；service account 要喺 state bucket 有寫權限
   （而家得 `roles/storage.objectViewer`）＝**IAM 改動，要另外批**。唔開就係用 bucket 入面嘅舊 snapshot，**唔好當佢係即時數據**。
4. `gcloud run services update-traffic backend-ai-service --region asia-east2 --to-latest`
5. Rollback：`gcloud run services update-traffic backend-ai-service --region asia-east2 --to-revisions <PREVIOUS_REVISION>=100`
   注意：新版寫入嘅 Firestore 文件有新欄位；舊版係 extra=forbid，讀 session 文件可能有問題（未驗證）→ rollback 之後先清 test session。

## 4. Maps／Navigation key（要 Kenny 批）
- box 上有 key 檔：`/workspace/MAPS_API_KEY.txt`（唔好 commit、唔好印出嚟）。限制睇唔到：project 嘅 API Keys API 未開。
- 要加嘅 Android 限制（保留原有）：package `com.hkparking.copilot.acceptance.r3`，SHA-1 `6F:2D:34:FB:E5:86:75:38:E0:C1:1F:37:D3:B4:D4:78:37:FA:22:3F`（debug cert，同 r3／r4 一樣）。
- APK 入面一定會有 Maps key（Android SDK 嘅設計），所以要靠 package＋SHA-1 限制保護。

## 5. 真模式 build（只喺 build 時用 env，唔寫入 repo）
```bash
export JAVA_HOME=/home/box/toolchains/jdk21
HKP_ACCEPTANCE_TAG=r3 HKP_ACCEPTANCE_LABEL=r5 \
HKP_USE_REAL_API=true HKP_USE_REAL_NAVIGATION=true HKP_USE_SIM_LOCATION=false \
HKP_API_BASE_URL=<新 Cloud Run URL> HKP_API_KEY=<INTERNAL_API_KEY，由 Secret Manager 攞> \
MAPS_API_KEY="$(cat /workspace/MAPS_API_KEY.txt)" \
flutter build apk --debug --build-number=5
```
測試：`flutter analyze`、`flutter test`、`cd android && bash ./gradlew :app:testDebugUnitTest -PHKP_USE_REAL_NAVIGATION=false|true`、`:app:compileReleaseKotlin`。

## 6. 模擬器驗收清單（真模式 build）
用 `adb install -r` 升級（同一個 acceptance.r3）。起點 `adb emu geo fix 114.17480 22.33710`。
1. 第一次撳出發 → 解釋 → 拒絕位置 → 橫額＋再試／系統設定；目的地照入到。再批精確位置；通知揀拒絕 → 照開程。
2. 目的地「朗豪坊商場」→ 去 → **藍色路線去朗豪坊**。
3. `geo fix 114.17080 22.32540`（約 0.83 km）→ 出 P 推介（真後端車位；要睇 source／更新時間係咪新）。
4. 開卡 → 明確確認 → 藍色路線**改去停車場**。確認失敗／滿 → 保持去原目的地。
5. 分兩次試：(a) 手動結束 → 導航停；**手動結束唔係泊妥**，唔會因此出現「可能已泊入」。(b) 藍牙熄匙先至係泊妥候選，2026-11-09 未驗。
6. 行程中喺系統設定收回位置 → 返 App → 行程安全結束。
7. 語音：有 TTS 就聽到提示；咪喺 debug 係模擬（adb INJECT_UTTERANCE），真咪未驗證。唔使新手機。

## r7（2026-11-08）核心交接補充
- 2026-11-09 起 R7-1 以文首為準：A、B 成功後，第三間不同車場先 `awaiting_consent`，明確同意先轉一次。下面呢句只描述第一次同第二次，以及失敗／同一間／進行中。
- `confirmCandidate`：navToGate 可轉車場（成功先換鎖／路線；失敗保持原車場）；同一間＝「已經確認」；進行中＝「請等等」；過期 epoch／abort 後結果唔改路線。每間車場 idempotency key `c{epoch}-{carparkId}` 各自唯一。
- 導航細行 `nav_target_line`：目的地 vs 實際導航目標＋確認結果；SDK 成功前唔寫「導航緊」。
- 證據限制：Google 導航聲由 SDK 按時機播（r6 log 10:23:39、10:32:02 HKT，中間約 8 分鐘原因未明）；10:32 冇新 app 路線請求；模擬器能否聽到無法由 log 推斷；SDK 當時用 en-us 語音，未改語言。Debug 咪仍為模擬。
- 交 UI 同學（未改）：輸入／開始流程；底部 P 同中間箭嘴功能重疊（箭嘴可收埋 P 卡但 P 只可開）；卡片捲動；地圖 chrome 遮擋。

## r8（2026-11-08）
- App TTS：Dart bridge 行程中 service 自己嘅 VoiceCapabilityChecker 冇人 check（UNKNOWN）→ r7 每句 `tts_unavailable`。`DartBridgeRuntime.speak` 先叫 `FloatingBubbleService.syncBridgeTtsAvailable()`（原生 init 失敗就唔覆蓋；manual-only 由 Dart `_speak` 擋）。測試 `R8BridgeTtsTest`。
- 定位：`GoogleNavEngine(simulateUserLocation:)` 由 `getTransportConfig.useSimLocation`（debug 且 HKP_USE_SIM_LOCATION）控制；false 時唔再 `simulator.setUserLocation`，session ready 後一次 `removeUserLocation`。測試 R8-1..3。
- DEMO 開關現狀：以下面 r9 段為準（fixture 數據＋真 Google 地圖重播）。呢度舊寫法「FakeNavEngine、未實作真地圖」作廢。
- 未證實：實機聲音、定位延遲全部來源、路線橙色原因。

## r9 DEMO 行程 source map（UI 同學）
- 場景數據／路線點：`lib/app/demo_trip.dart`（`DemoTripScenario`：目的地朗豪坊 22.3180,114.1686；彌敦道 7 個點，15 m／秒步長）；`DemoTripPlayer` start/tick/stop。
- 模式接線：`lib/app/trip_runtime.dart`（`modeInfo`、`engineFor`、`_wireDemo`、`syncDemoPlayer`）；DEMO＝真 GoogleNavEngine(simulateUserLocation:true)＋ChannelFixtureTransport。
- 裝置 GPS 閘：`TripController.deviceFixesEnabled`／`onDeviceFix`；channel `onFix` 入口 `lib/platform/trip_platform_channel.dart`。
- 示範車場／空位：`android/app/src/debug/kotlin/com/hkparking/copilot/sim/FixtureApiTransport.kt`、`android/app/src/debug/assets/mock/*.json`；閘坐標 `lib/core/nav/gate_coord_resolver.dart` `kDemoCarparkCoords`。`Bindings.fixturePostOrNull` r9 起永遠用 fixture。
- UI 入口：設定分頁 `demo_mode_switch`（`ignition_screen.dart`）；行程中 overlay `InTripOverlay`（`nav_shell_screen.dart`）、卡 `recommend_cards_strip.dart`、圓形掣 `in_trip_circular_chrome.dart`。
- 限制：冇暫停；DEMO 喺真 SDK 嘅效果未經實機驗收；emulator 聲音問題未解決。
