import asyncio
import json
import time
from dataclasses import dataclass
from datetime import datetime
from typing import Protocol

from .models import RegionSnapshot, SnapshotManifest


class SnapshotUnavailable(RuntimeError):
    pass


class SnapshotSource(Protocol):
    async def load_regions(self) -> list[RegionSnapshot]: ...


@dataclass
class _CachedBlob:
    generation: str
    expires_at: float
    value: dict


class GcsSnapshotSource:
    """Reads manifest.json -> region objects. Region objects written by the shared refresh are
    immutable (new name per publish), so caching them by generation is safe; the manifest is
    cached for cache_ttl_seconds (45 s) but primed whenever this instance sees a newer
    generation (own refresh / fresh lease read), and a newer generation always wins."""

    supports_refresh_flag = True

    def __init__(self, bucket_name: str, cache_ttl_seconds: int, *, store=None, settings=None,
                 fetch=None, clock=None) -> None:
        if not bucket_name and store is None:
            raise ValueError("GCS_STATE_BUCKET must be configured")
        if store is None:
            from .ingest.td_carpark import GcsObjectStore

            store = GcsObjectStore(bucket_name)
        self._store = store
        self._cache_ttl_seconds = cache_ttl_seconds
        self._cache: dict[tuple[str, str], _CachedBlob] = {}
        self._latest_generation: dict[str, str] = {}
        self._settings = settings
        self._fetch = fetch
        self._clock = clock
        self._refresh_task: asyncio.Task | None = None
        self._next_local_attempt = 0.0
        self.last_refresh = None  # RefreshResult of the latest completed attempt (observability)

    # ------------------------------------------------------------------ cache
    def prime(self, object_name: str, generation: int, payload: dict) -> None:
        current = self._latest_generation.get(object_name)
        if current is not None and int(current) > generation:
            return  # never go back to an older generation
        if current is not None:
            self._cache.pop((object_name, current), None)
        self._latest_generation[object_name] = str(generation)
        self._cache[(object_name, str(generation))] = _CachedBlob(
            str(generation), time.monotonic() + self._cache_ttl_seconds, payload
        )

    async def _read_json(self, object_name: str) -> dict:
        now = time.monotonic()
        generation = self._latest_generation.get(object_name)
        cached = self._cache.get((object_name, generation)) if generation is not None else None
        if cached and cached.expires_at > now:
            return cached.value

        def download() -> tuple[int, dict]:
            data, generation = self._store.read(object_name)
            if data is None:
                raise SnapshotUnavailable(f"Missing GCS object: {object_name}")
            payload = json.loads(data.decode("utf-8-sig"))
            if not isinstance(payload, dict):
                raise SnapshotUnavailable(f"Expected JSON object: {object_name}")
            return generation, payload

        generation, payload = await asyncio.to_thread(download)
        self.prime(object_name, generation, payload)
        return self._cache[(object_name, self._latest_generation[object_name])].value

    # ------------------------------------------------------------------ refresh
    def _refresh_due_locally(self) -> bool:
        from .shared_cache import MANIFEST_OBJECT

        if time.monotonic() < self._next_local_attempt:
            return False
        gen = self._latest_generation.get(MANIFEST_OBJECT)
        cached = self._cache.get((MANIFEST_OBJECT, gen)) if gen else None
        if cached is None:
            return True
        checked = cached.value.get("checked_at")
        if not checked:
            return True
        age = (self._now() - datetime.fromisoformat(checked)).total_seconds()
        return age >= self._settings.snapshot_refresh_min_interval_seconds

    def _now(self):
        from datetime import UTC

        return self._clock() if self._clock else datetime.now(UTC)

    def _run_refresh_sync(self):
        from functools import partial

        from . import shared_cache
        from .ingest import td_carpark as td

        fetch = self._fetch or partial(
            td.http_fetch, timeout=self._settings.snapshot_fetch_timeout_seconds, attempts=1
        )
        return shared_cache.refresh_once(
            self._store, fetch, shared_cache.RefreshConfig.from_settings(self._settings),
            clock=self._now,
        )

    async def _refresh(self) -> None:
        from .shared_cache import MANIFEST_OBJECT

        try:
            result = await asyncio.to_thread(self._run_refresh_sync)
            self.last_refresh = result
            if result.manifest is not None:
                self.prime(MANIFEST_OBJECT, result.generation, result.manifest)
        except Exception:  # noqa: BLE001 - refresh must never break serving
            self.last_refresh = None
        finally:
            # local throttle so one instance does not re-read the manifest on every request
            self._next_local_attempt = time.monotonic() + 5

    async def maybe_refresh(self) -> None:
        """Single-flight per instance. With data already cached, a request waits at most
        snapshot_refresh_wait_seconds and then serves the published (possibly stale) data; the
        refresh keeps running only while the instance has CPU (Cloud Run does not guarantee work
        after the response) — an abandoned lease simply expires and the next caller takes over.
        With nothing cached (cold instance) it waits for the bounded refresh."""
        if self._settings is None or not self._settings.snapshot_on_demand_refresh_enabled:
            return
        if self._refresh_task is None or self._refresh_task.done():
            if not self._refresh_due_locally():
                return
            self._refresh_task = asyncio.create_task(self._refresh())
        from .shared_cache import MANIFEST_OBJECT

        have_data = MANIFEST_OBJECT in self._latest_generation
        budget = (
            self._settings.snapshot_refresh_wait_seconds
            if have_data
            else self._settings.snapshot_lease_seconds
        )
        try:
            await asyncio.wait_for(asyncio.shield(self._refresh_task), budget)
        except TimeoutError:
            pass

    # ------------------------------------------------------------------ read
    async def load_regions(self, refresh: bool = True) -> list[RegionSnapshot]:
        if refresh:
            await self.maybe_refresh()
        try:
            manifest_data = await self._read_json("manifest.json")
            manifest = SnapshotManifest.model_validate(manifest_data)
            if not manifest.regions:
                raise SnapshotUnavailable("manifest.json contains no region snapshots")
            regions = await asyncio.gather(
                *(
                    self._read_region(region_id, region_info)
                    for region_id, region_info in manifest.regions.items()
                )
            )
            return regions
        except SnapshotUnavailable:
            raise
        except (KeyError, ValueError, TypeError) as error:
            raise SnapshotUnavailable("Invalid GCS snapshot manifest or region data") from error

    async def _read_region(self, region_id: str, region_info: dict[str, str]) -> RegionSnapshot:
        object_name = region_info["object"]
        payload = dict(await self._read_json(object_name))
        payload.setdefault("region_id", region_id)
        if region_info.get("source_status") == "degraded":
            payload["source_status"] = "degraded"  # manifest-level degrade after repeated failures
        try:
            return RegionSnapshot.model_validate(payload)
        except ValueError as error:
            raise SnapshotUnavailable(f"Invalid region snapshot: {object_name}") from error


def snapshot_age_seconds(timestamp: str, now: datetime) -> int:
    snapshot_time = datetime.fromisoformat(timestamp)
    if snapshot_time.tzinfo is None:
        raise ValueError("source_snapshot_ts must include a timezone")
    return max(0, int((now - snapshot_time).total_seconds()))
