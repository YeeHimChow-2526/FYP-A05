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

The Terraform configuration enables required Google APIs and provisions
Firestore, a private GCS snapshot bucket, Secret Manager secrets, Artifact
Registry, the backend Cloud Run service, and an external HTTPS load balancer
with Cloud Armor IP throttling. It does not create placeholder frontend or
data-ingestion services.

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
Firestore, Cloud Storage, Secret Manager, Artifact Registry, Cloud Build, and
Compute/Cloud Armor resources. A project Owner can bootstrap the stack; an
administrator can instead grant narrower roles according to organization
policy.

### 2. Configure Terraform and create the foundation

Copy [`terraform.tfvars.example`](./terraform.tfvars.example) to
`terraform.tfvars` and replace the project ID, domain, and bucket name. Bucket
names are globally unique, lowercase, and cannot be changed after creation.
Use a DNS domain that you own; the HTTPS certificate will not become active
until its DNS A record points to the load balancer IP.

```sh
cp terraform.tfvars.example terraform.tfvars
# Edit terraform.tfvars with your project, DNS name, and unique bucket name.
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

Build and push the image, then set `backend_image` in `terraform.tfvars` to the
same image URI:

```sh
gcloud builds submit server \
  --tag "asia-east2-docker.pkg.dev/YOUR_GCP_PROJECT_ID/hkparking/server:2.9.1"
```

### 4. Deploy and connect DNS

Review the full plan, then create the remaining resources:

```sh
terraform plan
terraform apply
terraform output api_ip_address
```

Create an `A` record for `api_domain` pointing at `api_ip_address`. Wait for
DNS propagation and Google-managed certificate provisioning before testing
HTTPS. The backend permits unauthenticated invocation at IAM only because the
Cloud Run ingress setting rejects traffic that does not arrive through the
load balancer. Cloud Armor handles IP throttling; the API does not add a
second in-container rate limiter. The HTTPS load balancer injects
`X-Load-Balancer-Client-IP`; the API uses that header for crowd-report grouping
and does not accept a client-supplied IP in JSON.

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

Cloud Run can scale to zero, but Firestore, storage, Cloud Armor, and especially
the external HTTPS load balancer can incur charges. Check current GCP pricing
and set project budgets/alerts before applying. No resources are created in
GCP by this repository until you run `terraform apply`.

The current scope does not include DATA.GOV.HK ingestion/history workers,
official POI data, Gemini perk reconciliation, or the 7-day/14-day chronic
hotspot escalation flow. The one-hour crowd-report penalty and two-source
ranking cap are implemented by the API.
