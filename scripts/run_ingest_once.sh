#!/usr/bin/env bash
# Run the TD car park vacancy ingest once (no AI), then show the bucket state.
# Usage:
#   bash scripts/run_ingest_once.sh [gs://bucket] [--dry-run --out /tmp/s.json]
#   INCLUDE_DEMO_REGION=1 bash scripts/run_ingest_once.sh      # keep MK_DEMO in manifest
#   LOOP_SECONDS=120 bash scripts/run_ingest_once.sh           # repeat until Ctrl-C (demo, pre-Scheduler)
# Credentials: Application Default Credentials. To write as the deploy SA:
#   gcloud auth application-default login --impersonate-service-account=<sa-email>
# Needs only storage object read/create on the bucket. No API keys.
set -euo pipefail
BUCKET="gs://total-velocity-507606-q2-state-snapshots"
if [[ $# -gt 0 && "$1" != --* ]]; then BUCKET="$1"; shift; fi
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PY="${PYTHON:-python3}"
DRY=0
for a in "$@"; do [[ "$a" == "--dry-run" ]] && DRY=1; done

run() {
  (cd "$ROOT/server" && GCS_STATE_BUCKET="$BUCKET" "$PY" -m app.ingest.td_carpark "$@")
}

if [[ -n "${LOOP_SECONDS:-}" && "$DRY" == 0 ]]; then
  while true; do run "$@" || echo "ingest failed (exit $?), keeping last snapshot" >&2; sleep "$LOOP_SECONDS"; done
fi

run "$@"
if [[ "$DRY" == 0 ]]; then
  gcloud storage ls -l "$BUCKET/**"
  echo "--- manifest.json"
  gcloud storage cat "$BUCKET/manifest.json"; echo
fi
