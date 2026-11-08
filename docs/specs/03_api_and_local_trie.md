# 03 — REST／本地字典樹／重試／在線即送與待確認本機保留（客戶端）

來源：實作版 Doc **v2.9.1** PART 1 §一（重試）／§三（字典樹）／§四（稀缺、低水位、眾包客戶端處理）、PART 2 §四。全部 REST 呼叫由原生 `net/ApiClient.kt` 發出（行程期間不經 Flutter）。本檔**不含**打分公式數學、BQ／ETL／Terraform／Firestore schema 細節。

## 1. 通用 Header

- `X-Internal-API-Key: <INTERNAL_API_KEY>`（APK 內打包，只作基本識別）
  - Base URL／key 由建置設定 `HKP_API_BASE_URL`／`HKP_API_KEY` 提供（`local.properties`／`-P`／環境變數，絕不入庫）；只准 `https://`（debug 另准 localhost／127.0.0.1／10.0.2.2 嘅 http）；URL 或 key 空 → 當離線；唔跟 redirect；每次請求總時限 `API_TIMEOUT_MS`（SC-018）。範例見 `docs/api/`。
- `Content-Type: application/json`
- 入口限流由 Cloud Armor 處理；客戶端遇逾時／5xx 走分級重試（§4）。
- **429（Cloud Armor 超限）單獨處理，唔即時重試、唔計入分級重試**：回應帶 `Retry-After`（秒）即等候該秒數（上限 `RATE_LIMIT_MAX_WAIT_MS`）；冇 `Retry-After` 即等候 `RATE_LIMIT_RETRY_DELAY_MS`；之後**只重試一次**。該次仍失敗（再 429、逾時或其他錯誤）→ 膠囊 `GREY_BREATHING`，同網絡失敗分支，等司機拍膠囊重試；口播沿用 GREY 分支，唔另加句子。

## 2. 客戶端需要嘅端點

### 2.1 `POST /v1/recommend-and-route`（唯讀，可安全重發）

**Request（客戶端送出）**

| 欄位 | 型別 | 說明 |
|------|------|------|
| `session_id` | string | 本程 id |
| `origin_lat` / `origin_lng` | double | 當前定位 |
| `dest_lat` / `dest_lng` | double | 目的地 |
| `dest_name` | string | 顯示／口播用地名 |
| `preference` | string | `INDOOR_ONLY`／`SMART_HYBRID`／`METERS_FOCUS` |
| `expand_scarcity` | bool | 可選，預設 false |
| `speech_locale` | string | 可選，預設 `"zh-HK"`（本產品 zh-HK＝粵語）；App 本版只送 `zh-HK`（`NativeConfig.SPEECH_LOCALE_DEFAULT`） |

**Response（UI／TTS 用到嘅欄位）**

| 欄位 | 說明 |
|------|------|
| `status` | string；如 `SUCCESS` |
| `source_status` | string；`ok`／`degraded`（來源完整性檢查失敗時為 `degraded`，伺服器沿用上一份快照） |
| `source_snapshot_ts` | int（Unix 秒）；政府數據實際更新時間 |
| `processed_at` | int（Unix 秒）；系統處理寫入時間 |
| `server_now` | int（Unix 秒）；伺服器回應時間 |
| `data_age_seconds` | int；`server_now − source_snapshot_ts`，由伺服器計算，客戶端**唔用本機時鐘**推算 |
| `cards[]` | 已排序；客戶端只口播首選，追問逐一出卡 |
| `cards[].carpark_id` | commit／release 用 |
| `cards[].name` | 顯示 |
| `cards[].type` | 如 `INDOOR` |
| `cards[].vacancies` | int；**-1＝未知**，禁止當 null；咪錶 `is_stale` 或 `source_status=degraded` 時一律 -1 |
| `cards[].distance_meters` | int 公尺 |
| `cards[].fee_summary` | 詳情卡 |
| `cards[].spoken_cue` | string；粵語口播短句（首選直接播）；咪錶資料過期時為保守講法「附近可能有咪錶，資料暫時未更新」 |
| `cards[].is_stale` | bool；`data_age_seconds > 600` 為 true；咪錶卡顯示「咪錶資料暫時未更新」，唔顯示數字 |
| `await_voice_reply` | bool；稀缺已滿時 true |
| `speech_locale` | string；實際使用之語言（`spoken_cue` 按該語言模板輸出，本版只有 zh-HK） |
| `speech_locale_fallback` | bool；送出不支援之值時伺服器明確回退 zh-HK 並置 true |

**客戶端解析（SC-009）**

