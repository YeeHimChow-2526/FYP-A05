# CODE_MAP — 完整app（v2：Dart 行程狀態機＋Kotlin 平台橋）

> 基準：`完整app` @ `ed80f5a`。數值以源碼為準，本檔**只指位置同關係，唔複製數值**。
> 改任何參數前：睇「改動影響」欄，並跑對應測試。

## 1. 擁有權

| 範圍 | 擁有者 | 位置 |
|---|---|---|
| 行程狀態（巡航、1 km 預取、出卡、導航入口、到場、泊妥） | **Dart** | `lib/core/trip/trip_state_machine.dart`、`lib/core/trip/trip_controller.dart` |
| 組裝／依賴注入（transport、nav engine、platform） | Dart | `lib/app/trip_runtime.dart` |
| FGS、定位取樣、TTS、熄匙訊號、外跳導航、SharedPreferences | Kotlin（平台橋） | `android/.../bridge/DartBridgeRuntime.kt`、`bridge/TripPlatformBridge.kt` |
| 權限、偏好寫入、今日行程（legacy channel） | Kotlin | `android/.../MainActivity.kt`（`OverlayBridge`） |
| 舊膠囊 FSM／浮卡／搖桿 | **legacy；v2 行程入口唔派事件到此，仍可能被其他入口執行，唔刪** | `TripStateMachine.kt`、`FloatingCardView.kt`、`FloatingBubbleService.kt` legacy 分支、`debug/.../sim/SimJoystick*` |

Kotlin 平台職責（FGS、定位、TTS、熄匙）**保留喺原生**，唔搬去 Dart。

## 2. 兩條 MethodChannel（實數：25 個 method、Dart 處理 13 個事件）

| Channel | Dart 包裝 | Kotlin | Dart→Kotlin method | Kotlin→Dart 事件 |
|---|---|---|---|---|
| `com.hkparking.copilot/trip_platform`（v2） | `lib/platform/trip_platform_channel.dart` | `bridge/TripPlatformBridge.kt`（`CHANNEL`） | 15：`startTripFgs ready checkStartGate fixturePost stopTripFgs applySamplingTier setNotificationText speak interruptSpeech launchGateNavigation getVoiceCapability getTransportConfig prefsLoad prefsPut prefsRemove` | 9：`onFix onTick onEngineSignal onFgsStopped onNetworkChanged onHotwordStart onShareIntake onPermissionRevoked onOemKilled` |
| `com.hkparking.copilot/overlay`（legacy＋設定） | `lib/core/platform/overlay_channel.dart` | `MainActivity.kt` `OverlayBridge` | 10：`startBubbleService getPermissionStatus getVoiceCapability startVoiceCapture stopVoiceCapture setPreferences getTripState getTodayTrips confirmParkedCandidate submitTripReport` | 4：`onTripStateChanged onTodayTripsChanged onTripEnded onMicSessionChanged` |

注意：`OverlayChannel.startBubbleService()` 喺 `lib/` 只有定義、**冇呼叫點**；佢會經 `MainActivity.kt:153-168` 以 legacy 模式起 service（見 §3）。

## 3. Service 入口追蹤（`FloatingBubbleService`）

`FloatingBubbleService` **建構時一定建立** legacy `TripStateMachine()`（`FloatingBubbleService.kt:58,64`）＝instantiate；但只有 `onStartCommand` 先決定行邊條路（`:110-141`）＝execute：
- `EXTRA_MODE == "dart_bridge"` → `bridgeRuntime.onStartCommand`（v2）
- 否則（包括 `intent == null`）→ `restartTripFromEntry(entry ?: A_1)`（**legacy**）

