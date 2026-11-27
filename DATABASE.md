# Firestore database

This project uses **Cloud Firestore in Native mode** as the operational
database for the Hong Kong parking voice copilot. It is not a relational
database: collections and documents are created when the API writes them, so
there is no SQL schema migration or seed step. The deployment provisions the
database and its TTL policies with Terraform; composite indexes and client
security rules are deployed with Firebase CLI.

For the step-by-step collection creation and verification plan, see
[DATABASE_TABLE_PLAN.md](./DATABASE_TABLE_PLAN.md).
The deployable collection and relationship reference is also available in
[database_table.txt](./database_table.txt).

## Data boundaries

Firestore stores sessions, temporary virtual locks, parking pain-point data,
and parking-perk master data. It does **not** store live carpark vacancies:
those are read from versioned regional snapshots in the private GCS bucket.
The API does not seed placeholder or synthetic parking, POI, or perk records.

The mobile app must not connect to Firestore directly. The checked-in
`firestore.rules` denies all client reads and writes. The Cloud Run API uses
Firestore's server-side client with its runtime service account and IAM.

## Collections

Firestore timestamps below are UTC timestamp values. `expire_at` controls
logical expiration in the API and enables Firestore TTL cleanup. TTL deletion
is asynchronous, so application reads must continue to check expiration.

### `active_sessions/{session_id}`

One document tracks a single app session. Requests create or refresh it.

| Field | Type | Purpose |
|---|---|---|
| `status` | string | `ACTIVE`, `COMMITTED`, or `PARKED_CONFIRMED` |
| `ratable` | boolean | True only after driver confirmation |
| `destination` | map | Optional destination supplied during recommendation |
| `target_carpark_id` | string | Selected carpark after commit or release |
| `last_seen_at` | timestamp | Last API activity for this session |
| `updated_at` | timestamp | Last document update |
| `expire_at` | timestamp | Idle or end-of-day expiration |
| `completed_at` | timestamp | Present for a parked-confirmed trip |
| `trip_report` | map | Optional `trip_timestamp`, `feedback` map, and `submitted_at` |

An uncommitted session expires after 30 minutes idle; a committed session
expires after 3 hours idle. A parked-confirmed session expires at midnight Hong
Kong time on its completion date. Trip reports are stored on this session
document and are accepted only while the session is unexpired, parked-confirmed,
and ratable.

### `virtual_locks/{session_id}`

At most one virtual lock is held for a session.

| Field | Type | Purpose |
|---|---|---|
| `carpark_id` | string | Carpark whose vacancy is provisionally occupied |
| `created_at` | timestamp | Start of the 30-minute lock window |
| `expire_at` | timestamp | TTL cleanup time for the lock |

Candidate counts filter by `carpark_id` and `created_at`; they are not cached.
Commit uses a Firestore transaction to count active locks and write the lock
and session together, preventing two commits from consuming the same remaining
vacancy. Releasing or confirming a parked trip deletes the lock.

### `carpark_pain_points/{carpark_id}`

The parent document stores the current ranking summary. The crowd-report API
currently writes:

| Field | Type | Purpose |
|---|---|---|
| `recent_independent_report_sources` | integer | Independent sources in the current report window |
| `crowd_rank_capped` | boolean | Whether the two-source ranking cap applies |
| `crowd_warning` | boolean | Whether a crowd warning applies |
| `crowd_penalty_points` | integer | Ranking penalty |
| `crowd_window_expires_at` | timestamp | End of the current one-hour window |
| `updated_at` | timestamp | Last summary update |

Crowd reports are stored in the `reports` subcollection:
`carpark_pain_points/{carpark_id}/reports/{session_id}_{time_bucket}`.
The five-minute time bucket is based on server time. Each report stores
`session_id`, `report_type`, an HMAC `source_ip_hash` (never the raw IP),
`ip_bucket`, `created_at`, `expire_at`, and `client_timestamp`. Reports expire
after seven days. The API groups reports for the one-hour ranking window;
crowd reports do not exclude a carpark.

The longer-term chronic-hotspot escalation described in the guidance is not
implemented by this repository yet.

Scoring read interface (`server/app/scoring_records.py`): the recommender reads only a typed
`pain_record` map `{source, provenance, observed_at, expires_at, level: GENERAL|CHRONIC}` (timestamps
with zone). Invalid, expired or absent -> P_i = 0. **No producer exists** (chronic-pain writer
deferred). Crowd fields above are a separate term (C_i).