1. 回應用完整 JSON 解析（共用 net/ApiResponses.kt，debug fixture 同 release 用同一套），未知欄位一律忽略。
2. recommend 回 2xx，但 body 唔係合法 JSON 物件、缺非空 `status`、`cards` 唔係陣列或者有非物件元素 → 當網絡失敗處理（分級重試再 GREY），禁止當 SUCCESS。
3. 缺省值：`await_voice_reply` false、`source_status` "ok"、`vacancies` -1（禁止當 0）、`is_stale` false、`distance_meters` 0；卡嘅 `spoken_cue` 係空就用頂層 `spoken_cue`。
4. 429 嘅等候秒數先用 `Retry-After` header，冇先用 body 嘅 `retry_after_seconds`；409 只讀頂層 `spoken_cue`。

稀缺已滿：仍 `SUCCESS`，附 spoken_cue（擴大至約 3km 詢問）＋`await_voice_reply=true`。

### 2.2 `POST /v1/session/commit`

`{ "session_id", "target_carpark_id" }` — 司機確認首選／拍卡後。

- **200**：後端以 Firestore transaction 讀後寫建立虛擬鎖；本程轉 COMMITTED。
- **409 `{"status":"LOCK_FULL","spoken_cue":"…"}`**：有效剩餘已滿；客戶端照播 spoken_cue，膠囊維持現狀，司機可講「有冇第二個」另選（重發 recommend）。
- 虛擬鎖只作分流，**並非車位預約**；UI／口播絕不講成「已幫你留位」。

**客戶端導航（SC-010）**

1. 「好／去呢個」只喺 `RECOMMENDATION_CONFIRM` 窗有效；等首選 TTS `onFinished(true)` 且 `isMicActive` 先開窗；開窗前 `speechHeard=false`。
2. commit 200 → `NAV_TO_GATE`＋`NavigationPort`；URI `google.navigation:q=<URLEncoder UTF-8>&mode=d`，package `com.google.android.apps.maps`，`FLAG_ACTIVITY_NEW_TASK`。
3. 409 → 口播 spoken_cue；唔 NAV／唔 Maps；記錄已滿 id；其後「有冇第二個」重查並過濾；空則「暫時冇其他選擇」。
4. FloatingCardView／手動掣／本地切卡 → 2.4；R11 唔屬本步。

### 2.3 `POST /v1/session/release`

`{ "session_id", "target_carpark_id", "reason": "PARKED_CONFIRMED" }` — 泊妥確認（目標場 150m 內或地庫斷訊＋熄匙）時，原生於寫入今日行程候選後在線即送；離線即放棄（鎖至多保留至 30 分鐘 `expire_at`）。伺服器刪除 `virtual_locks/{session_id}`，並將 session 標記 `PARKED_CONFIRMED`、`ratable = false`（待司機確認）。

### 2.4 `POST /v1/sync/outbox`

`{ "session_id", "event_type": "DRIVER_CONFIRMED", "timestamp" }` — 司機於今日行程一撳確認候選「可能已泊入 X」（`confirmParkedCandidate`）後送出；伺服器覆蓋狀態為 `PARKED_CONFIRMED` 並設 **`ratable = true`**，同時刪除鎖文件（天然等冪）。確認時如離線：確認保留於本機 SharedPreferences，網絡恢復即自動送出；香港時間 00:00 與今日行程一併清除，之後一律不送。

### 2.5 `POST /v1/feedback/crowd-report`／`trip-report`

- `crowd-report`：必填 `session_id`。伺服器規則（客戶端只需照播 `spoken_cue`／顯示標籤）：
  - 單人回報扣 45 分（−45）＋黃色標籤「⚠️ 近期有司機回報疑似霸位」。
  - 1 小時窗口內先以來源 IP 做 5 分鐘群組，再以 distinct `session_id` 去重；累積 ≥2 個獨立來源 → 該選項排序封頂（不得排在首個未被回報之選項之前）＋口播黃旗「近期有司機回報疑似霸位，請留意現場」。
  - 眾包回報一律不作排除；排除只來自官方數據。
  - 客戶端**不收集、不送出**裝置 ID；Play Integrity 屬後期階段（只用於 crowd-report）。
- `trip-report`：只接受經司機確認之行程（`PARKED_CONFIRMED` 且 `ratable = true`）；未知 session → 404；session 存在但未確認（`ratable = false`，包括候選未確認、APPROACH_MUTED 或未到場）→ 409，客戶端靜默處理、不口播。
- 今日行程清單讀本機 `TodayTripStore`（SharedPreferences）；後端**不設**行程查詢端點。

## 3. `session_id`（客戶端契約）

