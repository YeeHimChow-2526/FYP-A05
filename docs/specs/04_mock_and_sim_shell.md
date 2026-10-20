# 04 — 原生 sim／fixtures／注入事件／測試策略（客戶端工程約定）

來源：Kenny 對話協定＋實作版 Doc **v2.9.1** PART 2 §一（目錄樹 `sim/`）、§十 測試策略、W-A.18／19／20。完整測試步驟見 `docs/testing/01_test_plan.md`（Google Doc「泊車副駕 測試清單 v2.9.1」，ID `1OaMcyeEU8m0HKuhhCLf2DCJX9iY0h1vu-ar7WGdd148`）（Phase A 開發前語音鏈驗證 A–E；Phase B 產品整合 29 項，按一→六次序執行，逐項記錄裝置／Android 版本／語音服務／通過與否）。本檔描述**開發／驗收**用殼；正式生產路徑不含任何 sim 代碼。

## 1. 原則

- 模擬殼位於原生 debug source set `android/app/src/debug/kotlin/com/hkparking/copilot/sim/`，**只限 debug 建置**（Android 只將 `src/debug` 編入 debug 建置，release APK 不含任何 sim 代碼）。
- 介面 `VoiceInput`、`LocationSource`、`ApiTransport` 位於 `src/main`；`src/main` 代碼只引用 `di/Bindings.kt`（debug／release 各一份，見 §4），無執行期旗標。

```
android/app/src/
  main/kotlin/com/hkparking/copilot/                   # 介面 VoiceInput／LocationSource／ApiTransport；只引用 Bindings
  debug/kotlin/com/hkparking/copilot/sim/              # SimVoiceInput、SimLocationSource、FixtureApiTransport、SimEventReceiver、虛擬搖桿
  debug/kotlin/com/hkparking/copilot/di/Bindings.kt    # 綁 sim 實作
  debug/assets/mock/*.json                             # fixtures（§2）
  release/kotlin/com/hkparking/copilot/di/Bindings.kt  # 綁 SttVoiceInput、FusedLocationSource、HttpApiTransport
```
- 生產路徑乾淨：`FloatingBubbleService.kt`／`TripStateMachine.kt` 唔塞 TODO、唔散落 mock 註解。
- 跨層依賴用 Kotlin 介面：`VoiceInput`、`LocationSource`、`ApiTransport`；生產實作綁 SttService／FusedLocation／真 HTTP；sim 實作注入假數據。
- 註解標記 `// MOCK:` 或 `// SIM:` **只准**出現喺 `sim/` 與 `mock/` 之下。
- Flutter 畫面（設定、教學、今日行程）照讀原生狀態；sim 唔喺 Flutter 層另建狀態機。

## 2. `mock/` — JSON fixtures

位置：`android/app/src/debug/assets/mock/*.json`（debug 專用 assets，只編入 debug 建置；release APK 不含任何 mock 檔案）。`FixtureApiTransport` 經 `AssetManager` 以路徑 `mock/<name>.json` 讀取。

```
android/app/src/debug/assets/mock/
  recommend_success_langham.json
  recommend_scarcity_await_reply.json
  recommend_empty_expand.json
  recommend_low_watermark.json    # 有效空位 ≤3；spoken_cue「APM 只剩兩個位，隨時會滿，後備可以去 XX 停車場。」
  recommend_crowd_yellow_flag.json # ≥2 來源：排序封頂＋「近期有司機回報疑似霸位，請留意現場」
  recommend_meter_stale.json      # 咪錶卡 is_stale=true、vacancies=-1、spoken_cue 保守講法
  recommend_source_degraded.json  # source_status=degraded；室內場照常、咪錶不報數字
  recommend_rate_limited_429.json # HTTP 429（Cloud Armor 超限；可選 Retry-After header）
  commit_lock_full_409.json       # 409 LOCK_FULL
  commit_ok.json
  release_parked_confirmed.json   # reason = PARKED_CONFIRMED
  sync_driver_confirmed.json      # DRIVER_CONFIRMED → ratable = true
  trip_report_409_unconfirmed.json
  geocode_ambiguous.json
  geocode_network_fail.json       # 或由 transport 層直接 throw／timeout
  trie_hit_langham.json
  today_trips_sample.json         # 候選「可能已泊入 X」＋待確認
  voice_capability_full.json      # STT＋TTS 已安裝可用 → FULL_VOICE
  voice_capability_announce.json  # STT 不支援、TTS 可用 → ANNOUNCE_MANUAL
  voice_capability_manual.json    # TTS 不可用 → MANUAL_ONLY
  recommend_speech_locale_fallback.json # 不支援之 speech_locale → 回退 zh-HK、speech_locale_fallback = true
```