| 入口 | 位置 | 帶 `dart_bridge`？ | 路徑 |
|---|---|---|---|
| Dart `startTripFgs` | `TripPlatformBridge.kt:89`（`starter`） | 是 | **v2** |
| overlay `startBubbleService` | `MainActivity.kt:153-168` → `:290-294` | **否** | legacy（Dart 現時冇呼叫） |
| debug `SIM_EVENT START_TRIP` | `debug/.../sim/SimEventReceiver.kt:23,103` → `debug/.../di/Bindings.kt:76-83`（`fgsStarter`） | **否** | legacy（E2E 禁用） |
| `FgsBootstrapActivity` | `FgsBootstrapActivity.kt:91-92` | 唔直接起 service | emit `onHotwordStart` → 開 MainActivity → Dart 決定 |
| `ShareIntentReceiverActivity` | manifest `exported=true`（SEND） | 唔直接起 service | enqueue `onShareIntake` → Dart |
| 系統重啟（null intent） | `onStartCommand` | — | 兩條路都回 `START_NOT_STICKY`（`FloatingBubbleService.kt:127-141`、`DartBridgeRuntime.kt:37,44,54`），唔要求系統 sticky 自動重啟；唔等於可假設永遠收唔到 null intent，仍要守衛 |
| `bindService` | 冇呼叫點（`onBind` 回 `LocalBinder`，`:97`） | — | — |
| Receivers | `EngineShutdownReceiver.kt:69`（runtime 註冊）；debug `SimEventReceiver.kt:338-342`（runtime 註冊，`RECEIVER_EXPORTED` 只 debug） | — | 只轉發事件 |

**唔可以只喺 legacy 入口補 `EXTRA_MODE`**：bridge runtime 依賴 Dart `startTripFgs` 先建立 session；直接補會出現「service 喺 bridge 但 Dart 冇 session」。正確做法：legacy 入口改為轉交 Dart（同 `FgsBootstrapActivity` 一樣），或喺 v2 停用。

## 4. 主要參數關係（數值見源碼）

| 常數（`lib/core/constants/native_config.dart`，Kotlin 鏡像 `NativeConfig.kt`） | 用途 | 單位 | 唔好混淆／改動影響 | 必跑測試 |
|---|---|---|---|---|
| `TRIGGER_DISTANCE_M` | 1 km 背景預取門檻（`trip_state_machine.dart` `_withinTriggerForJit`） | m | 同 `DISTANCE_SANITY_TOLERANCE_MULTIPLIER` **無關** | `trip_state_machine_test`、`trip_controller_test` |
| `DISTANCE_SANITY_TOLERANCE_MULTIPLIER` | 距離合理性（GPS 跳點）檢查倍數 | 倍 | **唔係**轉灰遲滯 | `distance_tracker_test` |
| `APPROACH_RADIUS_M`／`APPROACH_CORE_RADIUS_M`／`APPROACH_EXIT_DISTANCE_M` | 到場／核心／離開到場 | m | 熄匙判定 `trip_controller.dart` `within` 用 `APPROACH_RADIUS_M` | 同上＋`engine_shutdown_detector_test` |
| `APPROACH_SPEED_THRESHOLD_KMH`、`ENGINE_OFF_MAX_SPEED_KMH` | 到場低速、熄匙最高速 | km/h | 定位速度一律 km/h（`DartBridgeRuntime.kt:150-157` → `trip_platform_channel.dart:121-126`） | 同上 |
| `GPS_FIX_STALE_S`、`VALID_FIX_MAX_ACCURACY_M` | fix 過期、有效精度 | s／m | 影響所有距離觸發 | `distance_tracker_test` |
| `LOC_INTERVAL_*`、`LOC_PRIORITY_*` | 分層取樣（>1 km／≤1 km／≤150 m） | ms | Kotlin `applySamplingTier` 執行 | `trip_controller_test` |
| `API_TIMEOUT_MS`、`API_MAX_ATTEMPTS`、`API_RETRY_INTERVAL_MS`、`RATE_LIMIT_*` | API 重試 | ms／次 | 雲端 p95 預算 | `api_client_test`、`native_config_mirror_test` |
| `SCORE_WEIGHT_*`、`SCORE_D_MAX_M` | **只係鏡像**；計分喺雲端 `server/app/config.py` | — | 改要兩邊一致 | `native_config_mirror_test` |
| `lib/core/constants/app_config.dart` | 教學頁數、今日行程重設時間（HKT）、評分 tag | — | — | `today_trip_*_test` |

