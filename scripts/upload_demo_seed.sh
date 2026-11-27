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
# Guard: never clobber a real (non-DEMO) manifest written by the TD ingest unless FORCE_DEMO=1.
REAL_MANIFEST=0
# CAS like every other manifest writer (server/app/shared_cache.py): 0 = must not exist yet.
GEN="$(gcloud storage objects describe "$BUCKET/manifest.json" --format='value(generation)' 2>/dev/null || true)"
GEN="${GEN:-0}"
if gcloud storage cat "$BUCKET/manifest.json" > "$TMP/current_manifest.json" 2>/dev/null; then
  if python3 -c 'import json,sys; m=json.load(open(sys.argv[1])); sys.exit(0 if m.get("is_demo") is False else 1)' "$TMP/current_manifest.json"; then
    REAL_MANIFEST=1
  fi
fi
# region first, manifest last (so manifest never points at a missing object)
gcloud storage cp --content-type=application/json "$TMP/regions/MK_DEMO/snapshot.json" "$BUCKET/regions/MK_DEMO/snapshot.json"
if [[ "$REAL_MANIFEST" == 1 && "${FORCE_DEMO:-0}" != 1 ]]; then
  echo "manifest.json has is_demo=false (real TD data); NOT overwriting it." >&2
  echo "Refreshed regions/MK_DEMO/snapshot.json only. Keep MK_DEMO listed with INCLUDE_DEMO_REGION=1 on the ingest," >&2
  echo "or rerun with FORCE_DEMO=1 to replace the manifest with the DEMO-only one." >&2
else
  # Precondition: fails (412) if anyone (API refresh / ingest) changed the manifest since GEN.
  gcloud storage cp --if-generation-match="$GEN" --content-type=application/json "$TMP/manifest.json" "$BUCKET/manifest.json"
fi
gcloud storage ls -l "$BUCKET/**"
echo "Uploaded DEMO seed with timestamp $NOW"
