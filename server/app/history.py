"""Cold-path History capture (approved t1232 timings; ARCHITECTURE §12.3/§13 pattern).

Reads the PUBLISHED live manifest (never refreshes upstream, never writes the manifest) and
writes one immutable NDJSON object per (HKT slot, region) with ifGenerationMatch=0, so any number
of instances/retries produce at most one object per slot. Recommendation never reads History and
a History failure never touches the live path.

Schedule (Asia/Hong_Kong, half-open [start, end)), slots anchored at 00:00 HKT:
  07:30–09:30 and 17:30–20:00 -> every 5 min; 01:00–06:00 -> every 60 min; otherwise 15 min.
Only the CURRENT slot is captured: missed slots are never back-filled; nothing runs unless a
caller (CLI/Job) runs it, so without an external trigger there are no guaranteed observations.

Object: history/grid_occupancy/dt=YYYYMMDD/hour=HH/region={id}/slot=HHMM.ndjson
(deviation from §12.3's one hourly file: per-slot objects keep writes immutable/idempotent;
the daily BQ load can read the hour=HH/ prefix). Demo regions go under history/demo/… only when
asked, and never into the real prefix.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from datetime import UTC, datetime, time, timedelta
from typing import Any, Callable
from zoneinfo import ZoneInfo

from .ingest import td_carpark as td
from .models import RegionSnapshot, SnapshotManifest
from .shared_cache import read_manifest

HKT = ZoneInfo("Asia/Hong_Kong")
# (start, end, interval_minutes); half-open; must align with interval from 00:00.
HISTORY_WINDOWS: tuple[tuple[time, time, int], ...] = (
    (time(7, 30), time(9, 30), 5),    # morning peak
    (time(17, 30), time(20, 0), 5),   # evening peak
    (time(1, 0), time(6, 0), 60),     # night
)
HISTORY_DEFAULT_INTERVAL_MINUTES = 15
HISTORY_PREFIX = "history/grid_occupancy"
HISTORY_DEMO_PREFIX = "history/demo/grid_occupancy"
DEMO_REGIONS = {"MK_DEMO"}
WRITE_ATTEMPTS = 3  # bounded retries for non-412 errors


@dataclass(frozen=True)
class Slot:
    start: datetime  # HKT, aware
    interval_minutes: int

    @property
    def key(self) -> str:
        return f"{self.start:%H%M}"


def slot_for(now: datetime) -> Slot:
    if now.tzinfo is None:
        raise ValueError("now must be timezone-aware")
    local = now.astimezone(HKT)
    t = local.time()
    interval = HISTORY_DEFAULT_INTERVAL_MINUTES
    for start, end, minutes in HISTORY_WINDOWS:
        if start <= t < end:
            interval = minutes
            break
    minute_of_day = local.hour * 60 + local.minute
    anchored = minute_of_day - minute_of_day % interval
    midnight = local.replace(hour=0, minute=0, second=0, microsecond=0)
    return Slot(midnight + timedelta(minutes=anchored), interval)


def object_name(slot: Slot, region_id: str, demo: bool) -> str:
    prefix = HISTORY_DEMO_PREFIX if demo else HISTORY_PREFIX
    return f"{prefix}/dt={slot.start:%Y%m%d}/hour={slot.start:%H}/region={region_id}/slot={slot.key}.ndjson"


def records(region: RegionSnapshot, slot: Slot, *, manifest_entry: dict, checked_at: str | None,
            captured_at: datetime, source: str) -> list[dict[str, Any]]:
    """One row per car park / meter cluster. Unknown counts stay NULL with their typed status
    (no invented 0; deviation from §13 'NOT NULL'). source_age is labelled, never 'refreshed'."""
    src_ts = datetime.fromisoformat(region.source_snapshot_ts)
    rows = []
    for c in region.carparks:
        status = str(c.effective_status())
        rec_ts = c.source_updated_at or region.source_snapshot_ts
        rows.append({
            "snapshot_date": f"{slot.start:%Y-%m-%d}",
            "slot_start": slot.start.isoformat(),
            "slot_interval_minutes": slot.interval_minutes,
            "record_type": "PERIODIC",
            "region": region.region_id,
            "source": source,
            "grid_id": c.carpark_id,
            "entity_type": "METER_GRID" if c.type.upper() in {"METER", "METERS"} else "CARPARK",
            "vacant_count": c.vacancies if status == "COUNT" and c.vacancies >= 0 else None,
            "vacancy_status": status,
            "vacancy_reason": c.vacancy_reason or None,
            "name": c.name,
            "is_open": c.is_open,
            "source_status": manifest_entry.get("source_status", region.source_status),
            "source_snapshot_ts": region.source_snapshot_ts,     # publisher time of the snapshot
            "record_source_updated_at": rec_ts,                  # publisher time of this row
            "record_source_age_seconds": max(0, int((captured_at - datetime.fromisoformat(rec_ts)).total_seconds())),
            "snapshot_source_age_seconds": max(0, int((captured_at - src_ts).total_seconds())),
            "checked_at": checked_at,                            # last successful upstream check
            "processed_at": region.processed_at,                 # when the live snapshot was built
            "captured_at": captured_at.isoformat(),              # when History read it
        })
    return rows


# Which manifest source a region's checked_at comes from; any other region (e.g. demo) has none.
REGION_SOURCE = {td.REGION_ID: "td_carpark", "HK_TD_METERS": "td_meters"}


def _checked_at(model: SnapshotManifest, region_id: str) -> str | None:
    source = REGION_SOURCE.get(region_id)
    if source is None:
        return None
    state = model.sources.get(source)
    if state is not None:
        return state.checked_at
    return model.checked_at if source == "td_carpark" else None


@dataclass
class CaptureResult:
    outcome: str  # captured | no_live_snapshot | bad_manifest
    slot: Slot
    regions: dict[str, str] = field(default_factory=dict)  # region -> written|exists|failed:...|skipped_demo


def capture_once(store: td.ObjectStore, *, clock: Callable[[], datetime] = lambda: datetime.now(UTC),
                 include_demo: bool = False) -> CaptureResult:
    captured_at = clock()
    slot = slot_for(captured_at)
    manifest, _gen, valid = read_manifest(store)
    if not valid:
        return CaptureResult("bad_manifest", slot)
    if not manifest or not manifest.get("regions"):
        return CaptureResult("no_live_snapshot", slot)
    model = SnapshotManifest.model_validate(manifest)
    out: dict[str, str] = {}
    for region_id, entry in sorted(model.regions.items()):
        demo = region_id in DEMO_REGIONS
        if demo and not include_demo:
            out[region_id] = "skipped_demo"
            continue
        name = object_name(slot, region_id, demo)
        try:
            data, _ = store.read(entry["object"])
            if data is None:
                out[region_id] = "failed:missing_snapshot"
                continue
            payload = json.loads(data.decode("utf-8-sig"))
            payload.setdefault("region_id", region_id)
            region = RegionSnapshot.model_validate(payload)
            source = payload.get("source") or ("DEMO" if demo else "")
            rows = records(region, slot, manifest_entry=entry, checked_at=_checked_at(model, region_id),
                           captured_at=captured_at, source=source)
            body = "".join(json.dumps(r, ensure_ascii=False, separators=(",", ":")) + "\n" for r in rows).encode()
        except Exception as error:  # noqa: BLE001 - one bad region must not stop the others
            out[region_id] = f"failed:{type(error).__name__}"
            continue
        for attempt in range(WRITE_ATTEMPTS):
            try:
                store.write(name, body, 0)
                out[region_id] = "written"
                break
            except td.PreconditionFailed:
                out[region_id] = "exists"  # another instance/retry already recorded this slot
                break
            except Exception as error:  # noqa: BLE001
                out[region_id] = f"failed:{type(error).__name__}"
    return CaptureResult("captured", slot, out)


def main(argv: list[str] | None = None) -> int:
    import argparse
    import os

    parser = argparse.ArgumentParser(prog="python -m app.history")
    parser.add_argument("--bucket", default=os.environ.get("GCS_STATE_BUCKET", ""))
    parser.add_argument("--include-demo", action="store_true")
    args = parser.parse_args(argv)
    if not args.bucket:
        print(json.dumps({"severity": "ERROR", "message": "GCS_STATE_BUCKET is not set"}))
        return 2
    result = capture_once(td.GcsObjectStore(args.bucket), include_demo=args.include_demo)
    failed = any(v.startswith("failed") for v in result.regions.values())
    print(json.dumps({"severity": "ERROR" if failed else "INFO", "component": "history_capture",
                      "outcome": result.outcome, "slot": result.slot.start.isoformat(),
                      "regions": result.regions}, ensure_ascii=False))
    return 1 if failed or result.outcome == "bad_manifest" else 0


if __name__ == "__main__":
    raise SystemExit(main())