Dart／Kotlin 常數一致性由 `test/core/native_config_mirror_test.dart` 守。

## 5. 數據流（v2 一程）

0. 行程前：`IgnitionScreen` 底部四分頁 偏好｜地圖（預設）｜今日行程｜設定（權限＋教學 `lib/features/settings/tutorial_screen.dart`）；開程後分頁收起，只剩 `InTripOverlay` 圓掣。偏好分頁經 `TripRuntime.setPreferenceCode` 寫 prefs（`hkp_driver_preference`）＋`TripController.preferenceCode`（recommend 用；重建沿用）。推薦卡 `RecommendationCard.availability／commitAllowed／isFallback`：`TripController.leadIndex`＝第一張 viable 卡（P 口播、「詳細啲」、語音確認都用佢）；`confirmCandidate` 拒 `commitAllowed=false`（唔送請求）；`RecommendCardsStrip` 參考／未確認卡掣 disabled（文字講明唔可以預留）；舊合約由 `CardAvailability.deriveLegacy` 保守推斷。合約 fixture：`test/fixtures/contract/`（由 cloud `tests/test_source_pipeline.py` 生成）。
1. 地圖分頁 `PreTripBottomBar`（`lib/features/nav_shell/pre_trip_bottom_bar.dart`）：「出發」→ `TripController.start(StartupEntry.a2)`；次掣「現場即泊」→ `TripController.startOnSite()`＝`start(onSite, awaitOnSiteFix: true)` → `startTripFgs(ON_SITE)`；`tsm.onSitePendingFix` 等第一個精度 ≤ `ON_SITE_GPS_ACCURACY_MAX_M` 嘅 `onFix` → `lockDestinationAt('現在位置', fix)`（距離 0）→ JIT recommend 一次；精度唔夠只口播 `ON_SITE_LOCATION_MISSED` 一次、繼續等（未設 timeout）。
2. Kotlin bridge 開 FGS（location）、定位 → `onFix` → `DistanceTracker`（`lib/core/trip/distance_tracker.dart`）→ `tsm.onDistance…`。
3. ≤ `TRIGGER_DISTANCE_M` 且 `!jitTriggered` → `_publishJitEdge` → `_recommend(backgroundPrefetch)` → `ApiClient`（`lib/core/net/`）→ transport（`fixturePost` 或 `HttpApiTransport`，由 `getTransportConfig` 決定）。
4. cards → `NavShellScreen`（`lib/features/nav_shell/nav_shell_screen.dart`）→ `RecommendCardsStrip`。
5. `confirmCandidate` → `commitSession` → `_stillCurrent(session, destinationEpoch)` → `GateCoordResolver` → `NavEngine.startGuidanceTo`（`google_nav_engine.dart`／`fake_nav_engine.dart`），失敗 → `launchGateNavigation`。
6. 熄匙（`onEngineSignal`）或 Exit（`abortTripByUser`）→ `_teardownSession`。

改目的地（Phase 3）：`lockDestinationAt` 用座標＋POI id 判斷係咪新目的地（淨係改名唔算）→ `_resetForNewDestination`：清 cards／`fullCarparkIds`／`cardsOpen`／`vacancyFresh`，`tsm.onDestinationChanged()` 重設 `jitTriggered`／`recommend`／`readinessLatched`／pip／接近樣本，並由 `navToGate`／`approachMuted`／`expanded`／`nearbyReady`／`amberSteady` 返 `cruising`（網絡壞就 `greyBreathing`）；`navToGate` 時 `navEngine.stopGuidance()`。`destinationEpoch` 每次鎖都 +1，舊 recommend／commit（包括 A→B→A）由 `_stillCurrent` 丟棄。新目的地 ≤ `TRIGGER_DISTANCE_M` 即再 JIT。