`ApiTransport` 之 sim 實作 `FixtureApiTransport` 經 `AssetManager` 讀 `mock/<name>.json`，**唔打**雲端；重試／GREY／口播邏輯仍走同一 `TripStateMachine`（用假延遲模擬 2.5s 逾時）。sim 只經 debug 版 `di/Bindings.kt` 綁定啟用（見 §4），無執行期旗標。

## 3. 虛擬搖桿與注入事件（debug 建置）

debug 建置提供虛擬搖桿 overlay／adb broadcast 注入事件（指令格式見 §3.1；接收器見 §3.2）：

| 事件 | 用途 |
|------|------|
| `START_TRIP` / `PAUSE` / `RESUME` | 模擬開程／暫停距離時鐘 |
| `SET_DISTANCE_M` | 注入與目的地距離（觸發 1km／150m／80m） |
| `SET_SPEED_KMH` | 注入車速（出發判定、APPROACH_MUTED、雷達靜音） |
| `GPS_FIX_STALE` | 最後有效 fix 年齡 >5s（地庫斷訊；150m 內 <15km/h 進 APPROACH_MUTED） |
| `ARRIVE_CORE` | 80m 內連續 3s <8km/h → APPROACH_MUTED |
| `LEAVE_TARGET` | 距離 >200m 持續 10s → 退回巡航 |
| `ENGINE_SHUTDOWN` | 熄匙（藍牙 ACL 斷／車充斷電）→ 目標場範圍內即 PARKED_CONFIRMED；範圍外即結束本程 |
| `DRIVER_CONFIRM` | 今日行程一撳確認 → sync/outbox（DRIVER_CONFIRMED） |
| `NETWORK_FAIL` / `NETWORK_OK` | 強制 ApiClient 失敗／恢復 → GREY 分支；驗收待確認離線保留與恢復自動送出 |
| `CLOCK_HKT_MIDNIGHT` | 模擬香港時間 00:00 → 今日行程與待確認清除 |
| `INJECT_UTTERANCE` | 注入 STT 文本（B-1 三支、追問句、「好」expand_scarcity） |
| `NO_MIC` | 模擬全語音模式 mic FGS 未升級 → TTS 句尾「請拍一拍膠囊答我」 |
| `MIC_FAIL` | MIC_READY 失敗 → 只停用語音輸入（膠囊照常；TTS 可用則照常口播） |
| `SET_VOICE_MODE` | 注入能力檢查結果 → `FULL_VOICE`／`ANNOUNCE_MANUAL`／`MANUAL_ONLY` |
| `CAPABILITY_CHECK_TIMEOUT` | 開程靜默重查逾時 → 沿用最近一次已確認結果；FGS_STARTED 照常 |
| `REVOKE_RECORD_AUDIO` | 全語音模式中撤銷 RECORD_AUDIO → 停止聆聽與熱詞、降級 location、按播報＋手動繼續 |
| `REVOKE_REQUIRED` | 撤銷本模式開程必要權限 → 停止 FGS、`PERMISSION_REVOKED` |
| `INJECT_SILENCE` / `INJECT_LONG_UTTERANCE` | 驗證收音計時：開口 3s 無人聲即關閉；講完 0.8s 自動停；單句上限 8s；STT 結果 5s 逾時 |
| `INJECT_CONFIDENCE` | 注入 STT 信心度（含缺失／−1）→ 驗證獨立分支（不當作 0） |
| `INJECT_TTS_ECHO` | TTS 播放期間注入「好」→ 不得視作確認 |
| `FGS_START_TIMEOUT` | FGS_STARTED 未於 2000ms 內回報 → FgsBootstrapActivity finish() 並記錄原因 |
| `BUBBLE_TAP` | 模擬拍膠囊升級收音（全語音）；STT 不可用時顯示手動卡片 |
| `TRIE_MISS_RESOLVE_OK` / `TRIE_MISS_NETWORK_FAIL` / `TRIE_MISS_AMBIGUOUS` | B-2 背景解析三結果 |
| `GPS_BAD_ACCURACY` | 現場即泊精度 >100m 失敗支 |
| `FORCE_STALE` / `FORCE_DEGRADED` | 下一次 recommend 回對應 fixture |
| `FORCE_RATE_LIMIT` | 下一次 recommend 回 429 fixture（`recommend_rate_limited_429.json`）；`value` = 2 → 首次嘗試及單次重試皆回 429 |
| `FORCE_LOCK_FULL` | 下一次 commit 回 409 LOCK_FULL |
| `FORCE_LOW_WATERMARK` / `FORCE_CROWD_FLAG` | 下一次 recommend 回低水位／黃旗 fixture |

