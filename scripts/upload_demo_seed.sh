#!/usr/bin/env bash
# Upload DEMO seed to GCS. Usage: bash scripts/upload_demo_seed.sh [bucket]
set -euo pipefail
BUCKET="${1:-gs://total-velocity-507606-q2-state-snapshots}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SEED="$ROOT/server/seed"
NOW="$(date -u +%Y-%m-%dT%H:%M:%S+00:00)"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/regions/MK_DEMO"
cp "$SEED/manifest.json" "$TMP/manifest.json"
sed "s/__NOW__/$NOW/g" "$SEED/regions/MK_DEMO/snapshot.json" > "$TMP/regions/MK_DEMO/snapshot.json"
python3 -m json.tool "$TMP/regions/MK_DEMO/snapshot.json" >/dev/null 2>&1 || python -m json.tool "$TMP/regions/MK_DEMO/snapshot.json" >/dev/null
# region first, manifest last (so manifest never points at a missing object)
gcloud storage cp --content-type=application/json "$TMP/regions/MK_DEMO/snapshot.json" "$BUCKET/regions/MK_DEMO/snapshot.json"
gcloud storage cp --content-type=application/json "$TMP/manifest.json" "$BUCKET/manifest.json"
gcloud storage ls -l "$BUCKET/**"
echo "Uploaded DEMO seed with timestamp $NOW"