### `carpark_perks_master/{carpark_id}`

This document is read by the recommendation service as a carpark perk record.
The upstream official and offline source schemas and their reconciliation
worker are not present in the repository, so this project does not define or
seed perk data. **Producer intentionally deferred by product decision (t1230: would need crawling
+ AI review).** The recommender reads only a typed `perk_record` map `{source, provenance,
observed_at, expires_at, confidence (0–1, required, never defaulted), fee_summary?}`. No valid
active record -> M_i = 0, no penalty; the rest of the ranking is unchanged.

### Snapshot source provenance (GCS `regions/*/snapshot.json`)

| Source | Producer | Per-record fields |
|---|---|---|
| TD car-park vacancy (`vacancy_all.json`) | `app/ingest/td_carpark.py` (existing job) | `vacancy_status` COUNT / NO_COUNT / SOURCE_STALE / CLOSED / UNKNOWN, `vacancy_reason`, `source_updated_at` (TD `lastupdate`) |
| TD metered spaces (`parkingspaces.csv` + `occupancystatus.csv`) | `app/ingest/td_meters.py` — pure normaliser, **not scheduled/wired** | N+V counted, NU -> NOT_FOR_USE, no sensor row / other values -> NO_COUNT / UNKNOWN; count is a lower bound |

TD does not document negative counts or a non-zero type-B flag, so they are NO_COUNT (unknown),
never "maintenance". Legacy snapshots without `vacancy_status`: count>=0 -> COUNT, -1 -> UNKNOWN.
Only CLOSED and NOT_FOR_USE exclude a record; unknown records are shown as unconfirmed and can
never be committed.

## Indexes and TTL

`firestore.indexes.json` defines the composite index used by lock counts:

- Collection group `virtual_locks`: `carpark_id` ascending, `created_at`
  ascending.

`firestore.tf` enables TTL on `expire_at` for:

- `active_sessions`
- `virtual_locks`
- the `reports` collection group, including nested crowd-report subcollections

TTL is for physical cleanup, not correctness. The API checks `expire_at` when
reading sessions and checks `created_at` against the active lock window when
counting locks.

## Production protection and recovery

Terraform now defaults to the **live** state (verified 2026-11-11): `delete_protection_state = DELETE_PROTECTION_DISABLED`,
PITR disabled and no daily backup schedule; `deletion_policy = ABANDON` stays (Terraform-only). The protections are
opt-in switches (`firestore_enable_delete_protection`, `firestore_enable_point_in_time_recovery`,
`firestore_enable_daily_backups`; retention 1 to 98 days). Backup and PITR storage and operations may incur additional
charges, so turning them on is a separate team decision after reviewing the plan and project budget.

For recovery, identify the required recovery timestamp or scheduled backup,
restore to a separate Firestore database, validate the restored data and API
behavior, then plan a controlled application cutover. Do not restore over the
live database without a reviewed recovery plan. The API currently uses the
`(default)` database ID, so a separate-database recovery requires an explicit
configuration/cutover change.

## Deploy and verify

Use the project setup and Terraform foundation steps in [README.md](./README.md)
to create the Firestore database. After registering the project with Firebase,
deploy the checked-in rules and indexes:

```sh
firebase deploy --only firestore:rules,firestore:indexes \
  --project YOUR_GCP_PROJECT_ID
```

No Firestore collections will appear until the API writes data. The API runtime
service account requires `roles/datastore.user` (provisioned in
`cloud_run.tf`). Local development uses Application Default Credentials and
the configured `PROJECT_ID`; do not put credentials in the repository.

## Contract v2 鎖（review/cloud-destination-release，2026-11-08）
- `sessions/{id}`：新增 `destination_epoch`、`lock_revision`（單調）、`contract_version`、`status`（ACTIVE／USER_ABORTED／PARKED_CONFIRMED tombstone）、`idle_expire_at`（邏輯閒置過期）、`expire_at`（保留期，`session_retention_hours`=24，須覆蓋鎖＋閒置＋重試窗口）、idempotency 記錄。
- 鎖文件：`destination_epoch`、`lock_revision`、`lock_id`。DESTINATION_CHANGED 只刪 epoch 較舊嘅鎖；終結釋放寫 tombstone，touch 唔可以重開。
- 所有 touch/commit/release/confirm_parked 喺 transaction 內；過期 session 唔再刪。（Phase 3c 起容量改由 `carpark_capacity` 文件把關，唔再靠 aggregation query。）