### 3.1 注入指令格式（adb broadcast）

- Action：`com.hkparking.copilot.SIM_EVENT`
- 字串 extra `event`：上表事件名（例如 `SET_DISTANCE_M`）。
- 可選 extra `value`：整數用 `--ei`；文字（例如 `INJECT_UTTERANCE` 之句子）用 `--es`。

```bash
adb shell am broadcast -a com.hkparking.copilot.SIM_EVENT -p com.hkparking.copilot --es event SET_DISTANCE_M --ei value 150
adb shell am broadcast -a com.hkparking.copilot.SIM_EVENT -p com.hkparking.copilot --es event INJECT_UTTERANCE --es value "好"
```

### 3.2 接收器 `sim/SimEventReceiver.kt`

- 位於 `android/app/src/debug/kotlin/com/hkparking/copilot/sim/SimEventReceiver.kt`，只喺 debug 建置於執行期以 `ContextCompat.registerReceiver(context, receiver, IntentFilter("com.hkparking.copilot.SIM_EVENT"), ContextCompat.RECEIVER_EXPORTED)` 註冊（minSdk 33 動態註冊接收器須明確指定匯出旗標；adb 屬 App 外來源，故用 `RECEIVER_EXPORTED`）。
- 虛擬搖桿 overlay 喺 App 內部發送同一 action，兩條路徑共用同一處理函式。
- 未知事件或 `value` 格式錯誤 → 記 log 並忽略。

## 4. 介面注入模式（Kotlin）

```kotlin
interface VoiceInput {
    val utterances: Flow<String>
    suspend fun startListening(hotword: Boolean)
    suspend fun stopListening()
}

interface LocationSource {
    val fixes: Flow<LocFix> // lat, lng, accuracyM, speedKmh, timestampMs
}

interface ApiTransport {
    suspend fun post(path: String, bodyJson: String): HttpResult
}

// release：SttVoiceInput + FusedLocationSource + HttpApiTransport
// debug sim/：SimVoiceInput + SimLocationSource（搖桿／GPX 寫入）+ FixtureApiTransport
```

**綁定（`di/Bindings.kt`）**：同名檔 `di/Bindings.kt`（`object Bindings`，兩份 API 相同）分別位於：

- `android/app/src/debug/kotlin/com/hkparking/copilot/di/Bindings.kt` → 綁 `SimVoiceInput`、`SimLocationSource`、`FixtureApiTransport`
- `android/app/src/release/kotlin/com/hkparking/copilot/di/Bindings.kt` → 綁 `SttVoiceInput`、`FusedLocationSource`、`HttpApiTransport`

`src/main` 代碼只引用 `Bindings`，由建置類型決定編入邊一份；無執行期旗標。

## 5. 測試策略（W-A.18）

**Android 模擬器可驗證（大部分流程）**：

- GPX 路線回放（車速、距離、GPS 斷訊）
- 網絡失敗（斷網）
- 宿主機麥克風直通（粵語收音）
- TTS
- Google Maps 分享 Intent（B-2）
- `adb emu power ac off` 模擬車充斷電
- 原生 sim/ 模擬殼（虛擬搖桿、fixtures、注入事件）重播場景

**只限真機驗證**：

