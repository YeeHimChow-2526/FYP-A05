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

### `carpark_perks_master/{carpark_id}`

This document is read by the recommendation service as a carpark perk record.
The upstream official and offline source schemas and their reconciliation
worker are not present in the repository, so this project does not define or
seed additional perk fields. Supply the agreed source contract before loading
production perk data.

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

The Terraform defaults protect the `(default)` database from accidental
deletion (`delete_protection_state = DELETE_PROTECTION_ENABLED` and
`deletion_policy = ABANDON`), enable seven-day point-in-time recovery (PITR),
and create a daily backup schedule with 14-day retention. The backup retention
can be configured from 1 to 98 days; PITR and daily backups can be disabled
through Terraform variables for non-production environments. Backup and PITR
storage and operations may incur additional charges, so review the plan and
project budget before deployment.

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