### `carpark_capacity/{carpark_id}`（Phase 3c，權威容量帳）

| 欄位 | 用途 | 單位／型別 | 限制 |
|---|---|---|---|
| `leases.{session_id}.lock_revision` | 對應 `virtual_locks/{session}` | int | 只會移除 revision 相同嘅 lease |
| `leases.{session_id}.expire_at` | lease 到期（=鎖 created_at + 30 分） | timestamp | 過期即唔計，下次寫入時清走（冇背景 job） |
| `leases.{session_id}.committed_at` | 最後 commit 時間 | timestamp | confirm_parked 冇 revision 時比較用 |
| `lease_count` | 診斷 | int | ≤ `capacity_max_leases`（預設 3000）。文件大小／索引項未實測；部署前要量度最大車場嘅實際 bytes 再定上限（Firestore 文件上限 1 MiB） |
| `bootstrapped`／`bootstrapped_from` | 遷移狀態 | bool／`EMPTY`\|`LEGACY_LOCKS` | 冇文件 → `CAPACITY_NOT_READY`，直至 `capacity_bootstrap_enabled=true` |

- 可用 = 最後已知空位（stale 都照用，標 stale）− 有效 lease（唔計自己）；從未知 → `CAPACITY_UNKNOWN`。
- 爭用：每車場一份文件，所有 commit／release 喺同一文件上序列化；Google 文件對單文件持續寫入有建議上限（唔係硬性保證值），實際吞吐未量度。熱門車場高峰可能出 503 `CONTENTION`（伺服器重試 10 次 × 3 輪後；App 再以同一 body 有限重試）。
- 成本（每次操作文件讀／寫，未計 index）：commit 3R+3W（換車場 4R+4W；首次 bootstrap 加 legacy query）；release／confirm 3R+3W；recommend 每候選 1R（取代 aggregation count）。冇實測金額。
- 遷移：先部署、確認舊 revision 0 流量（舊版唔寫容量帳），再開 `capacity_bootstrap_enabled`；bootstrap 喺 transaction 內讀未過期 legacy 鎖一次。
- `active_sessions` 新欄位：`session_started_at`、`created_at`、`legacy_created`；`session_retention_hours`(24) 必須 > `session_create_window_hours`(12) + skew。

### Phase 3d：泊車確認事件（`/v1/sync/outbox`）
| 欄位 | 用途 | 單位 | 限制 |
|---|---|---|---|
| `contract_version` | 2＝新事件（建立時鎖低身份） | int | 只可 2 或省略；有 `lock_revision`／`destination_epoch` 就必須 2 |
| `destination_epoch` | 事件所屬目的地世代 | int | v2 必填；< session epoch → 409 `STALE_EPOCH` |
| `lock_revision` | 事件建立時持有嘅鎖 | int ≥1 | v2：有鎖就要完全相同（省略＝當時冇鎖），否則 409 `STALE_REVISION`；冇時間 fallback |
- 舊（冇版本）事件：一律唔准改 v2 session（409 `REVISION_REQUIRED`）；只喺舊 App 建立嘅 v1 session 保留 `committed_at ≤ timestamp + 300s` 推斷（**唔保證**，只限舊流程）。
- 部署狀態：**未部署**。預設 `capacity_bootstrap_enabled=false`（冇容量帳嘅車場 commit 會 409 `CAPACITY_NOT_READY`）、`legacy_session_create_enabled=false`（舊 App 開唔到新 session）。切換步驟只係文件，未執行。

## Shared on-demand snapshot cache (Phase 4a, `review/cloud-destination-release`, not deployed)

`server/app/shared_cache.py` implements plan §5 option B: **one** `manifest.json` holds the lease
(`owner_token`, `lease_until`), `checked_at`, per-source state (`sources.td_carpark`,
`sources.td_meters`) and the region pointers. Every writer — API on-demand refresh
(`GcsSnapshotSource.maybe_refresh`), the ingest Job/CLI (`td_carpark.run_once`) and
`scripts/upload_demo_seed.sh` (now `--if-generation-match`) — uses generation preconditions.
Snapshots are immutable objects `regions/{id}/snapshots/{source_ts}-{hash}-{token}.json`.

