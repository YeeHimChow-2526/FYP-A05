# FYP-A05

Cloud infrastructure and the core FastAPI service for the Hong Kong parking
voice copilot.

## Backend API

The API source is in [`server/`](./server/). It implements:

- `POST /v1/recommend-and-route`
- `POST /v1/session/commit`
- `POST /v1/session/release`
- `POST /v1/sync/outbox`
- `POST /v1/feedback/crowd-report`
- `POST /v1/feedback/trip-report`

All API routes require `X-Internal-API-Key`. The key is a client identifier,
not a security boundary. The service uses Cloud Run's public `run.app` URL and
does not have Cloud Armor rate limiting; `/health` is intentionally
unauthenticated for the Cloud Run health check.

Firestore collections, fields, indexes, TTL policies, and access controls are
documented in [DATABASE.md](./DATABASE.md). The collection creation plan is
in [DATABASE_TABLE_PLAN.md](./DATABASE_TABLE_PLAN.md), with a collection and
relationship reference in [database_table.txt](./database_table.txt).

### Local development

```sh
python -m venv .venv
. .venv/bin/activate
pip install -r server/requirements.txt
export INTERNAL_API_KEY=local-development-key
export PROJECT_ID=your-gcp-project
export GCS_STATE_BUCKET=your-snapshot-bucket
uvicorn main:app --app-dir server --reload
```

Local Firestore/GCS access uses Application Default Credentials. Do not put
credentials or API keys in source files or `.tfvars`.

### Snapshot and POI data required

The API expects `manifest.json` in the configured state bucket:

```json
{
  "regions": {
    "KE": { "object": "regions/KE/snapshot.json" }
  }
}
```

Each referenced snapshot must contain `region_id`, timezone-aware
`source_snapshot_ts`, timezone-aware `processed_at`, `source_status` (`ok` or
`degraded`), and a `carparks` array. Each carpark has `carpark_id`, `name`,
`type`, `lat`, `lng`, `vacancies` (`-1` means unknown), and optional
`fee_summary`, `is_open`, and `region_id`.

`server/assets/hk_poi_master.json` currently contains no POIs because the
specified Hong Kong POI master was not included. Supply records with
`poi_id`, `name`, `aliases`, `lat`, and `lng` before enabling name-only
destination resolution. Only the TD car park ingest (below) exists so far; other
government sources and scheduled workers are not present; the API does not fabricate data
when the manifest is missing and returns `503` instead.
Name-only destination requests also return `503` until the POI file is seeded.
The commit endpoint reads the latest GCS vacancy value before opening the
Firestore transaction because Firestore cannot transact on GCS objects; the
transaction then checks active Firestore locks and writes the session lock.

### Real vacancy ingest (TD)

`server/app/ingest/td_carpark.py` turns the Transport Department open-data
car park feeds into one citywide region snapshot. It uses no AI and needs no API key.

- Sources (env-overridable `TD_VACANCY_URL` / `TD_INFO_URL`):
  `https://resource.data.one.gov.hk/td/carpark/vacancy_all.json` (≈1 min updates, ETag)
  and `https://resource.data.one.gov.hk/td/carpark/basic_info_all.json` (names, WGS84 coords).
- Mapping: `carpark_id=park_id`, `name=name_tc`, lat/lng from basic info (rows outside the
  HK bbox are dropped). Private car (`P`) + `HOURLY` only: type `A` → count, `B` with 0 → 0,
  otherwise `-1`; `C` → `is_open=false`, `-1`; per-carpark `lastupdate` older than 30 min → `-1`.
  Per-carpark `region_id` = district (e.g. `KWUN_TONG`); the snapshot region is `HK_TD`, and
  `source_snapshot_ts` = the feed's `Last-Modified`. `source_status` is `degraded` when fewer
  than 300 car parks map, or after 3 consecutive fetch failures (the last snapshot is kept, and
  the API marks it stale after 600 s anyway).
