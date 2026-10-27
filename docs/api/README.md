# docs/api — 客戶端 REST 範例（Step 3.5／SC-018）

呢個目錄係**畀雲端組同 stub server 用嘅範例**，唔係 App 讀嘅 fixture。
App debug 版 fixture 仍然喺 `android/app/src/debug/assets/mock/`（Step22b 鎖死 14 個檔，所以範例唔放嗰度）。

- 欄位以 `docs/specs/03_api_and_local_trie.md` §1–§5 同 `net/ApiClient.kt`、`net/ApiResponses.kt` 為準。
- Response 範例盡量直接抄 debug mock；mock 冇嘅，就用 `FixtureApiTransport` 嘅預設 body `{"status":"OK"}`，並標明 **OPEN QUESTION**。
- 機讀版本：`openapi.yaml`（最少限度，只寫 App 真係用到嘅嘢）。

## 共通

| 項目 | 值 |
|------|----|
| Method | 全部 `POST` |
| Header | `X-Internal-API-Key: <key>`、`Content-Type: application/json`、`Accept: application/json` |
| Base URL | 建置設定 `HKP_API_BASE_URL`（必須 `https://`；debug 只准 `http://localhost`／`127.0.0.1`／`10.0.2.2`） |
| 成功 | App 視 **HTTP 200..299** 為成功（`ApiClient.isOk`，RT-7／SC-018）。recommend-and-route 成功仍須可解析 JSON body。**請伺服器優選回 200**（201／204 亦可）。 |
| 逾時 | 每次請求總時限 `API_TIMEOUT_MS` = 2500 ms |
| 重定向 | App 唔跟 3xx，當失敗 |

## 端點同範例檔

| 端點 | Request | Response | App 讀咩 |
|------|---------|----------|----------|
| `/v1/recommend-and-route` | `recommend-and-route.request.json` | `recommend-and-route.response.200.json`（= `recommend_success_langham.json`）；`recommend-and-route.response.429.json`（= `recommend_rate_limited_429.json`） | 完整 body（見 03 §2.1） |
| `/v1/session/commit` | `session-commit.request.json` | `session-commit.response.200.json`（**OPEN QUESTION**：spec 冇寫 200 body）；`session-commit.response.409.json`（= `commit_lock_full_409.json`） | 200 只睇 status code；409 讀頂層 `spoken_cue` |
| `/v1/session/release` | `session-release.request.json` | `session-release.response.200.json`（**OPEN QUESTION**） | 只睇 status code |
| `/v1/sync/outbox` | `sync-outbox.request.json` | `sync-outbox.response.200.json`（= `sync_driver_confirmed.json`） | 只睇 status code |
| `/v1/feedback/crowd-report` | `crowd-report.request.json` | `crowd-report.response.200.json`（**OPEN QUESTION**） | 只睇 status code |
| `/v1/feedback/trip-report` | `trip-report.request.json` | `trip-report.response.200.json`（= `sync_driver_confirmed.json`，fixture 共用）；`trip-report.response.409.json`（= `trip_report_409_unconfirmed.json`） | 只睇 status code；404／409 靜默 |

## 備註

- `timestamp`／`client_timestamp`／`trip_timestamp`：Unix **秒**（整數）。
- recommend 回應嘅 `source_snapshot_ts`／`processed_at`／`server_now`：spec 03 寫 int（Unix 秒），但 mock 同 App parser 用 ISO-8601 字串（`+08:00`）。App 收到 int 唔會死，只係當空字串。**OPEN QUESTION**。
- `crowd-report.request.json` 嘅 `report_type`：global §10.7 只有一個例子 `BLOCKED_OCCUPIED`，完整值集未定義；App 而家亦冇任何地方會送 crowd-report；範例用 `OPEN_QUESTION_REPORT_TYPE` 佔位。**OPEN QUESTION**。
- `trip-report` 嘅 `rating_tag` 取 `GOOD`／`OK`／`BAD`，`perk_status_tag` 取 `VALID`／`INVALID`／`NONE`（SC-017）；`photo_url` App 本版唔會送。
- 範例 `session_id` 只係示範字串。