- 六種起步任一成功開始（及現場即泊）即由原生建立；其後 recommend／commit／release／sync／crowd-report／trip-report **皆帶此 id**。
- B-1 目的地解析失敗或現場即泊定位失敗 → **不**建立。
- 每次開程**新** id；不設心跳、不重連同一 id；銷毀 FGS 時結束會話。

## 4. ApiClient.kt 重試（`NativeConfig`）

公式：**3 × 2.5s 逾時 + 2 × 1s 間隔 ≈ 9.5s**（`API_MAX_ATTEMPTS`／`API_TIMEOUT_MS`／`API_RETRY_INTERVAL_MS`；口播耗時另計）。

| 場景 | 行為 |
|------|------|
| 1km 背景自動查詢 | 靜默重試；`GREY_BREATHING`；**不口播**、唔用 ALERT；3 次皆敗則停自動重試，等下一觸發／司機要求 |
| 司機主動要求泊車 | 逾時口播「網絡有啲慢，我再試吓」＋ GREY；仍敗「網絡唔順，遲啲再試」（一次）；維持 GREY 至成功／結束 |
| B-2 背景解析 | 同一公式；網絡敗 → GREY＋上述失敗句；拍膠囊重試 |
| 任何請求遇 429 | 單獨處理（見 §1）：等候 `Retry-After`（上限 `RATE_LIMIT_MAX_WAIT_MS`）或 `RATE_LIMIT_RETRY_DELAY_MS` 後只重試一次；仍敗 → GREY，拍膠囊重試 |

成功則轉 `NEARBY_READY` pip。**絕不盲猜**無空位車場。

## 5. 在線即送／待確認本機保留（MVP）

- release（`PARKED_CONFIRMED`）：當下在線即 POST；離線即放棄（唔落 DataStore／Room／WorkManager／SQLite 持久隊列）。
- **唯一本機保留項**：今日行程「待確認」——司機一撳確認時離線，保留於本機，網絡恢復自動送出 `/v1/sync/outbox`；香港時間 00:00 清除。
- SharedPreferences **只**用於：偏好、本程熱詞開關、今日行程 `TodayTripStore`（PARKED_CONFIRMED 時寫入 session_id／車場名／時間，顯示「可能已泊入 X」；連同待確認狀態；**只保存當日 HKT**，00:00 清除）。

## 6. 本地字典樹（本機字典樹由文字查出坐標 <150ms）

- `android/app/src/main/kotlin/com/hkparking/copilot/poi/LocalPoiTrie.kt`＋`assets/hk_poi_master.json`
- 流程：原生粵語 STT 先轉出文字（延遲真機實測，唔寫固定數字）→ 本機字典樹由文字查出坐標 <150ms（指標只量度查表一步）。
- 地標中英別名共用同一 POI ID（一套地標庫）；STT 文字先經語言層對應指令碼（見 `01` §5.5）。
- 語音／分享標題離線比對；命中即鎖定；Miss → 雲端 Geocoding（語音路徑即時；B-2 背景）。
- 隱式回顯：「收到，為你鎖定【大區＋特徵】[全稱]，搜尋緊周邊車位。」
- 含糊／同名 → 純語音反問＋`ALERT_BREATHING`（全語音模式 mic 未升級則句尾「請拍一拍膠囊答我」；STT 不可用時不作此要求，地名留待泊好車後於 App 輸入）。

## 7. `spoken_cue` 客戶端處理（稀缺／低水位／黃旗）

1. 收到 `await_voice_reply=true` → TTS `spoken_cue`（必要時加「請拍一拍膠囊答我」）。
2. 按 `01` §5.4「收音計時」聆聽（講完自動停；STT 不可用時不聆聽，按無回應處理）：
   - 「好」（只綁定本條問題）→ **同一** `session_id` 重發 recommend，`expand_scarcity=true`
   - 「唔使／等等」或超時 → 維持首選已滿；**唔**自動彈卡、**唔**設進度倒數
3. 低水位（私家車有效空位 ≤ `LOW_WATERMARK_VACANCIES = 3`，伺服器判定）：照播例如「APM 只剩兩個位，隨時會滿，後備可以去 XX 停車場。」——只點名一個後備；資料過期／degraded 時伺服器唔讀數字。
4. 眾包黃旗：照播伺服器附加之「近期有司機回報疑似霸位，請留意現場」；客戶端唔自行排除或重排選項。
5. 畫面保持語音優先；唔擅自改 Maps 終點。

## 8. 無 Firestore SDK

客戶端**不**嵌入 Firestore SDK；全部經 Cloud Run REST（原生 `ApiClient.kt`）。Security Rules 禁止前端直連（雲端責任；App 只需遵守「只打 REST」）。