就緒同空位分開：`TripSnapshot.readinessLatched`（1 km latch，同一目的地保持到改目的地／完程，唔受距離或網絡影響）；`vacancyPip`＋`vacancyFresh`（`lib/core/trip/vacancy_pip.dart`：首選卡過期或 -1 → none＋唔新鮮；≤ `LOW_WATERMARK_VACANCIES`(3) → red；其餘 green；參數表冇黃門檻所以唔出黃）。`_applyNetworkOutcome` 只管灰／恢復，pip 由 `_recommend` 真結果設定。UI：`nav_shell_screen.dart` `trip_status_line`。

卡片：`TripController.cardsOpen` 預設 false；JIT 靜默唔開；車場卡掣 `toggleCards()`；全語音 P 只讀首選，`showCardDetails()`（「詳細啲」入口）開卡＋讀；`announceManual`／`manualOnly` 嘅 P 直接開卡。

已知缺口：Dart 冇語音指令接駁（「詳細啲」只有 `showCardDetails()` 入口）；改目的地唔會即時向雲端 release 舊鎖（現有 StrictModel 冇 `DESTINATION_CHANGED`，要 Phase 4/5 加 epoch／revision），伺服器端競態未解決；黃 pip 冇參數。

## 6. Build 開關

| 設定 | 來源次序 | 效果 |
|---|---|---|
| `HKP_USE_REAL_API`、`HKP_USE_REAL_NAVIGATION`、`HKP_USE_SIM_LOCATION`、`HKP_API_BASE_URL`、`HKP_API_KEY`、`MAPS_API_KEY` | `android/app/build.gradle.kts` `hkpSetting()`：`-P` → `android/local.properties` → 環境變數 → 空 | BuildConfig／manifest placeholder（build 時決定） |
| debug `Bindings.useRealNavigation` | `debug/.../di/Bindings.kt:62`＝BuildConfig；`:112`／`:207` 喺測試重設 false | Kotlin |
| Dart nav engine／transport | `trip_runtime.dart` `_buildController()` 用 `modeInfo`：正常模式跟 `getTransportConfig`（`useRealApi`／`useRealNavigation`）；DEMO 一律 fixture＋Fake | Google Nav SDK 或 Fake；HTTP 或 `fixturePost` |
| DEMO／正常 | `lib/app/app_mode.dart`；`TripRuntime.setMode()`（prefs key `hkp_app_mode`）。有 session／FGS 啟動中拒絕；release（`kReleaseMode`）拒絕 DEMO；地圖同設定頁常駐 marker（模式｜數據｜導航），冇靜默 fallback | 重建 controller，`generation` 通知畫面重新訂閱 |
| release | `release/.../di/Bindings.kt` 固定真 API／真導航 | — |

## 7. 平台矩陣（iOS 本輪唔建 scaffold）

| 能力 | Android（現況） | iOS（將來要做） | 後備 | 驗收 |
|---|---|---|---|---|
| 導航 | `google_navigation_flutter`（`pubspec.yaml`） | 同 plugin iOS 部分＋Maps key | 外跳 Maps | 真機導航 |
| 背景定位 | Kotlin FGS（location） | `CLLocationManager` 背景模式（Swift） | 前台定位 | 背景 1 km 觸發 |
| TTS／STT | Android TTS／SpeechRecognizer | `AVSpeechSynthesizer`／`SFSpeechRecognizer` | 手動掣 | 粵語準確度 |
| 熄匙 | 藍牙斷開＋速度（`EngineShutdownReceiver.kt`） | 無直接等價能力；另行研究 iOS 可用訊號，唔保證 CarPlay／藍牙可判斷熄匙 | 手動「我已泊好」 | — |
| Share intake | `ShareIntentReceiverActivity` | Share Extension | 手動輸入 | — |

