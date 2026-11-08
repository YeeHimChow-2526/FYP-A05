# 真模式交接（同學版）— 2026-11-08 HKT

App：`FYP-A05-full-app` 分支 `完整app`（未 commit 改動）。雲端：`scratch-baseline/cloud` 分支 `review/cloud-destination-release`（未 commit、未部署）。
同一個 App 有 DEMO／正常兩種（設定 → DEMO 模式）；debug 先可以切。UI 流程（目的地輸入／啟動畫面）留畀 UI 同學，呢份唔郁。

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
- Live：`backend-ai-service-00008-vef` 100%，URL `https://backend-ai-service-4dc4y2pixq-df.a.run.app`，image `hkparking/server@sha256:578bba8d…2292`（tag `dest-15aab20`）。
- 新 env：`SNAPSHOT_ON_DEMAND_REFRESH_ENABLED=true`、`CAPACITY_BOOTSTRAP_ENABLED=true`；其餘 env／secret／2 CPU 2Gi 保留。
- Rollback：`gcloud run services update-traffic backend-ai-service --region asia-east2 --to-revisions backend-ai-service-00005-m4d=100`。舊版讀新 GCS 物件已測（`test_rollback_compat.py`）；Firestore 舊版用 dict `.get` 讀，鎖文件欄位一致。語義差異：新 session `expire_at` 較長、`USER_ABORTED` 舊版唔識當終結 → rollback 後舊鎖數可能偏寬，未 live 測。
- 注意：`legacy_session_create_enabled=false` → 舊 App（`com.hkparking.copilot`，冇 session_started_at）開唔到新 session。
- Image 係喺 `vacancy1`（同一 Python 依賴）上面加一層 app code（crane），因為 `grokbot-deploy` 冇 Cloud Build bucket `total-velocity-507606-q2_cloudbuild` 權限；之後正規做法係畀佢嗰個 bucket 權限再 `gcloud builds submit`。

## 2b. 舊後端（11/5）唔兼容嘅原因
Cloud Run `backend-ai-service`（asia-east2）而家 100% traffic 喺 `backend-ai-service-00005-m4d`（2026-11-05 23:27 HKT，image `hkparking/server:vacancy1`，cloud commit `3ce525f`）。
舊 request model `extra="forbid"`，唔識 `destination_epoch`、`lock_revision`、`idempotency_key`、`session_started_at`、`contract_version`；release `reason` 而家係 enum → 新 App 嘅 commit／release／sync 好大機會 422。

## 3. 部署步驟（參考；今次已做）
1. 雲端分支 commit → `gcloud builds submit server --tag asia-east2-docker.pkg.dev/total-velocity-507606-q2/hkparking/server:<新tag>`
2. `gcloud run deploy backend-ai-service --region asia-east2 --image <上面> --no-traffic --tag cand`
   → 用 cand URL 跑 smoke（recommend → commit → release）。
3. 按需刷新：加 env `SNAPSHOT_ON_DEMAND_REFRESH_ENABLED=true`；service account 要喺 state bucket 有寫權限
   （而家得 `roles/storage.objectViewer`）＝**IAM 改動，要另外批**。唔開就係用 bucket 入面嘅舊 snapshot，**唔好當佢係即時數據**。
4. `gcloud run services update-traffic backend-ai-service --region asia-east2 --to-latest`
5. Rollback：`gcloud run services update-traffic backend-ai-service --region asia-east2 --to-revisions backend-ai-service-00005-m4d=100`
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
5. 分兩次試：(a) 停程／取消 → 導航停、鎖位釋放（後端 release 200）；(b) `geo fix 114.16900 22.31950` → 泊好。
6. 行程中喺系統設定收回位置 → 返 App → 行程安全結束。
7. 語音：有 TTS 就聽到提示；咪喺 debug 係模擬（adb INJECT_UTTERANCE），真咪未驗證。唔使新手機。

## r7（2026-11-08）核心交接補充
- `confirmCandidate`：navToGate 可轉車場（成功先換鎖／路線；失敗保持原車場）；同一間＝「已經確認」；進行中＝「請等等」；過期 epoch／abort 後結果唔改路線。每間車場 idempotency key `c{epoch}-{carparkId}` 各自唯一（r7_switch_test R7-1）。
- 導航細行 `nav_target_line`：目的地 vs 實際導航目標＋確認結果；SDK 成功前唔寫「導航緊」。
- 證據限制：Google 導航聲由 SDK 按時機播（r6 log 10:23:39、10:32:02 HKT，中間約 8 分鐘原因未明）；10:32 冇新 app 路線請求；模擬器能否聽到無法由 log 推斷；SDK 當時用 en-us 語音，未改語言。Debug 咪仍為模擬。
- 交 UI 同學（未改）：輸入／開始流程；底部 P 同中間箭嘴功能重疊（箭嘴可收埋 P 卡但 P 只可開）；卡片捲動；地圖 chrome 遮擋。

## r8（2026-11-08）
- App TTS：Dart bridge 行程中 service 自己嘅 VoiceCapabilityChecker 冇人 check（UNKNOWN）→ r7 每句 `tts_unavailable`。`DartBridgeRuntime.speak` 先叫 `FloatingBubbleService.syncBridgeTtsAvailable()`（原生 init 失敗就唔覆蓋；manual-only 由 Dart `_speak` 擋）。測試 `R8BridgeTtsTest`。
- 定位：`GoogleNavEngine(simulateUserLocation:)` 由 `getTransportConfig.useSimLocation`（debug 且 HKP_USE_SIM_LOCATION）控制；false 時唔再 `simulator.setUserLocation`，session ready 後一次 `removeUserLocation`。測試 R8-1..3。
- DEMO 開關現狀：debug only；本地 fixture + FakeNavEngine 面板（唔係真地圖）；定位仍係裝置 GPS。「真地圖＋固定模擬行程」未實作。
- 未證實：實機聲音、定位延遲全部來源、路線橙色原因。

## r9 DEMO 行程 source map（UI 同學）
- 場景數據／路線點：`lib/app/demo_trip.dart`（`DemoTripScenario`：目的地朗豪坊 22.3180,114.1686；彌敦道 7 個點，15 m／秒步長）；`DemoTripPlayer` start/tick/stop。
- 模式接線：`lib/app/trip_runtime.dart`（`modeInfo`、`engineFor`、`_wireDemo`、`syncDemoPlayer`）；DEMO＝真 GoogleNavEngine(simulateUserLocation:true)＋ChannelFixtureTransport。
- 裝置 GPS 閘：`TripController.deviceFixesEnabled`／`onDeviceFix`；channel `onFix` 入口 `lib/platform/trip_platform_channel.dart`。
- 示範車場／空位：`android/app/src/debug/kotlin/com/hkparking/copilot/sim/FixtureApiTransport.kt`、`android/app/src/debug/assets/mock/*.json`；閘坐標 `lib/core/nav/gate_coord_resolver.dart` `kDemoCarparkCoords`。`Bindings.fixturePostOrNull` r9 起永遠用 fixture。
- UI 入口：設定分頁 `demo_mode_switch`（`ignition_screen.dart`）；行程中 overlay `InTripOverlay`（`nav_shell_screen.dart`）、卡 `recommend_cards_strip.dart`、圓形掣 `in_trip_circular_chrome.dart`。
- 限制：冇暫停；DEMO 喺真 SDK 嘅效果未經實機驗收；emulator 聲音問題未解決。
