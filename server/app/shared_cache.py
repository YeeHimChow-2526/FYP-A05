"""Shared on-demand snapshot refresh over ONE GCS manifest (plan §5, option B).

Protocol (all writers: API on-demand refresh and the ingest Job/CLI):
  1. read manifest.json at its CURRENT generation G (never the 45 s memory cache)
  2. nothing due (per-source checked_at < min interval, or backoff) -> stop
     lease held by someone else and not expired -> stop (serve what is published)
  3. acquire: write {owner_token, lease_until} with ifGenerationMatch=G (0 = first bootstrap) -> G1
     412 -> another writer won: stop, no retry
  4. per due source: conditional fetch; on 200 write an IMMUTABLE snapshot object
     (new name, ifGenerationMatch=0); 304 / same hash only advances checked_at
  5. publish: write the manifest with ifGenerationMatch=G1 exactly; 412 -> fenced, never rebase/retry

Guarantee: once another writer has advanced the manifest (taken over an expired lease, or any
publish), the old leader's publish fails (G1 no longer current). NOT guaranteed: a leader whose
lease merely expired, with nobody taking over, can still publish late (ownership semantics).
source_snapshot_ts never regresses: an older publisher time is not published (checked_at only).
checked_at / source_snapshot_ts are never set from a failed fetch, and source time is never the
fetch time. Orphaned immutable objects (fenced runs) need a bucket lifecycle rule (rollout note).
"""

from __future__ import annotations

import csv
import hashlib
import io
import json
import uuid
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from typing import Any, Callable

from pydantic import ValidationError

from .ingest import td_carpark as td
from .ingest import td_meters
from .models import SnapshotManifest, SourceState

MANIFEST_OBJECT = "manifest.json"
TD_SOURCE = "td_carpark"
METER_SOURCE = "td_meters"
METER_SPACES_URL = "https://resource.data.one.gov.hk/td/psiparkingspaces/spaceinfo/parkingspaces.csv"
METER_OCCUPANCY_URL = "https://resource.data.one.gov.hk/td/psiparkingspaces/occupancystatus/occupancystatus.csv"

Fetch = Callable[[str, "str | None"], td.FetchResult]


@dataclass
class RefreshConfig:
    min_interval_seconds: int = 60
    lease_seconds: int = 45
    static_ttl_seconds: int = 21600
    backoff_base_seconds: int = 30
    backoff_max_seconds: int = 600
    meters_enabled: bool = False

    @classmethod
    def from_settings(cls, s: Any) -> "RefreshConfig":
        return cls(s.snapshot_refresh_min_interval_seconds, s.snapshot_lease_seconds,
                   s.snapshot_static_ttl_seconds, s.snapshot_failure_backoff_base_seconds,
                   s.snapshot_failure_backoff_max_seconds, s.meter_refresh_enabled)


@dataclass
class RefreshResult:
    outcome: str  # fresh | leased | lost_lease | published | fenced | bad_manifest
    generation: int
    manifest: dict[str, Any] | None = None
    sources: dict[str, str] = field(default_factory=dict)


def _ts(value: str | None) -> datetime | None:
    return datetime.fromisoformat(value) if value else None


def _dumps(payload: dict[str, Any]) -> bytes:
    return json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def read_manifest(store: td.ObjectStore) -> tuple[dict[str, Any] | None, int, bool]:
    """Fresh read at the current generation -> (payload, generation, valid)."""
    data, generation = store.read(MANIFEST_OBJECT)
    if data is None:
        return None, 0, True
    try:
        payload = json.loads(data.decode("utf-8"))
        SnapshotManifest.model_validate(payload)
    except (UnicodeDecodeError, json.JSONDecodeError, ValidationError, TypeError):
        return None, generation, False
    return payload, generation, True


def _due(state: SourceState, now: datetime, cfg: RefreshConfig, force: bool) -> bool:
    nxt = _ts(state.next_attempt_after)
    if nxt is not None and now < nxt:
        return False  # backoff applies to the Job too (no stampede on a failing upstream)
    checked = _ts(state.checked_at)
    return force or checked is None or (now - checked).total_seconds() >= cfg.min_interval_seconds


def _fail(state: SourceState, now: datetime, cfg: RefreshConfig, error: str) -> None:
    state.consecutive_failures += 1
    delay = min(cfg.backoff_base_seconds * 2 ** (state.consecutive_failures - 1), cfg.backoff_max_seconds)
    state.next_attempt_after = (now + timedelta(seconds=delay)).isoformat()
    state.last_error = error[:500]


def _ok(state: SourceState, now: datetime) -> None:
    state.checked_at = now.isoformat()
    state.consecutive_failures = 0
    state.next_attempt_after = None
    state.last_error = None


