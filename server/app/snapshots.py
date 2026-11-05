import asyncio
import json
import time
from dataclasses import dataclass
from datetime import datetime
from typing import Protocol

from google.cloud import storage

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
    def __init__(self, bucket_name: str, cache_ttl_seconds: int) -> None:
        if not bucket_name:
            raise ValueError("GCS_STATE_BUCKET must be configured")
        self._bucket_name = bucket_name.removeprefix("gs://")
        self._cache_ttl_seconds = cache_ttl_seconds
        self._client = storage.Client()
        self._cache: dict[tuple[str, str], _CachedBlob] = {}
        self._latest_generation: dict[str, str] = {}

    async def _read_json(self, object_name: str) -> dict:
        now = time.monotonic()
        generation = self._latest_generation.get(object_name)
        cached = (
            self._cache.get((object_name, generation))
            if generation is not None
            else None
        )
        if cached and cached.expires_at > now:
            return cached.value

        def download() -> tuple[str, dict]:
            blob = self._client.bucket(self._bucket_name).get_blob(object_name)
            if blob is None:
                raise SnapshotUnavailable(f"Missing GCS object: {object_name}")
            generation = str(blob.generation)
            payload = json.loads(blob.download_as_text())
            if not isinstance(payload, dict):
                raise SnapshotUnavailable(f"Expected JSON object: {object_name}")
            return generation, payload

        generation, payload = await asyncio.to_thread(download)
        previous_generation = self._latest_generation.get(object_name)
        if previous_generation is not None and previous_generation != generation:
            self._cache.pop((object_name, previous_generation), None)
        self._latest_generation[object_name] = generation
        self._cache[(object_name, generation)] = _CachedBlob(
            generation=generation,
            expires_at=now + self._cache_ttl_seconds,
            value=payload,
        )
        return payload

    async def load_regions(self) -> list[RegionSnapshot]:
        try:
            manifest_data = await self._read_json("manifest.json")
            manifest = SnapshotManifest.model_validate(manifest_data)
            if not manifest.regions:
                raise SnapshotUnavailable("manifest.json contains no region snapshots")
            regions = await asyncio.gather(
                *(
                    self._read_region(region_id, region_info["object"])
                    for region_id, region_info in manifest.regions.items()
                )
            )
            return regions
        except SnapshotUnavailable:
            raise
        except (KeyError, ValueError, TypeError) as error:
            raise SnapshotUnavailable("Invalid GCS snapshot manifest or region data") from error

    async def _read_region(self, region_id: str, object_name: str) -> RegionSnapshot:
        payload = await self._read_json(object_name)
        payload.setdefault("region_id", region_id)
        try:
            return RegionSnapshot.model_validate(payload)
        except ValueError as error:
            raise SnapshotUnavailable(f"Invalid region snapshot: {object_name}") from error


def snapshot_age_seconds(timestamp: str, now: datetime) -> int:
    snapshot_time = datetime.fromisoformat(timestamp)
    if snapshot_time.tzinfo is None:
        raise ValueError("source_snapshot_ts must include a timezone")
    return max(0, int((now - snapshot_time).total_seconds()))
