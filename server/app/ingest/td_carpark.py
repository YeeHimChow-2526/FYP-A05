"""TD car park vacancy feed -> GCS ``regions/HK_TD/snapshot.json`` + ``manifest.json``.

No AI. One run = one conditional fetch of the Transport Department feeds,
a pure transform into the ``RegionSnapshot`` schema, and generation-guarded
GCS writes (region first, manifest last). Designed to run as a Cloud Run Job
(``python -m app.ingest.td_carpark``) or by hand via ``scripts/run_ingest_once.sh``.

Exit codes: 0 = written / nothing changed / another run won (412);
1 = source fetch failed (old snapshot kept; degraded after 3 consecutive fails);
2 = bad configuration.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta, timezone
from email.utils import parsedate_to_datetime
from typing import Any, Callable, Protocol

from ..models import RegionSnapshot, SnapshotManifest

HKT = timezone(timedelta(hours=8))

VACANCY_URL = os.environ.get(
    "TD_VACANCY_URL", "https://resource.data.one.gov.hk/td/carpark/vacancy_all.json"
)
INFO_URL = os.environ.get(
    "TD_INFO_URL", "https://resource.data.one.gov.hk/td/carpark/basic_info_all.json"
)

REGION_ID = "HK_TD"
SOURCE = "TD_DATA_GOV_HK"
SNAPSHOT_OBJECT = f"regions/{REGION_ID}/snapshot.json"
MANIFEST_OBJECT = "manifest.json"
STATE_OBJECT = "state/td_carpark.json"
DEMO_REGION_ID = "MK_DEMO"
DEMO_REGION_OBJECT = f"regions/{DEMO_REGION_ID}/snapshot.json"

# HK bounding box (lat_min, lat_max, lng_min, lng_max)
BBOX = (22.153, 22.563, 113.835, 114.441)
PER_CARPARK_STALE_MINUTES = 30
MIN_OK_CARPARKS = 300
DEGRADED_AFTER_FAILURES = 3

FETCH_TIMEOUT_SECONDS = 8
FETCH_MAX_ATTEMPTS = 4
FETCH_BACKOFF_SECONDS = 2


# --------------------------------------------------------------------------- transform


def _parse_hkt(value: str) -> datetime:
    return datetime.strptime(value, "%Y-%m-%d %H:%M:%S").replace(tzinfo=HKT)


def _region_key(district_en: str | None) -> str:
    return (
        (district_en or "")
        .strip()
        .upper()
        .replace("&", "AND")
        .replace(" ", "_")
    )


def _private_hourly(row: dict[str, Any]) -> dict[str, Any] | None:
    for vehicle in row.get("vehicle_type") or []:
        if vehicle.get("type") != "P":
            continue
        for category in vehicle.get("service_category") or []:
            if category.get("category") == "HOURLY":
                return category
    return None


def transform(
    vacancy: dict[str, Any],
    info: dict[str, Any],
    now: datetime,
    source_ts: datetime,
) -> tuple[dict[str, Any], dict[str, int]]:
    """Pure mapping of the two TD feeds to a RegionSnapshot dict (+ counters)."""
    meta = {r.get("park_id"): r for r in info.get("car_park") or []}
    stats = {
        "in": 0,
        "no_info": 0,
        "bad_geo": 0,
        "no_private_hourly": 0,
        "stale": 0,
        "closed": 0,
        "out": 0,
        "with_vacancy": 0,
    }
    carparks: list[dict[str, Any]] = []
    for row in vacancy.get("car_park") or []:
        stats["in"] += 1
        park_id = row.get("park_id")
        m = meta.get(park_id)
        if not park_id or not m:
            stats["no_info"] += 1
            continue
        lat, lng = m.get("latitude"), m.get("longitude")
        if not (
            isinstance(lat, (int, float))
            and isinstance(lng, (int, float))
            and BBOX[0] <= lat <= BBOX[1]
            and BBOX[2] <= lng <= BBOX[3]
        ):
            stats["bad_geo"] += 1
            continue
        hourly = _private_hourly(row)
        if hourly is None:
            stats["no_private_hourly"] += 1
            continue
        vtype, raw = hourly.get("vacancy_type"), hourly.get("vacancy")
        is_open = vtype != "C"
        if vtype == "A" and isinstance(raw, int) and not isinstance(raw, bool) and raw >= 0:
            vacancies = raw
        elif vtype == "B" and raw == 0:  # B = flag only: 0 means full, else count unknown
            vacancies = 0
        else:
            vacancies = -1
        if not is_open:
            stats["closed"] += 1
            vacancies = -1
        try:
            if now - _parse_hkt(hourly["lastupdate"]) > timedelta(
                minutes=PER_CARPARK_STALE_MINUTES
            ):
                if vacancies != -1:
                    stats["stale"] += 1
                vacancies = -1
        except (KeyError, TypeError, ValueError):
            vacancies = -1
        carparks.append(
            {
                "carpark_id": str(park_id),
                "name": m.get("name_tc") or m.get("name_en") or str(park_id),
                "type": "INDOOR",
                "lat": float(lat),
                "lng": float(lng),
                "vacancies": vacancies,
                "fee_summary": "",
                "is_open": is_open,
                "region_id": _region_key(m.get("district_en")),
            }
        )
    stats["out"] = len(carparks)
    stats["with_vacancy"] = sum(1 for c in carparks if c["vacancies"] >= 0)
    snapshot = {
        "region_id": REGION_ID,
        "source": SOURCE,
        "is_demo": False,
        "source_snapshot_ts": source_ts.isoformat(),
        "processed_at": now.isoformat(),
        "source_status": "ok" if len(carparks) >= MIN_OK_CARPARKS else "degraded",
        "carparks": carparks,
    }
    RegionSnapshot.model_validate(snapshot)  # fail loudly before any write
    return snapshot, stats


def build_manifest(
    snapshot: dict[str, Any],
    existing: dict[str, Any] | None,
    include_demo: bool,
    demo_object_exists: bool = False,
) -> dict[str, Any]:
    """Manifest region values are strings only (SnapshotManifest schema)."""
    regions: dict[str, dict[str, str]] = {
        REGION_ID: {
            "object": SNAPSHOT_OBJECT,
            "source_snapshot_ts": str(snapshot["source_snapshot_ts"]),
            "source_status": str(snapshot["source_status"]),
        }
    }
    if include_demo:
        old_demo = ((existing or {}).get("regions") or {}).get(DEMO_REGION_ID)
        if isinstance(old_demo, dict) and old_demo.get("object"):
            regions[DEMO_REGION_ID] = {k: str(v) for k, v in old_demo.items()}
        elif demo_object_exists:
            regions[DEMO_REGION_ID] = {"object": DEMO_REGION_OBJECT}
    manifest = {
        "source": "TD",
        "is_demo": False,
        "regions": regions,
    }
    if DEMO_REGION_ID in regions:
        manifest["includes_demo_region"] = True
    SnapshotManifest.model_validate(manifest)
    return manifest


# --------------------------------------------------------------------------- fetch


class FetchError(RuntimeError):
    pass


@dataclass
class FetchResult:
    status: int  # 200 or 304
    body: bytes = b""
    etag: str | None = None
    last_modified: datetime | None = None


Fetcher = Callable[[str, str | None], FetchResult]


def http_fetch(
    url: str,
    etag: str | None = None,
    *,
    timeout: float = FETCH_TIMEOUT_SECONDS,
    attempts: int = FETCH_MAX_ATTEMPTS,
    backoff: float = FETCH_BACKOFF_SECONDS,
    sleep: Callable[[float], None] = time.sleep,
) -> FetchResult:
    headers = {"User-Agent": "fyp-a05-td-ingest/1", "Accept": "application/json"}
    if etag:
        headers["If-None-Match"] = etag
    last_error: Exception | None = None
    for attempt in range(attempts):
        if attempt:
            sleep(backoff)
        try:
            request = urllib.request.Request(url, headers=headers)
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return FetchResult(
                    status=200,
                    body=response.read(),
                    etag=response.headers.get("ETag"),
                    last_modified=_parse_http_date(response.headers.get("Last-Modified")),
                )
        except urllib.error.HTTPError as error:
            if error.code == 304:
                return FetchResult(status=304, etag=etag)
            last_error = error
            if 400 <= error.code < 500 and error.code not in (408, 429):
                break
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            last_error = error
    raise FetchError(f"GET {url} failed: {last_error}")


def _parse_http_date(value: str | None) -> datetime | None:
    if not value:
        return None
    try:
        parsed = parsedate_to_datetime(value)
    except (TypeError, ValueError):
        return None
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=UTC)


def _decode_json(body: bytes) -> dict[str, Any]:
    payload = json.loads(body.decode("utf-8-sig"))
    if not isinstance(payload, dict):
        raise FetchError("expected a JSON object")
    return payload


# --------------------------------------------------------------------------- storage


class PreconditionFailed(RuntimeError):
    """Another writer changed the object (GCS 412)."""


class ObjectStore(Protocol):
    def read(self, name: str) -> tuple[bytes | None, int]:
        """Return (data, generation); (None, 0) when the object is missing."""

    def write(self, name: str, data: bytes, if_generation_match: int) -> None: ...


class GcsObjectStore:
    def __init__(self, bucket_name: str) -> None:
        from google.api_core import exceptions as gexc
        from google.cloud import storage

        self._gexc = gexc
        self._bucket = storage.Client().bucket(bucket_name.removeprefix("gs://"))

    def read(self, name: str) -> tuple[bytes | None, int]:
        blob = self._bucket.get_blob(name)
        if blob is None:
            return None, 0
        try:
            data = blob.download_as_bytes(if_generation_match=blob.generation)
        except self._gexc.PreconditionFailed as error:
            raise PreconditionFailed(name) from error
        return data, int(blob.generation)

    def write(self, name: str, data: bytes, if_generation_match: int) -> None:
        blob = self._bucket.blob(name)
        blob.cache_control = "no-store"
        try:
            blob.upload_from_string(
                data,
                content_type="application/json",
                if_generation_match=if_generation_match,
            )
        except self._gexc.PreconditionFailed as error:
            raise PreconditionFailed(name) from error


def _dumps(payload: dict[str, Any]) -> bytes:
    return json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def _read_json(store: ObjectStore, name: str) -> tuple[dict[str, Any] | None, int]:
    data, generation = store.read(name)
    if data is None:
        return None, 0
    try:
        payload = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return None, generation
    return (payload if isinstance(payload, dict) else None), generation


# --------------------------------------------------------------------------- run


@dataclass
class RunResult:
    exit_code: int
    outcome: str
    stats: dict[str, Any] = field(default_factory=dict)
    writes: list[str] = field(default_factory=list)


def _write_manifest(
    store: ObjectStore,
    snapshot: dict[str, Any],
    include_demo: bool,
    writes: list[str],
) -> None:
    existing, generation = _read_json(store, MANIFEST_OBJECT)
    demo_exists = False
    if include_demo and not ((existing or {}).get("regions") or {}).get(DEMO_REGION_ID):
        demo_exists = store.read(DEMO_REGION_OBJECT)[0] is not None
    manifest = build_manifest(snapshot, existing, include_demo, demo_exists)
    if existing == manifest:
        return
    store.write(MANIFEST_OBJECT, _dumps(manifest), generation)
    writes.append(MANIFEST_OBJECT)


def run_once(
    store: ObjectStore,
    fetch: Fetcher,
    *,
    now: datetime | None = None,
    include_demo: bool = False,
) -> RunResult:
    now = now or datetime.now(UTC)
    writes: list[str] = []
    state, state_gen = _read_json(store, STATE_OBJECT)
    state = state or {}
    try:
        try:
            vacancy_res = fetch(VACANCY_URL, state.get("etag"))
            if vacancy_res.status == 304:
                return RunResult(0, "not_modified")
            digest = hashlib.sha256(vacancy_res.body).hexdigest()
            if digest == state.get("sha256"):
                return RunResult(0, "unchanged")
            info_res = fetch(INFO_URL, None)
            vacancy = _decode_json(vacancy_res.body)
            info = _decode_json(info_res.body)
        except (FetchError, ValueError) as error:
            return _record_failure(store, state, state_gen, now, include_demo, str(error), writes)

        source_ts = vacancy_res.last_modified or now
        snapshot, stats = transform(vacancy, info, now, source_ts)

        _, snap_gen = store.read(SNAPSHOT_OBJECT)
        store.write(SNAPSHOT_OBJECT, _dumps(snapshot), snap_gen)
        writes.append(SNAPSHOT_OBJECT)
        _write_manifest(store, snapshot, include_demo, writes)
        new_state = {
            "etag": vacancy_res.etag,
            "sha256": digest,
            "consecutive_failures": 0,
            "last_success_at": now.isoformat(),
            "last_source_snapshot_ts": snapshot["source_snapshot_ts"],
            "last_carpark_count": stats["out"],
        }
        store.write(STATE_OBJECT, _dumps(new_state), state_gen)
        writes.append(STATE_OBJECT)
        return RunResult(0, "written", stats, writes)
    except PreconditionFailed as error:
        return RunResult(0, f"precondition_failed:{error}", {}, writes)


def _record_failure(
    store: ObjectStore,
    state: dict[str, Any],
    state_gen: int,
    now: datetime,
    include_demo: bool,
    message: str,
    writes: list[str],
) -> RunResult:
    failures = int(state.get("consecutive_failures") or 0) + 1
    if failures >= DEGRADED_AFTER_FAILURES:
        snapshot, snap_gen = _read_json(store, SNAPSHOT_OBJECT)
        if snapshot and snapshot.get("source_status") != "degraded":
            snapshot["source_status"] = "degraded"
            store.write(SNAPSHOT_OBJECT, _dumps(snapshot), snap_gen)
            writes.append(SNAPSHOT_OBJECT)
        if snapshot:
            _write_manifest(store, snapshot, include_demo, writes)
    new_state = {
        **state,
        "consecutive_failures": failures,
        "last_failure_at": now.isoformat(),
        "last_error": message[:500],
    }
    store.write(STATE_OBJECT, _dumps(new_state), state_gen)
    writes.append(STATE_OBJECT)
    return RunResult(1, "fetch_failed", {"consecutive_failures": failures, "error": message}, writes)


def dry_run(fetch: Fetcher, out_path: str | None, now: datetime | None = None) -> RunResult:
    now = now or datetime.now(UTC)
    vacancy_res = fetch(VACANCY_URL, None)
    info_res = fetch(INFO_URL, None)
    snapshot, stats = transform(
        _decode_json(vacancy_res.body),
        _decode_json(info_res.body),
        now,
        vacancy_res.last_modified or now,
    )
    manifest = build_manifest(snapshot, None, include_demo=False)
    if out_path:
        with open(out_path, "wb") as handle:
            handle.write(_dumps(snapshot))
    stats = {
        **stats,
        "source_snapshot_ts": snapshot["source_snapshot_ts"],
        "source_status": snapshot["source_status"],
        "bytes": len(_dumps(snapshot)),
        "manifest": manifest,
    }
    return RunResult(0, "dry_run", stats)


def _env_flag(name: str) -> bool:
    return os.environ.get(name, "").strip().lower() in {"1", "true", "yes", "on"}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="python -m app.ingest.td_carpark")
    parser.add_argument("--dry-run", action="store_true", help="fetch + transform only; no GCS")
    parser.add_argument("--out", help="with --dry-run: write the snapshot JSON here")
    parser.add_argument("--bucket", default=os.environ.get("GCS_STATE_BUCKET", ""))
    args = parser.parse_args(argv)

    if args.dry_run:
        result = dry_run(http_fetch, args.out)
    else:
        if not args.bucket:
            print(json.dumps({"severity": "ERROR", "message": "GCS_STATE_BUCKET is not set"}))
            return 2
        result = run_once(
            GcsObjectStore(args.bucket),
            http_fetch,
            include_demo=_env_flag("INCLUDE_DEMO_REGION"),
        )
    print(
        json.dumps(
            {
                "severity": "INFO" if result.exit_code == 0 else "ERROR",
                "component": "td_carpark_ingest",
                "outcome": result.outcome,
                "writes": result.writes,
                "stats": result.stats,
            },
            ensure_ascii=False,
        )
    )
    return result.exit_code


if __name__ == "__main__":
    sys.exit(main())
