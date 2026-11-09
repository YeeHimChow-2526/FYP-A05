# 泊車副駕

香港用車時嘅泊車副駕 App。Flutter 負責畫面同行程狀態；Android 原生 Kotlin 負責前台服務、定位、語音同平台橋。

交接基準係 GitHub 分支 `完整app`，commit `91edc3ea9569b34d8ecc32fca3c2f38ecbaee5f7`。本工作樹係 `local/r9-verified-2026-11-09`，上面仲有未 commit 嘅行程反饋同舊入口封口。現況細則見 `docs/HANDOFF_SHORT.md` 同 `docs/REAL_MODE_HANDOFF.md` 文首。

舊「project kit／context package」說明（`hk_parking_client_context`、`hk_parking_project_kit_v291`、`git init`、Pi／Gemini、Google Doc v2.9.1）已搬去 [`docs/archive/README_project_kit_v291.md`](docs/archive/README_project_kit_v291.md)。

## 開 App

需要 Flutter、Android SDK、Java 17。路徑同開關放喺 `android/local.properties`（gitignore），經 `android/app/build.gradle.kts` 嘅 `hkpSetting()` 讀取：Gradle `-P`，然後 `local.properties`，然後環境變數。下面只係欄位名，唔好寫真值。

- SDK：`sdk.dir`、`flutter.sdk`、`flutter.buildMode`、`flutter.versionName`、`flutter.versionCode`
- API／地圖：`HKP_API_BASE_URL`、`HKP_API_KEY`、`MAPS_API_KEY`
- 開關：`HKP_USE_REAL_API`、`HKP_USE_REAL_NAVIGATION`、`HKP_USE_SIM_LOCATION`（值用 `true` 或留空，唔好抄真 URL 或 key）
- 可選：`HKP_ACCEPTANCE_TAG`、`HKP_ACCEPTANCE_LABEL`

```text
flutter pub get
flutter run
```

debug APK：`flutter build apk --debug`。覆蓋安裝用 `adb install -r`，唔卸載、唔清資料。

## 模式

- 正常：模擬器或手機 GPS，加上 `HKP_USE_REAL_API`、`HKP_USE_REAL_NAVIGATION`。畫面標「正常｜即時數據｜Google 導航」。
- DEMO：只限 debug。本地 fixture 加真 Google 地圖重播，唔打雲端鎖位。畫面標「DEMO｜示範數據（fixture）｜Google 導航」。release 唔可以入 DEMO。

## 而家點行

行程嘅唯一真實狀態喺 Dart `TripRuntime`（`lib/app/trip_runtime.dart`、`lib/core/trip/`）。Kotlin `TripStateMachine` 同 overlay channel 仲留喺度。`getTodayTrips`、`confirmParkedCandidate`、`submitTripReport` 已停用，回 `LEGACY_ENTRY_DISABLED`／「舊入口已停用」。定位、語音、前台服務冇停。

行程反饋 HTTP 只送：

```json
{
  "session_id": "sess-demo-0001",
  "trip_timestamp": 1792995600,
  "feedback": {
    "rating_tag": "GOOD",
    "perk_status_tag": "VALID",
    "perk_notes": ""
  }
}
```

`rating_tag` 係 `GOOD`／`OK`／`BAD`。`perk_status_tag` 係 `VALID`／`INVALID`／`NONE`。`carpark_id` 唔入呢條 body。

## 已知限制

- 雲端未擋重複提交、未驗 feedback 內容、未做香港時間午夜截止、未檢查 `target_carpark_id`。
- 導航 SDK 轉彎語音跟裝置語言，App 控制唔到。App 自己嘅口播先係粵語。SDK 粵語未驗。
- release 而家用 debug 簽名（`android/app/build.gradle.kts` 嘅 release `signingConfig`）。
- 底欄留白同推薦卡 `BOTTOM OVERFLOWED BY 9.0 PIXELS` 交 UI。
- 第三次唔同停車場要明確確認。等待、逾時、殺 App 都唔會偷偷轉線；明確確認後只 commit／轉線一次。畫面確認已驗；真咪「好／唔好」未驗。
- 冇 `canListen` 時唔可以叫人開口答，改用畫面確認／取消。
- 手動結束唔等於泊妥。冇藍牙就唔會自動判斷「可能已泊入」，係已知缺口。
- 升級前已同步但未交反饋、而且冇 `confirmSyncSent` 嘅舊列，可能不能再提交。

## 文件

- 短交接：`docs/HANDOFF_SHORT.md`
- 真模式交接：`docs/REAL_MODE_HANDOFF.md`
- 程式地圖：`docs/CODE_MAP.md`
- API 樣本：`docs/api/`
- 規格：`docs/specs/`（v2.9.1 正文保留，文首或相關段有 2026-11-09 更正）