| Setting | Default | Unit / constraint | Purpose |
|---|---|---|---|
| `SNAPSHOT_ON_DEMAND_REFRESH_ENABLED` | false | bool | API refreshes on request; needs bucket write (below) |
| `SNAPSHOT_REFRESH_MIN_INTERVAL_SECONDS` | 60 | s, ≥10 | shared interval between successful upstream checks |
| `SNAPSHOT_LEASE_SECONDS` | 45 | s; > fetches per refresh (≤4 × 8 s) | lease length (Job uses 180) |
| `SNAPSHOT_FETCH_TIMEOUT_SECONDS` | 8 | s, ≤20; 1 attempt on-demand | per upstream HTTP call |
| `SNAPSHOT_STATIC_TTL_SECONDS` | 21600 | s, ≥300 | re-check of static metadata (ETag) |
| `SNAPSHOT_FAILURE_BACKOFF_BASE/MAX_SECONDS` | 30 / 600 | s | doubling backoff, shared via manifest |
| `SNAPSHOT_REFRESH_WAIT_SECONDS` | 2 | s, ≤10 | max wait of a request that already has data |
| `METER_REFRESH_ENABLED` | false | bool | TD meter CSVs, separate source state/region |

Guarantees (mock-tested only, `tests/test_shared_cache.py`): publish is `ifGenerationMatch=G1`
exactly; any 412 discards the run (no rebase/retry). Once another writer advances the manifest
(e.g. takes over an expired lease) the old leader cannot publish. **Not** guaranteed: a leader
whose lease expired with no takeover may still publish late. `source_snapshot_ts` never goes
backwards; 304/same-hash only move `checked_at`; failures never move `checked_at` or source time,
and source time is the publisher time (Last-Modified, else newest per-record timestamp), never the
fetch time. Unreadable manifest -> refresh refuses to write; empty first bootstrap -> 503 truthfully.
Cloud Run may throttle CPU after a response, so a refresh outliving its request may stall; its
lease then expires and the next request takes over.

Rollout needs (not executed; live IAM unknown): the API service account currently has
`roles/storage.objectViewer` in `cloud_run.tf`; on-demand refresh needs create + overwrite on the
bucket. Review-only proposal (not applied):

```hcl
resource "google_storage_bucket_iam_member" "snapshot_writer" {
  bucket = google_storage_bucket.state_snapshots.name
  role   = "roles/storage.objectUser" # or a custom role: objects.get/create/delete(overwrite)
  member = "serviceAccount:${google_service_account.app_sa.email}"
}
# plus a lifecycle rule for orphaned immutable snapshots (fenced runs), e.g. age >= 7 days with
# matches_prefix "regions/HK_TD/snapshots/" — NOTE: would also delete the live pointer if the
# feed published nothing for 7 days; pick the age with that in mind.
```
Old `state/td_carpark.json` is no longer written or read; the first new run bootstraps state
into the manifest. Meters: `td_meters.py` fits the same CAS path behind the flag (publisher time =
Last-Modified else newest `OccupancyDateChanged`, a lower bound); operating hours are still not
evaluated (`is_open` assumed) — known gap. No new Job or scheduler was added.

## History cold path (Phase 4b, local code only — NOT enabled, nothing is collecting)

`server/app/history.py` (`python -m app.history --bucket …`) reads the **published** manifest and
writes one immutable NDJSON object per HKT slot and region with `ifGenerationMatch=0`:
`history/grid_occupancy/dt=YYYYMMDD/hour=HH/region={id}/slot=HHMM.ndjson` (demo regions only with
`--include-demo`, under `history/demo/…`). It never refreshes upstream, never writes the
manifest, and recommendation never reads it; a History failure cannot affect the live path.

| Parameter (code: `app/history.py`) | Value | Unit / constraint | Purpose |
|---|---|---|---|
| `HISTORY_WINDOWS` peak | 07:30–09:30, 17:30–20:00 | HKT, half-open; 5 min | dense sampling |
| `HISTORY_WINDOWS` night | 01:00–06:00 | HKT, half-open; 60 min | sparse sampling |
| `HISTORY_DEFAULT_INTERVAL_MINUTES` | 15 | min; slots anchored at 00:00 HKT | all other times |
| `WRITE_ATTEMPTS` | 3 | count | bounded retries for non-412 write errors |

