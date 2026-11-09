# 交接（極短）— 完整app

**現況以本節為準（2026-11-09）。下面各 Phase 係歷史，衝突時以本節同 `docs/REAL_MODE_HANDOFF.md` 文首為準。** 架構：`docs/CODE_MAP.md`。風險證據：`docs/testing/r9_ui_handoff_risk_2026-11-09.txt`。GPX：`docs/testing/gpx/README.md`。

- 建置同鍵名：`flutter pub get`，然後 `flutter build apk --debug`。設定經 `android/app/build.gradle.kts` `hkpSetting()`：Gradle `-P` → `android/local.properties`（gitignore）→ 環境變數 → 空。鍵名同 DEMO／GPX 見 REAL_MODE。唔寫秘密值。
- 本輪只改兩個測試同交接文件：`flutter analyze` 零問題；`flutter test` 269 全過。上一輪乾淨目錄先有 Android 單元測試同模擬器結果，本輪冇重跑，唔好混為一次。
- 第三次唔同停車場要明確確認。畫面確認已驗；真咪未驗。
- UI 待辦：底欄壓 ETA／留白；推薦卡曾 `BOTTOM OVERFLOWED BY 9.0 PIXELS`。
- 未驗：藍牙泊妥、反饋提交、橫向、成功空推薦。手動結束唔係泊妥。
- Navigation SDK 粵語未驗證。App 粵語 TTS 唔可以當 SDK 語言證據。
- 正常模式仍可能收到雲端 DEMO 卡；客戶端禁止預留。冇改雲端。
- 舊 Kotlin 殼同 `HKP_ACCEPTANCE_TAG` 驗收身份開關刻意保留，唔係可刪項。

- 分支起點：`完整app` 舊 baseline 係 `ed80f5a`。雲端喺 `Terraform` 分支，**唔 merge 入 App**。
- 架構同參數關係：讀 `docs/CODE_MAP.md`。平台差異：CODE_MAP §7。
- 行程邏輯只改 Dart（`lib/core/trip/`）；Kotlin 只做平台橋。舊 Kotlin 膠囊檔保留唔刪。
- 測試：`flutter analyze`、`flutter test`；Kotlin `cd android && bash ./gradlew :app:testDebugUnitTest -PHKP_USE_REAL_NAVIGATION=false`同 `=true` 都要過（`Step35NavigationSwitchTest` 已拆成「flag 路由」同「JVM 強制 sim」兩個測試）。
- Key：`MAPS_API_KEY`、`HKP_API_BASE_URL`、`HKP_API_KEY` 由 `hkpSetting()` 提供，**唔入庫、唔抄入文件**。取得方式同 2026-11-09 真導航 debug 建置見文首同 REAL_MODE，呢行唔再代表「未接入」。
- 模擬位置：`adb emu geo fix <經度> <緯度>`；debug `SIM_EVENT FIX "<緯度>,<經度>,<速度km/h>,<精度m>"`（`SimEventReceiver.kt` `parseFix`）。唔好用 `SIM_EVENT START_TRIP`（行 legacy）。
- Phase 2（未 commit）：四分頁、DEMO／正常切換（debug only）、地圖頁「現場即泊」次掣（onSite＋精度 ≤100 m 鎖現場）。未驗：真機／模擬器 E2E、真地圖、語音。
- Phase 3（未 commit）：改目的地全面重設＋epoch 丟舊結果、1 km 就緒 latch（同空位 pip 分開）、卡片預設收起。未做：改目的地向雲端 release（要 Phase 4/5 epoch）、語音指令接 `showCardDetails()`、黃 pip 門檻。

## Phase 3b（2026-11-08 HKT，未 commit）
- 改目的地即時釋放舊鎖（唔理幾遠），失敗保留重試狀態（上線／recommend 成功後重試），舊後端顯示 unsupported。雲端改動喺 cloud repo 分支 `review/cloud-destination-release`（未 push、未部署）。
- 「詳細啲」：原生 STT 文字 → `onVoiceUtterance` → Dart 派發。**Release 版 `SttVoiceInput` 仍係 stub，真機冇語音文字**；測試只係模擬訊息，唔係咪 E2E。
- （以上三項已喺 Phase 3c 處理，見下。）

