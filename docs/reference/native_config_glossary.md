# NativeConfig 詞彙（非數值表）

解釋 `NativeConfig` 命名同非顯然行為。本檔唔複製數值表、唔貼 `object NativeConfig { … }`。

**新線 SoT：** [`lib/core/constants/native_config.dart`](../../lib/core/constants/native_config.dart)（SCREAMING_SNAKE，數值同 Kotlin 1:1）。Kotlin [`NativeConfig.kt`](../../android/app/src/main/kotlin/com/hkparking/copilot/NativeConfig.kt) 只係凍結殼，供 FBS 編譯，Step 3 拆 FBS 後先刪。

- Flutter 畫面參數：[`lib/core/constants/app_config.dart`](../../lib/core/constants/app_config.dart)
- 行為細節：[`docs/specs/01_ui_and_voice_fsm.md`](../specs/01_ui_and_voice_fsm.md)、[`docs/specs/03_api_and_local_trie.md`](../specs/03_api_and_local_trie.md)

## 子系統

- **卡片尺寸**（`OVERLAY_*`／`CARD_*`）：高度取 dp 上限同螢幕覆蓋比例嘅較小者；切卡只用於追問，唔自動彈卡、唔倒數。
- **FGS 握手**（`FGS_BOOTSTRAP_*`）：等 `FGS_STARTED` 有上限，逾時仍 `finish()` 並記錄原因。`MIC_READY` 獨立；麥克風失敗只停語音輸入。
- **導航觸發／靜默追問**（`NAV_*`／`TRIGGER_*`／`SILENCE_DRIVE_*`）：置信度分靜默鎖定／追問／丟棄。`TRIGGER_DISTANCE_M` 係 1km JIT 預備區。
- **粵語 STT／VAD**（`LISTEN_*` vs `STT_*`；`SPEECH_LOCALE_*`；`VOICE_CAPABILITY_*`）：`LISTEN_*` 係本機 VAD 計時；`STT_*` 係引擎結果等候／信心。`speech_locale` 只送 `zh-HK`。能力重查同握手並行，唔阻塞 `FGS_STARTED`。
- **到場／APPROACH**（`APPROACH_*`／`GPS_FIX_STALE_*`／`TEARDOWN_*`／`ENGINE_OFF_*`）：外圈同核心分開；熄匙先完成到場判定再淡出停 FGS。見 `01`。
- **分級定位**（`LOC_INTERVAL_*`／`LOC_PRIORITY_*`）：遠／近／接近三檔；須已有 location FGS。唔用系統 Geofencing。
- **今日行程 prefs**（`TODAY_TRIPS_*`／`TODAY_TRIP_NOTES_MAX_CHARS`）：SharedPreferences key；HKT 轉日清除。`app_config.todayTripsResetTimeHkt` 只係畫面對齊，唔另計新鮮度。
- **分數權重**（`SCORE_*`）：**客戶端只備查**，對齊雲端 `.env`；客戶端唔重算權威分。
- **網絡防禦**（`API_*`／`RATE_LIMIT_*`／`MAX_RESPONSE_BYTES`／`API_IO_POOL_*`）：普通逾時同 HTTP 429 分路；body 同 thread pool 有硬上限，超限當 network error。

## 命名

- 後綴：`_MS` 毫秒、`_S` 秒、`_M` 米、`_KMH` 時速、`_DP` dp。
- `LISTEN_*`：VAD（開口等、句尾靜音、單句上限）。`STT_*`：引擎結果逾時或信心門檻。
- `APPROACH_RADIUS_M`：外圈。`APPROACH_CORE_RADIUS_M`：核心（較細）。

## 非顯然互動

- **重試 vs 口播：** `API_MAX_ATTEMPTS` 只計普通逾時總嘗試。背景查詢靜默，失敗用 `GREY_BREATHING`，三次後停自動重試。主動請求先口播「網絡有啲慢，我再試吓」，仍敗口播「網絡唔順，遲啲再試」。口播耗時唔計入逾時公式。**HTTP 429 唔入呢個次數**——用 `RATE_LIMIT_*`：有 `Retry-After` 就等候（夾喺 min/max，上限 `RATE_LIMIT_MAX_WAIT_MS`），冇就等 `RATE_LIMIT_RETRY_DELAY_MS`，之後只再試一次；仍敗 → `GREY_BREATHING`，等司機拍膠囊。見 `03`。
- **STT −1／缺失唔當 0。** 低於 `STT_CONFIDENCE_THRESHOLD` 口播「太嘈聽唔清，稍後再試」後閉嘴；分數唔跨引擎比較。唔設雲端 STT，唔靜默回落普通話（`SPEECH_LOCALE_DEFAULT=zh-HK`）。TTS 播放期間嘅「好」唔當確認。
- **APPROACH／到場雙態：** 外圈內先高頻定位。核心內且低速連續達秒數，或外圈內低速且 fix 過期（仍在請求）→ `APPROACH_MUTED`（只靜音主動口播，行程繼續，不釋放鎖）。距離持續超出退出門檻先退回巡航；無 GPS 維持靜音。目標場外圈內或地庫斷訊，再加熄匙 → `PARKED_CONFIRMED`，寫今日行程候選「可能已泊入 X」，在線送 release；離線放棄 release。今日行程一撳確認先令伺服器 `ratable=true`；確認時離線先本機保留，恢復再送，HKT 00:00 清除。細節見 `01`。
- **數據新鮮度：** 只讀伺服器 `data_age_seconds`／`is_stale`／`source_status`。**嚴禁**用手機時鐘推算。咪錶 stale 或 degraded 唔顯示／讀出空位數字。

## 真機調校（只列名；數值只喺源碼）

起始值、真機調校：`LISTEN_OPENING_WAIT_S`、`LISTEN_END_SILENCE_S`、`LISTEN_MAX_UTTERANCE_S`、`STT_RESULT_TIMEOUT_S`、`APPROACH_EXIT_DISTANCE_M`、`APPROACH_EXIT_HOLD_S`、`ENGINE_OFF_DEBOUNCE_MS`、`ENGINE_OFF_MAX_SPEED_KMH`、`VALID_FIX_MAX_ACCURACY_M`。粵語 STT 延遲同 FGS 啟動延遲真機實測，源碼不設預設延遲常數。

## 唔做

唔貼成份常數表；唔改規格行為。改數值必須 Dart `native_config.dart` 同 Kotlin `NativeConfig.kt` 同步（畫面參數只改 `app_config.dart`）。