- 車載藍牙 ACL 斷開
- OEM 背景殺程（Xiaomi／Samsung）
- 市區高樓 GPS 漂移
- B-1 路徑 1A Hey Google／App Actions（入口優先聲明：「B-1 路徑 1A：Hey Google／App Actions 冷態入口屬實驗性能力，須按指定 intent、語言及裝置驗證通過後方可啟用，不列入本版必要交付或免手操作保證。正文相關冷態流程均以驗證通過為前提。本限制不適用於 B-1 路徑 1B 已開程後的本機語音互動。」；測試清單 Phase B 第 12 項）
- 單一 AudioRecord → 裝置端 STT（測試清單 Phase A；指定裝置／引擎組合，通過只代表該組合可行，並不代表所有 Android 13+ 手機都可用）
- 防自我辨識：副駕 TTS、Google Maps 語音、藍牙切換、來電中斷
- 實際記憶體上限：指定測試機 `adb shell dumpsys meminfo <package>` 量度 PSS，實測後先定上限
- FGS 啟動延遲與粵語 STT 延遲：真機實測（文件唔寫固定數字）

## 6. 驗收清單

- [ ] B-1 (a) 有地名 → 開程
- [ ] B-1 (b) 「附近／呢度／而家位置」→ 現場即泊
- [ ] B-1 (c) 失敗 → 不開 FGS＋TTS 再講一次
- [ ] B-2 Trie Miss 網絡失敗 → GREY＋拍膠囊重試
- [ ] B-2 含糊 → ALERT＋「請拍一拍膠囊答我」
- [ ] 無 mic 澄清口播 → 句尾後綴
- [ ] `MIC_FAIL` → 只停用語音輸入、TTS 照常；`FGS_START_TIMEOUT` → finish()＋記錄原因
- [ ] `SET_VOICE_MODE` 三模式：全語音開 mic；播報＋手動／純手動只 location、無 RECORD_AUDIO 要求；手動卡「下一個／確認／取消」
- [ ] `CAPABILITY_CHECK_TIMEOUT` → 不阻塞 FGS 握手、不彈下載提示
- [ ] `REVOKE_RECORD_AUDIO`／`REVOKE_REQUIRED` → 按模式處理
- [ ] 收音計時：`INJECT_SILENCE`／`INJECT_LONG_UTTERANCE` 符合 3／0.8／8／5 秒
- [ ] `INJECT_CONFIDENCE` 缺失或 −1 → 獨立分支（不當作 0）
- [ ] `INJECT_TTS_ECHO` → TTS 播放期間之「好」不確認
- [ ] 「好」逾時／取消／候選變更後失效
- [ ] 中英雙語導航通知（Maps 中文／英文）→ 指路詞過濾、解析未中不清除終點
- [ ] `await_voice_reply` → expand_scarcity 重發
- [ ] `ARRIVE_CORE` → APPROACH_MUTED（只靜音、行程繼續、鎖未釋放）；`LEAVE_TARGET` → 退回巡航
- [ ] 目標範圍內 `ENGINE_SHUTDOWN` → PARKED_CONFIRMED 候選＋release；離線即放棄 release
- [ ] `DRIVER_CONFIRM` 在線 → ratable = true、開放 3 格反饋；離線 → 本機保留，`NETWORK_OK` 自動送出；`CLOCK_HKT_MIDNIGHT` → 清除
- [ ] 未確認行程 trip-report → 409 靜默
- [ ] `FORCE_LOW_WATERMARK` → 口播只點名一個後備
- [ ] `FORCE_CROWD_FLAG` → 照播黃旗句、選項仍保留
- [ ] `FORCE_STALE`／`FORCE_DEGRADED` → 咪錶卡「咪錶資料暫時未更新」、無數字；室內場照常
- [ ] `FORCE_LOCK_FULL` → 播 409 spoken_cue，膠囊唔轉 ALERT，可講「有冇第二個」
- [ ] `FORCE_RATE_LIMIT` → 等候（`Retry-After` 上限 `RATE_LIMIT_MAX_WAIT_MS`，或 `RATE_LIMIT_RETRY_DELAY_MS`）後只重試一次；`value` 2（第二次亦 429）→ GREY＋拍膠囊重試；全程冇即時重試迴圈

## 7. 五階段客戶端路線圖（摘要）

詳見根目錄 `AGENTS.md`「五階段路線圖」。sim／mock 主要服務早期階段；接真 FGS／藍牙後，sim 保留作回歸。
