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
not a security boundary; rate limiting is configured at Cloud Armor. `/healthz`
is intentionally unauthenticated for the Cloud Run health check.

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
destination resolution. The government data URLs, their source schemas, and
the ingestion workers are also not present; the API does not fabricate data
when the manifest is missing and returns `503` instead.
Name-only destination requests also return `503` until the POI file is seeded.
The commit endpoint reads the latest GCS vacancy value before opening the
Firestore transaction because Firestore cannot transact on GCS objects; the
transaction then checks active Firestore locks and writes the session lock.

## Cloud deployment

Terraform provisions the API Cloud Run service, Firestore, a private GCS
snapshot bucket, Secret Manager secrets, and an external HTTPS load balancer
with Cloud Armor IP throttling. Set `project_id`, `backend_image`, `api_domain`,
and `state_snapshot_bucket_name` in a local tfvars file. Point `api_domain` at
the IP in the Terraform output before expecting the managed TLS certificate to
become active.
The backend permits unauthenticated invocation at IAM only because the
Cloud Run ingress setting rejects traffic that does not arrive through the
load balancer; the internal header key is not treated as the security boundary.

Secret values are intentionally not managed by Terraform state. First create
the Secret Manager secrets and Artifact Registry repository:

```sh
terraform apply \
  -target=google_secret_manager_secret.internal_api_key \
  -target=google_secret_manager_secret.crowd_ip_hash_secret \
  -target=google_artifact_registry_repository.api
```

Build and push the image to the Artifact Registry repository reported by
`terraform output api_image_repository`, set `backend_image` to that full image
URI in the local tfvars file, and add secret versions through Secret Manager
out-of-band. The two secrets are `hkparking-internal-api-key` and
`hkparking-crowd-ip-hash-secret`; do not commit their values. Then run a normal
`terraform apply`.

```sh
gcloud builds submit server \
  --tag "${REGION}-docker.pkg.dev/${PROJECT_ID}/hkparking/server:2.9.1"
```

The HTTPS load balancer injects
`X-Load-Balancer-Client-IP`; the API uses that header for crowd-report grouping
and does not accept a client-supplied IP in JSON.

Deploy Firestore rules and indexes with Firebase CLI:

```sh
firebase deploy --only firestore:rules,firestore:indexes --project "$PROJECT_ID"
```

The existing sync scheduler is paused until an ingestion worker and real
source configuration are provided. Cloud Armor handles IP throttling; the API
does not implement a second in-container rate limiter.

The current scope does not include DATA.GOV.HK ingestion/history workers,
official POI data, Gemini perk reconciliation, or the 7-day/14-day chronic
hotspot escalation flow. The one-hour crowd-report penalty and two-source
ranking cap are implemented by the API.