## Phase 3b 補充：Contract v2（改目的地釋放舊鎖）— 2026-11-08

- `recommend-and-route` / `session/commit` 帶 `destination_epoch`（int）；commit 再帶 `idempotency_key`（`c{epoch}-{carpark}`，同 key 同內容＝重播，同 key 異內容＝409 `IDEMPOTENCY_KEY_REUSED`）。
- commit 成功回 `contract_version:2`、`destination_epoch`、`lock_revision`（session 文件內單調遞增，唔喺可刪嘅 lock 文件）、`lock_id`。冇 `lock_revision`＝舊後端 → App 顯示「伺服器唔支援即時釋放」，唔假裝成功。
- `session/release` 新增 `reason`：`DESTINATION_CHANGED`（非終結，session 保持 ACTIVE，需 `new_destination_epoch`，只刪 epoch 較舊嘅鎖；有 `lock_revision` 就要相符）、`USER_ABORTED`／`PARKED_CONFIRMED`（終結 tombstone，之後 touch/commit 一律 409 `SESSION_CLOSED`）。v2 鎖做終結釋放要帶 `lock_revision`（否則 409 `REVISION_REQUIRED`）。
- 409 `{"status": code}`：`STALE_EPOCH`、`SESSION_CLOSED`、`SESSION_EXPIRED`、`EPOCH_REQUIRED`、`REVISION_REQUIRED`、`LOCK_FULL`；404 `SESSION_NOT_FOUND`（commit 唔會自動開 session）。
- 冇版本（v1）請求：只可以操作 v1 session／鎖；唔可以刪 v2 鎖或者繞過 epoch 檢查。

### Phase 3c 參數表（2026-11-08，未部署）

| 欄位 | 端點 | 用途 | 單位 | 限制 |
|---|---|---|---|---|
| `session_started_at` | recommend-and-route | 證明 session id 係近期新開（防 TTL 後復活） | Unix 秒 | 1.5e9–4.1e9；開新 session 必填；`now - x ≤ session_create_window_hours`（12h），未來 ≤ `client_clock_skew_seconds`（300s）；同一 id 再送唔同值 → 409 `SESSION_ID_REUSED` |
| `destination_epoch` | recommend／commit | 目的地世代 | 整數 | 0–2^31-1；只可向前 |
| `idempotency_key` | commit | 重試冪等 | 字串 | 8–128，`[A-Za-z0-9_.:-]`，要同 epoch 一齊 |
| `lock_revision` | release／sync/outbox | 精確對鎖 | 整數 | ≥1；sync 可選：冇就只刪 `committed_at ≤ timestamp + skew` 嘅鎖，否則 409 `STALE_PARKED` |
| `new_destination_epoch` | release（DESTINATION_CHANGED） | 新目的地世代 | 整數 | 必填於 DESTINATION_CHANGED，其他 reason 唔准 |

新增錯誤：409 `SESSION_START_REQUIRED`／`SESSION_START_EXPIRED`／`SESSION_START_INVALID`／`SESSION_ID_REUSED`／`CAPACITY_UNKNOWN`（從未知空位）／`CAPACITY_NOT_READY`（容量帳未 bootstrap，fail closed）／`CAPACITY_LEDGER_FULL`／`STALE_PARKED`；503 `CONTENTION`（同一車場文件爭用重試用盡，可重試）。
App（Phase 3d）用 `lib/core/net/server_issue.dart` 分類：typed 回應唔變灰、唔講網絡斷，狀態列顯示用戶動作；容量類（`CAPACITY_*`）同 LOCK_FULL 一樣剔走該卡等用戶揀；只有 503 `CONTENTION` 喺 `API_MAX_ATTEMPTS` 內用同一 body（同一 idempotency_key）重試；唔自動換目的地／開新 session。

### Phase 3d：`/v1/sync/outbox` 泊車確認（v2）
| 欄位 | 用途 | 單位 | 限制 |
|---|---|---|---|
| `contract_version` | 2＝新事件 | int | 有下面兩欄就必須係 2 |
| `destination_epoch` | engine-off 嗰刻嘅目的地世代 | int | v2 必填 |
| `lock_revision` | engine-off 嗰刻持有嘅鎖 | int ≥1 | 冇鎖就唔送；伺服器要求完全相同，冇時間 fallback |
身份喺 `TodayTripRecord`（`contractVersion`／`lockRevision`／`destinationEpoch`）建立時寫入 prefs，重播原封不動；4xx typed 拒絕 → `syncRejected`，唔再重試；503／網絡 → 保留待重試。
