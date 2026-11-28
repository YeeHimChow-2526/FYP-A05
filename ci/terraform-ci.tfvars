# Non-secret pins for CI terraform plan, and for any later approved apply.
# Last verified against live GCP on 2026-11-28 (Asia/Hong_Kong).
# If live ingest or the Cloud Run service template is changed on purpose,
# update this file in the same change and record the date and reason.
# This file is not permission to apply without a fresh reviewed plan.

project_id                 = "total-velocity-507606-q2"
region                     = "asia-east2"
state_snapshot_bucket_name = "total-velocity-507606-q2-state-snapshots"
ingestion_enabled          = true
ingestion_image            = "asia-east2-docker.pkg.dev/total-velocity-507606-q2/hkparking/server@sha256:17358f8dea2553a08c21551240fac40da0351d61b2e54ac253a61bb375c03a42"
backend_secret_reload_ts  = "1795798559"