## Phase 3c（2026-11-08 HKT，未 commit）
- 雲端（分支 `review/cloud-destination-release`）：每車場容量帳 `carpark_capacity/{id}`（commit／release／switch／confirm_parked 同一 transaction 讀寫）；stale 用最後已知值、從未知拒絕；開新 session 要 `session_started_at`（防 TTL 復活）；confirm_parked 唔再無條件刪鎖。Firestore emulator 並發測試通過（emulator ≠ 生產）。
- App：recommend 送 `session_started_at`；release 版用真 Android SpeechRecognizer（one-shot，冇長開熱詞），錯誤經 `onVoiceError` 落 Dart；RECORD_AUDIO 系統對話框。**真機咪未驗證**。
- 部署前要做：設 `capacity_bootstrap_enabled=true` 之前確保舊版 revision 冇流量；未部署新後端時舊 App 開唔到新 session（`legacy_session_create_enabled=false`）。

## Phase 3d（2026-11-08 HKT，未 commit）
- 泊車確認 outbox：建立時存 `lockRevision`／`destinationEpoch`（v2），重播不變；伺服器 v2 要求精確 revision，舊事件唔准改 v2 session。
- 開咪等 TTS 真完結（`onSpeechDone`，UtteranceProgressListener）；錯誤／被停／改目的地唔開咪。
- typed 伺服器回應（`server_issue.dart`）唔再當網絡斷；CONTENTION 有限重試。
- 雲端仍**未部署**；切換（bootstrap、舊 App 不相容）只寫喺 cloud `DATABASE.md`。

## Phase 3e：評分＋偏好（2026-11-08 HKT，未 commit）
- 公式／常數／分類嘅唯一來源：cloud `server/app/scoring.py`＋`config.py`（唔喺文件抄數字）。偏好唔加減分；SMART_HYBRID 純分數排序，冇配額。
- 卡 `availability`＋`commit_allowed`：參考卡（新鮮 0／stale 最後已知 0）排最尾、唔做首選、App 唔送 commit。全部參考／冇卡 → 頂層提問擴大範圍（唔自動擴）。App 首選＝第一張 viable 卡。
- 偏好存 prefs `hkp_driver_preference`，重建沿用。
- 資料來源（Phase 3f）：TD 停車場 ingest 寫 `vacancy_status`／`source_updated_at`；-1 唔當維修。咪錶 `td_meters.py` 只係本地 normaliser（未排程）。優惠 producer 按產品決定延後（冇優惠＝M=0）；痛點 producer 未有（P=0）。詳見 cloud DATABASE.md。
- App：未確認卡唔可以預留（掣同口播講明）；舊後端缺 availability 保守推斷＋標「舊版伺服器」。只係 fixture 測試，唔係 E2E。

## Phase 4a：共享 on-demand 快取（雲端，2026-11-08 HKT，未 commit／未部署）
- 單一 GCS manifest＝租約＋指標（cloud `server/app/shared_cache.py`）；所有寫者同一 CAS。預設關（`SNAPSHOT_ON_DEMAND_REFRESH_ENABLED`），上線要 bucket 寫權（只寫咗 review 建議）。App 冇改。詳見 cloud DATABASE.md。
- Phase 4b History（雲端冷路徑，本地 code，未啟用、冇收集緊）：`server/app/history.py`；同 App「今日行程」無關。詳見 cloud DATABASE.md。
- History→BQ: local adapter (staging + locked insert-only MERGE) + CSV/optional view; not executed/scheduled (DATABASE.md).

## 驗收準備（2026-11-08 HKT）
- Windows 模擬器 debug APK（fixture／模擬導航／模擬語音，冇 key）＋步驟：見交付 `HKParking_acceptance_instructions.zip`。未做 Android E2E；真地圖／真 STT 要另一個 build。

## t1245 導航目標＋r3（2026-11-08 HKT，未 commit）
- 撳「去」即導航去用戶目的地；只有成功 commit 先改去車場；改目的地／停程／泊好清走。測試只係 FakeNav，真藍線未驗。
- 驗收 APK：`HKP_ACCEPTANCE_TAG=r3` → `com.hkparking.copilot.acceptance.r3`「泊車副駕測試 r3」，同舊 App 並存。

- r4（t1253）：精確位置必要＋定位開；通知／藍牙／咪可選；冇背景位置（manifest 移除 Nav SDK merge 入嚟嗰個）；語音能力開程時套用。驗收 ID 仍 acceptance.r3，HKP_ACCEPTANCE_LABEL=r4、--build-number=4。
- 真模式交接：`docs/REAL_MODE_HANDOFF.md`。
- r6（t1266）：`GoogleNavEngine` session 初始化後對現有 view `setNavigationUIEnabled(true)`（plugin 0.11.2 只喺建立時判斷）；`HKP_NAV` logcat；路線失敗橫額 `route_failed_banner`。