def _static(store, state: SourceState, fetch: Fetch, url: str, prefix: str, now: datetime,
            cfg: RefreshConfig, validate: Callable[[bytes], Any]) -> Any:
    """Static metadata with its own ETag + TTL, cached as an immutable GCS object.
    A failed or invalid update keeps the previous object (rollback = pointer unchanged)."""
    cached = None
    if state.static_object:
        data, _ = store.read(state.static_object)
        cached = validate(data) if data is not None else None
    fresh = _ts(state.static_checked_at)
    if cached is not None and fresh and (now - fresh).total_seconds() < cfg.static_ttl_seconds:
        return cached
    try:
        res = fetch(url, state.static_etag if cached is not None else None)
        if res.status == 304 and cached is not None:
            state.static_checked_at = now.isoformat()
            return cached
        parsed = validate(res.body)
        name = f"{prefix}/{hashlib.sha256(res.body).hexdigest()[:24]}"
        try:
            store.write(name, res.body, 0)
        except td.PreconditionFailed:
            pass  # same content-addressed object already exists
        state.static_object, state.static_etag = name, res.etag
        state.static_checked_at = now.isoformat()
        return parsed
    except (td.FetchError, ValueError, TypeError, KeyError):
        if cached is not None:
            return cached  # keep previous metadata; retried after TTL
        raise


def _publish_region(store, manifest: dict, region_id: str, snapshot: dict, token: str, digest: str) -> None:
    ts = datetime.fromisoformat(snapshot["source_snapshot_ts"]).astimezone(UTC)
    name = f"regions/{region_id}/snapshots/{ts:%Y%m%dT%H%M%SZ}-{digest[:12]}-{token[:8]}.json"
    store.write(name, _dumps(snapshot), 0)
    manifest["regions"][region_id] = {
        "object": name,
        "source_snapshot_ts": snapshot["source_snapshot_ts"],
        "source_status": snapshot["source_status"],
    }


def _not_older(manifest: dict, region_id: str, source_ts: datetime) -> bool:
    prev = _ts((manifest["regions"].get(region_id) or {}).get("source_snapshot_ts"))
    return prev is None or source_ts >= prev


def _td_source_ts(res: td.FetchResult, vacancy: dict) -> datetime:
    """Publisher time: HTTP Last-Modified, else the newest per-car-park lastupdate.
    Never the fetch time; no timestamp at all is a failure."""
    if res.last_modified is not None:
        return res.last_modified
    stamps = []
    for row in vacancy.get("car_park") or []:
        for vehicle in row.get("vehicle_type") or []:
            for cat in vehicle.get("service_category") or []:
                try:
                    stamps.append(td._parse_hkt(cat["lastupdate"]))
                except (KeyError, TypeError, ValueError):
                    pass
    if not stamps:
        raise ValueError("no publisher timestamp in TD vacancy feed")
    return max(stamps)


def _validate_td_info(body: bytes) -> dict:
    info = td._decode_json(body)
    if not isinstance(info.get("car_park"), list) or not info["car_park"]:
        raise ValueError("TD basic_info has no car_park list")
    return info


def refresh_td_carpark(store, manifest: dict, state: SourceState, fetch: Fetch, now: datetime,
                       token: str, cfg: RefreshConfig) -> str:
    res = fetch(td.VACANCY_URL, state.etag)
    if res.status == 304:
        _ok(state, now)
        return "not_modified"
    digest = hashlib.sha256(res.body).hexdigest()
    if digest == state.sha256 and td.REGION_ID in manifest["regions"]:
        state.etag = res.etag
        _ok(state, now)
        return "unchanged"
    vacancy = td._decode_json(res.body)
    info = _static(store, state, fetch, td.INFO_URL, "static/td_info", now, cfg, _validate_td_info)
    source_ts = _td_source_ts(res, vacancy)
    if not _not_older(manifest, td.REGION_ID, source_ts):
        _ok(state, now)  # successful check, but never publish an older publisher time
        state.last_error = "source_clock_backwards"
        return "source_clock_backwards"
    snapshot, _ = td.transform(vacancy, info, now, source_ts)
    _publish_region(store, manifest, td.REGION_ID, snapshot, token, digest)
    state.etag, state.sha256, state.source_snapshot_ts = res.etag, digest, snapshot["source_snapshot_ts"]
    _ok(state, now)
    return "published"


def _meter_source_ts(res: td.FetchResult, occupancy_csv: str) -> datetime:
    """Last-Modified, else the newest OccupancyDateChanged (a lower bound: data is at least that
    new). Never the fetch time."""
    if res.last_modified is not None:
        return res.last_modified
    stamps = []
    for row in csv.DictReader(io.StringIO(occupancy_csv.lstrip("\ufeff"))):
        try:
            stamps.append(datetime.strptime(row["OccupancyDateChanged"].strip(), "%m/%d/%Y %I:%M:%S %p")
                          .replace(tzinfo=td.HKT))
        except (KeyError, AttributeError, ValueError):
            pass
    if not stamps:
        raise ValueError("no publisher timestamp in meter occupancy feed")
    return max(stamps)