- Writes: immutable `regions/HK_TD/snapshots/*.json` objects and a shared `manifest.json`
  containing the current region pointers, ETags, failure counts and refresh lease.
  A 304 or unchanged body keeps the existing region object; the manifest's check time still advances.
  The CLI uses the same generation-guarded publication protocol as the API's on-demand refresh.
  `INCLUDE_DEMO_REGION=1` adds `MK_DEMO` when its seed object exists.
- Run:

```sh
cd server && python -m app.ingest.td_carpark --dry-run --out /tmp/s.json   # no GCS
bash scripts/run_ingest_once.sh [gs://bucket]                             # writes + lists bucket
```

  `scripts/upload_demo_seed.sh` no longer replaces a real (`is_demo: false`) manifest unless
  `FORCE_DEMO=1`. Without that flag it refreshes only `regions/MK_DEMO/snapshot.json`.
- Scheduled ingestion is opt-in; see the next section. Without scheduling or enabled on-demand
  refresh, data becomes stale after the last manual run.

### Scheduled downloads: car parks and parking meters

`ingestion.tf` defines **two Cloud Scheduler triggers and one Cloud Run Job**, running both
sources on these non-overlapping schedules in `Asia/Hong_Kong`:

| Time window | Interval | Cron |
|---|---|---|
| 06:00-23:59 | Every five minutes | `*/5 6-23 * * *` |
| 00:00-05:59 | Every fifteen minutes | `*/15 0-5 * * *` |

The overnight trigger last runs at 05:45; the daytime trigger starts at 06:00.
These schedules control the ingestion job only; enabled API on-demand refresh can still
fetch more frequently, including overnight.

The job reuses the
`server/` image, overriding its API startup command with
`python -m app.ingest.td_carpark --archive-raw` and `METER_REFRESH_ENABLED=true`.
It uses dedicated service accounts: the job can read/write the snapshot bucket, while
the scheduler can only invoke this job. No API key or application secrets are needed.

Each execution downloads these four complete feeds without conditional requests and preserves
the original bytes in GCS (run IDs contain a UTC timestamp and a random suffix):

| Feed | URL | GCS object |
|---|---|---|
| Car park vacancies | https://resource.data.one.gov.hk/td/carpark/vacancy_all.json | `raw/td_carpark/<run-id>/vacancy_all.json` |
| Car park basic information | https://resource.data.one.gov.hk/td/carpark/basic_info_all.json | `raw/td_carpark/<run-id>/basic_info_all.json` |
| Meter occupancy | https://resource.data.one.gov.hk/td/psiparkingspaces/occupancystatus/occupancystatus.csv | `raw/td_meters/<run-id>/occupancystatus.csv` |
| Meter spaces | https://resource.data.one.gov.hk/td/psiparkingspaces/spaceinfo/parkingspaces.csv | `raw/td_meters/<run-id>/parkingspaces.csv` |

The same downloaded responses feed the existing snapshot processor, without downloading twice:

- Processed objects: `regions/HK_TD/snapshots/*.json` and `regions/HK_TD_METERS/snapshots/*.json`.
- `manifest.json` points to the latest published objects; do not assume fixed snapshot filenames.
- Existing immutable metadata caches remain under `static/td_info/` and `static/td_meter_spaces/`.
- Unchanged dynamic feeds do not create new processed snapshots. Raw files are still archived.
- Raw archiving happens before the shared refresh lease/backoff checks, so another writer can
  cause snapshot publication to be skipped even after this execution archives its raw feeds.
- Download failures are included in structured logs and produce a nonzero job exit code.
  Other successfully downloaded feeds are retained and independent sources can still publish.
  Failed storage writes fail the job. Raw data is saved before parsing, including malformed feeds.
- Neither Scheduler nor the job retries failed executions automatically; the next scheduled run
  tries again. Scheduler success means execution was requested, not that ingestion succeeded:
  check Cloud Run Job execution status and logs.