Row fields: `slot_start`, `snapshot_date` (HKT), `source_snapshot_ts` (publisher time of the
snapshot), `record_source_updated_at` + `record_source_age_seconds` (per row), `checked_at`
(last successful upstream check), `processed_at`, `captured_at`; `vacant_count` is NULL unless the
typed status is COUNT (deviation from §13 "NOT NULL": no invented zeros). The same source data
seen in a later slot is recorded with its real age, not as a refresh. Only the current slot is
captured — missed slots are never back-filled — and without an external trigger nothing is
recorded. Per-slot objects replace §12.3's single hourly file so writes stay immutable.

Review-only, not applied / not created (new services need Kenny's confirmation; cost not run):

```hcl
# Cloud Run Job running `python -m app.history` (same image), triggered every 5 min; runs that
# land in a 15/60-minute slot already recorded are 412 no-ops.
# google_cloud_scheduler_job "history_capture" { schedule = "*/5 * * * *", time_zone = "Asia/Hong_Kong", paused = true }
# Lifecycle, history/ prefix only (never regions/ or manifest.json):
# rule { condition { age = 30,  matches_prefix = ["history/"] } action { type = "SetStorageClass" storage_class = "NEARLINE" } }
# rule { condition { age = 90,  matches_prefix = ["history/"] } action { type = "SetStorageClass" storage_class = "COLDLINE" } }
# rule { condition { age = 365, matches_prefix = ["history/"] } action { type = "SetStorageClass" storage_class = "ARCHIVE" } }
# rule { condition { age = 730, matches_prefix = ["history/"] } action { type = "Delete" } }
```
The BigQuery daily load (§13 `history_loader.py`) is not implemented in this phase.

## History → BigQuery load + readable layer (local code only; not enabled)

Code: `server/app/history_bq.py` (`FIELDS` = schema/中文名/單位 source of truth; `DICTIONARY` = code meanings + provenance).
- `python -m app.history_bq load --date YYYY-MM-DD` = PLAN ONLY (lists GCS objects, prints destination/job id). `--execute --location <dataset location>` submits a load job; never run yet.
- Two steps per HKT date, no partition replacement: (1) load the listed slot objects into staging `stg_grid_occupancy_history_{date}_{sha12(objects@generations)}` (expires after `STAGING_EXPIRATION_HOURS`=168); (2) one transaction: `DELETE … WHERE FALSE` (BigQuery's documented table lock) + insert-only MERGE on `KEY`=(region, grid_id, slot_start, record_type). The target is never truncated/updated/deleted, so a delayed old batch cannot undo a newer one; a concurrent publisher aborts and is retried; rerun/late files only add missing keys. Relies on History objects being write-once (generation 0). Job ids `stg_|merge_{table}_{date}_{sha12}_{1..MAX_ATTEMPTS=3}`: DONE/RUNNING read back, never resubmitted; lost ack → read back same id. Not claimed: exactly-once jobs, or a date being complete (unlisted files appear on a later rerun). Demo → `grid_occupancy_history_demo` only with `--demo`.
- Machine table uses ASCII column names + Chinese descriptions. Readable route = the CSV export; the Chinese-alias view is optional (aliases avoid brackets, which BigQuery flexible column names reject per https://cloud.google.com/bigquery/docs/schemas#flexible-column-names). Lock/MERGE semantics per https://cloud.google.com/bigquery/docs/transactions and …/data-manipulation-language. All SQL only parsed locally (sqlglot); never run on BigQuery.
- `vacant_count` NULL = unknown (never 0); no capacity/occupancy % (no reliable denominator).
- `view-sql --project P` prints table DDL + optional readable view.
- `export-csv --out f.csv --ndjson …` (or `--bucket --date`, read-only) → UTF-8-BOM CSV with bilingual headers + `f_字典.csv`.
- Provenance: N/NU/V/O from TD metered-parking data spec; TD car park `vacancy_type` handling is project interpretation (no official doc for -1 / B non-zero).
- Rollout remaining (review, not done): create dataset+table, grant job SA `roles/bigquery.jobUser` + dataset `dataEditor` + bucket `objectViewer`, add `google-cloud-bigquery` image dep (added to requirements.txt), a daily trigger (opt-in), run view SQL.