def refresh_td_meters(store, manifest: dict, state: SourceState, fetch: Fetch, now: datetime,
                      token: str, cfg: RefreshConfig) -> str:
    res = fetch(METER_OCCUPANCY_URL, state.etag)
    if res.status == 304:
        _ok(state, now)
        return "not_modified"
    digest = hashlib.sha256(res.body).hexdigest()
    if digest == state.sha256 and td_meters.REGION_ID in manifest["regions"]:
        state.etag = res.etag
        _ok(state, now)
        return "unchanged"
    occupancy = res.body.decode("utf-8-sig")

    def validate_spaces(body: bytes) -> str:
        text = body.decode("utf-8-sig")
        td_meters._rows(text, "PoleId")  # header must exist
        return text

    spaces = _static(store, state, fetch, METER_SPACES_URL, "static/td_meter_spaces", now, cfg, validate_spaces)
    source_ts = _meter_source_ts(res, occupancy)
    if not _not_older(manifest, td_meters.REGION_ID, source_ts):
        _ok(state, now)
        state.last_error = "source_clock_backwards"
        return "source_clock_backwards"
    snapshot = td_meters.transform(spaces, occupancy, source_ts, now)
    _publish_region(store, manifest, td_meters.REGION_ID, snapshot, token, digest)
    state.etag, state.sha256, state.source_snapshot_ts = res.etag, digest, snapshot["source_snapshot_ts"]
    _ok(state, now)
    return "published"


REFRESHERS = {TD_SOURCE: (td.REGION_ID, refresh_td_carpark), METER_SOURCE: (td_meters.REGION_ID, refresh_td_meters)}


def refresh_once(store: td.ObjectStore, fetch: Fetch, cfg: RefreshConfig, *,
                 clock: Callable[[], datetime] = lambda: datetime.now(UTC),
                 token: str | None = None, force: bool = False,
                 extra_regions: dict[str, dict[str, str]] | None = None) -> RefreshResult:
    token = token or uuid.uuid4().hex
    now = clock()
    manifest, generation, valid = read_manifest(store)
    if not valid:
        return RefreshResult("bad_manifest", generation)  # never overwrite an unreadable manifest
    manifest = manifest or {"regions": {}}
    model = SnapshotManifest.model_validate(manifest)
    enabled = [TD_SOURCE] + ([METER_SOURCE] if cfg.meters_enabled else [])
    states = {name: model.sources.get(name, SourceState()) for name in enabled}
    due = [n for n in enabled if _due(states[n], now, cfg, force)]
    missing_extra = {k: v for k, v in (extra_regions or {}).items() if k not in manifest["regions"]}
    if not due and not missing_extra:
        return RefreshResult("fresh", generation, manifest)
    lease_until = _ts(model.lease_until)
    if model.owner_token and model.owner_token != token and lease_until and lease_until > now:
        return RefreshResult("leased", generation, manifest)
    leased = dict(manifest, owner_token=token, lease_until=(now + timedelta(seconds=cfg.lease_seconds)).isoformat())
    try:
        g1 = store.write(MANIFEST_OBJECT, _dumps(leased), generation)
    except td.PreconditionFailed:
        return RefreshResult("lost_lease", generation)

    work = json.loads(json.dumps(manifest))
    work["regions"].update(missing_extra)  # e.g. MK_DEMO pointer; never removes other regions
    outcomes: dict[str, str] = {}
    for name in due:
        state = states[name]
        region_id, refresher = REFRESHERS[name]
        try:
            outcomes[name] = refresher(store, work, state, fetch, clock(), token, cfg)
        except (td.FetchError, ValueError, TypeError, KeyError, ValidationError) as error:
            _fail(state, clock(), cfg, f"{type(error).__name__}: {error}")
            if state.consecutive_failures >= td.DEGRADED_AFTER_FAILURES and region_id in work["regions"]:
                work["regions"][region_id] = dict(work["regions"][region_id], source_status="degraded")
            outcomes[name] = "failed"
        except td.PreconditionFailed:
            outcomes[name] = "object_conflict"  # immutable name collision; leave pointer unchanged
    sources = {k: v.model_dump(mode="json") for k, v in model.sources.items()}
    sources.update({n: states[n].model_dump(mode="json") for n in enabled})
    work.update(sources=sources, owner_token=None, lease_until=None,
                checked_at=states[TD_SOURCE].checked_at)
    work.setdefault("source", "TD")
    if work.get("is_demo") is None or any(r != "MK_DEMO" for r in work["regions"]):
        work["is_demo"] = not any(r != "MK_DEMO" for r in work["regions"]) and bool(work["regions"])
    if "MK_DEMO" in work["regions"]:
        work["includes_demo_region"] = True
    SnapshotManifest.model_validate(work)
    try:
        g2 = store.write(MANIFEST_OBJECT, _dumps(work), g1)
    except td.PreconditionFailed:
        return RefreshResult("fenced", g1, None, outcomes)  # someone advanced past us: discard
    return RefreshResult("published", g2, work, outcomes)