## Phase 3b（2026-11-08）
- `lib/core/trip/trip_controller.dart`：`HeldLock`／`PendingLockRelease`／`LockReleaseState`、`_flushDestinationRelease`、`retryLockRelease`、`onVoiceUtterance`（語音指令派發；`SHOW_DETAILS`→`showCardDetails`）、開收咪生命周期。
- `lib/core/net/api_client.dart`：`destinationEpoch`／`idempotencyKey`／`lockRevision`／`newDestinationEpoch`、`RELEASE_*` 常數、`CallOutcome.bodyFields`。
- `lib/platform/trip_platform_channel.dart`：`onVoiceUtterance`、`startListening`／`stopListening`。
- Kotlin：`DartBridgeRuntime.wireVoice/emitUtterance/startListening`、`TripPlatformBridge`；測試 `Step3dVoiceBridgeTest.kt`。
- 測試：`trip_controller_test.dart` P3b-R1…R10、P3b-V1…V4、P3c-V6；`trip_platform_channel_test.dart` P3b-V5。
- **語音平台關係（Phase 3c）**：`SpeechVoiceInput`（main，`voice/SpeechVoiceInput.kt`；one-shot，start/stop/文字/錯誤）← 注入 `SpeechEngine`（`AndroidSpeechEngine`＝SpeechRecognizer，主線程；JVM 測試用假引擎）。Release `Bindings.voiceInput` 用佢；debug 仍係 `SimVoiceInput`；舊 `SttVoiceInput` stub 保留未用。
  `DartBridgeRuntime.wireVoice` → `onVoiceUtterance`／`onVoiceError{code,permanent}` → `trip_platform_channel.dart` → `TripController.onVoiceUtterance`／`onVoiceError`。Dart 只喺 P 口播後叫 `startListening(hotword:false)`；永久錯誤 → `voiceInputBlocked`、P 直接出卡。
  TTS→STT（Phase 3d）：Dart `_speak(listenAfter)` 帶 `utteranceId` → `TripPlatformBridge "speak"` → `DartBridgeRuntime.speak` → `TtsService.onFinished`（`AndroidTextToSpeechEngine` UtteranceProgressListener onDone／onError／onStop，主線程，只認當前 utteranceId）→ `onSpeechDone{utteranceId,played}` → `TripController.onSpeechDone`（同 session／epoch 先 `startListening`；played=false → 出卡）。
  泊車 outbox 身份：`TodayTripRecord` + `TodayTripSync._syncOne` → `ApiClient.syncDriverConfirmed(identity:)`；typed 伺服器回應：`lib/core/net/server_issue.dart` → `TripController.serverIssue` → 狀態列。
  RECORD_AUDIO：`MainActivity` 處理 overlay channel `requestRecordAudioPermission`（系統對話框），由偏好頁開語音輸入時觸發；manifest `<queries>` 加 `android.speech.RecognitionService`。

- 雲端快照（cloud repo，唔喺 App）：`GcsSnapshotSource`（讀）＋`shared_cache.refresh_once`（租約／CAS 寫）＋`td_carpark.run_once`（Job 同協議）；參數表喺 cloud `DATABASE.md`。
- 雲端 History（cold path）：`app/history.py`（時段＋每 slot 不可變 NDJSON）；唔係 App `features/trip_history/`。
- History→BigQuery: cloud `server/app/history_bq.py` (plan-only default; staging + insert-only MERGE; readable CSV; see cloud DATABASE.md).
- 導航目標（t1245）：`TripController._routeTo/_navStop`＋`NavTarget`（目的地 vs 車場分開、串行＋serial 防遲到）；驗收身份 `HKP_ACCEPTANCE_TAG`（android/app/build.gradle.kts，只限 debug）。

- t1253 權限政策：`lib/core/platform/trip_permissions.dart`（Dart 流程／語音能力）＋`android/.../TripPermissions.kt`（狀態＋開程條件）；MainActivity `getTripPermissions/requestTripPermission/openAppSettings/getVoiceFacts`；測試 `test/features/ignition/permission_flow_test.dart`、`TripPermissionsTest.kt`。