**Deployment (manual, not performed by adding these files):**

1. Complete the existing-resource import first, keeping `ingestion_enabled = false`.
   Follow [the live-sync procedure](docs/TERRAFORM_LIVE_SYNC.md); do not apply a fresh-create
   plan against the existing project.
2. Build the updated `server/` image using `gcloud builds submit` (see below). Record its
   Artifact Registry digest; the old live backend image does not include raw archiving.
3. Set `ingestion_enabled = true` and `ingestion_image = "<new image URI, preferably @sha256:...>"`
   in your private `terraform.tfvars`. This image setting is independent of `backend_image`,
   so enabling ingestion does not deploy a new API image or change API traffic.
4. Run `terraform plan`, review the new job, scheduler, service accounts and IAM bindings,
   then `terraform apply`. The deployer needs permission to create those resources and
   act as the scheduler/job service accounts. The Cloud Scheduler API must be enabled.
5. Test immediately with `gcloud run jobs execute parking-data-ingest --region=asia-east2 --wait`
   (use your configured region), inspect execution logs, and list the bucket's `raw/` objects
   and the two region pointers in `manifest.json`.

The schedules start after apply. Expect 240 executions and up to 960 raw objects per day.
Raw and processed snapshots currently have **no automatic expiry**; storage grows over time.
Cloud Run, Scheduler, network and storage operations may incur charges. Agree on retention
and monitor actual costs before leaving this enabled long term.

## Cloud deployment

The Terraform configuration enables required Google APIs and provisions
Firestore, a private GCS snapshot bucket, Secret Manager secrets, Artifact
Registry, and a publicly reachable backend Cloud Run service. Cloud Run provides
the service's generated HTTPS `run.app` URL; no custom domain, DNS record,
external load balancer, or Cloud Armor policy is configured. It does not create
placeholder frontend or data-ingestion services.

### 1. Prepare the GCP project

The GCP project must already exist and have active billing. Install Terraform,
`gcloud`, and (for Firestore rules) Firebase CLI. Sign in as a user who can
enable APIs and create IAM bindings/resources. If your access is limited, ask
your project administrator to grant the required roles or run the deployment
with you; do not download or share a service-account key.

```sh
gcloud auth login
gcloud auth application-default login
gcloud config set project YOUR_GCP_PROJECT_ID
gcloud auth application-default set-quota-project YOUR_GCP_PROJECT_ID
gcloud billing projects describe YOUR_GCP_PROJECT_ID
```

Confirm billing is enabled. The deployer needs permission to enable project
services, create service accounts and IAM bindings, and administer Cloud Run,
Firestore, Cloud Storage, Secret Manager, Artifact Registry, and Cloud Build
resources. A project Owner can bootstrap the stack; an
administrator can instead grant narrower roles according to organization
policy.

> **This branch (`新確認版Terraform`) adopts the already-running cloud with `import` blocks** (see `imports.tf`
> and `docs/TERRAFORM_LIVE_SYNC.md`, section 5). Do not follow the "create the foundation" steps below on the live
> project: use the import flow, and only `terraform apply` when the plan shows 0 to add / change / destroy.

### 2. Configure Terraform and create the foundation

Copy [`terraform.tfvars.example`](./terraform.tfvars.example) to
`terraform.tfvars` and replace the project ID and bucket name. Bucket names are
globally unique, lowercase, and cannot be changed after creation.

```sh
cp terraform.tfvars.example terraform.tfvars
# Edit terraform.tfvars with your project ID and unique bucket name.
terraform init
terraform plan
```

Terraform's first run must create the Secret Manager containers and Artifact
Registry before secret versions and the API image exist. Apply foundation
resources first:

```sh
terraform apply \
  -target=google_project_service.required_apis \
  -target=google_firestore_database.database \
  -target=google_firestore_backup_schedule.daily \
  -target=google_firestore_field.active_sessions_ttl \
  -target=google_firestore_field.virtual_locks_ttl \
  -target=google_firestore_field.crowd_reports_ttl \
  -target=google_storage_bucket.state_snapshots \
  -target=google_secret_manager_secret.internal_api_key \
  -target=google_secret_manager_secret.crowd_ip_hash_secret \
  -target=google_artifact_registry_repository.api
```

### 3. Add secrets and build the API image

Secret values are deliberately not stored in Terraform or the repository.
Create them locally with restrictive file permissions, then upload them to
Secret Manager without printing the values:

```sh
umask 077
mkdir -p "$HOME/.config/hkparking"
openssl rand -hex 32 > "$HOME/.config/hkparking/internal-api-key"
openssl rand -hex 32 > "$HOME/.config/hkparking/crowd-ip-hash-secret"

gcloud secrets versions add hkparking-internal-api-key \
  --data-file="$HOME/.config/hkparking/internal-api-key"
gcloud secrets versions add hkparking-crowd-ip-hash-secret \
  --data-file="$HOME/.config/hkparking/crowd-ip-hash-secret"
```

The API key is also needed by the app client. Transfer it through your private
development workflow; it is only a basic client identifier, not a security
boundary. Do not commit the key or either local secret file.

Firestore uses a Terraform `ABANDON` deletion policy. Delete protection,
seven-day point-in-time recovery and daily backups (14 days) are available as
switches (`firestore_enable_delete_protection`, `firestore_enable_point_in_time_recovery`,
`firestore_enable_daily_backups`), but they default to **false** because the live
database has them off (see `docs/TERRAFORM_LIVE_SYNC.md`). Turning them on is a
separate team decision and may incur additional charges; review the cost in
`terraform plan` first.

Build and push the image, then set `backend_image` in `terraform.tfvars` to the
same image URI:

```sh
gcloud builds submit server \
  --tag "asia-east2-docker.pkg.dev/YOUR_GCP_PROJECT_ID/hkparking/server:2.9.1"
```

### 4. Deploy and get the Cloud Run URL

Review the full plan, then create the remaining resources:

```sh
terraform plan
terraform apply
terraform output -raw api_base_url
```

The backend permits unauthenticated invocation at IAM so that the generated
Cloud Run URL is reachable. The application still requires
`X-Internal-API-Key`, but this is not a security boundary. There is no Cloud
Armor IP throttling. Crowd-report grouping currently depends on the
`X-Load-Balancer-Client-IP` header, so that endpoint requires additional
configuration before it can be used safely without the load balancer.

### 5. Deploy Firestore rules and indexes

If the GCP project is not yet a Firebase project, register it first. Then
authenticate Firebase CLI and deploy the checked-in rules and indexes:

```sh
firebase login
# Run this only if the project has not already been registered with Firebase.
firebase projects:addfirebase YOUR_GCP_PROJECT_ID
firebase deploy --only firestore:rules,firestore:indexes \
  --project YOUR_GCP_PROJECT_ID
```

Keep the Terraform state file in a private, backed-up location and use the same
Terraform workspace for both the foundation and full applies. Never commit
`terraform.tfstate`, plan files, `terraform.tfvars`, or secret values.

### Cost and remaining setup

Cloud Run can scale to zero, but Firestore and storage can incur charges. Check
current GCP pricing and set project budgets/alerts before applying. No resources
are created in GCP by this repository until you run `terraform apply`.

DATA.GOV.HK scheduled ingestion is opt-in as described above. The current scope does not include history workers,
official POI data, Gemini perk reconciliation, or the 7-day/14-day chronic
hotspot escalation flow. The one-hour crowd-report penalty and two-source
ranking cap are implemented by the API. Terraform defines GCS lifecycle
retention only for objects under `history/` (Nearline after 30 days, Coldline
after 90 days, Archive after 365 days, deletion after 730 days); review the
plan and deletion impact before applying it. This does not create history
workers, a scheduler, or BigQuery resources.
