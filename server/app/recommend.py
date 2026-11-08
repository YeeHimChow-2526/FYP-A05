import asyncio
import math
import time
from datetime import UTC, datetime
from typing import Any
from zoneinfo import ZoneInfo

from . import scoring
from .lock_policy import CapacityReading
from .config import Settings
from .models import VacancyStatus, CarparkSnapshot, Preference, RecommendRequest, RegionSnapshot
from .poi import PoiCatalog
from .snapshots import SnapshotSource, snapshot_age_seconds


class RecommendationService:
    def __init__(
        self,
        settings: Settings,
        poi_catalog: PoiCatalog,
        snapshots: SnapshotSource,
        repository: Any,
        jitter: scoring.Jitter | None = None,
        clock=None,
    ) -> None:
        self._settings = settings
        self._poi_catalog = poi_catalog
        self._snapshots = snapshots
        self._repository = repository
        self._jitter = jitter or scoring.default_jitter(settings)
        self._clock = clock or (lambda: datetime.now(UTC))
        self._count_semaphore = asyncio.Semaphore(
            settings.firestore_max_concurrent_lock_counts
        )
        self._pain_cache: dict[str, tuple[float, dict[str, Any]]] = {}
        self._perk_cache: dict[str, tuple[float, dict[str, Any]]] = {}

    async def current_vacancies(self, carpark_id: str) -> CapacityReading:
        """Last-known capacity for commit. Stale/degraded snapshots keep the last-known count
        (flagged stale) instead of becoming unlimited; never-known (absent / negative) -> None."""
        # Commit never triggers an upstream refresh (bounded latency inside the lock path); it
        # reads the same published manifest pointer as recommend (memory cache never regresses).
        snapshots = await (
            self._snapshots.load_regions(refresh=False)
            if getattr(self._snapshots, "supports_refresh_flag", False)
            else self._snapshots.load_regions()
        )
        now = self._clock().astimezone(UTC)
        for region in snapshots:
            for carpark in region.carparks:
                if carpark.carpark_id == carpark_id:
                    counted = carpark.effective_status() == VacancyStatus.COUNT
                    value = carpark.vacancies if counted and carpark.vacancies >= 0 else None
                    age = scoring.record_age_seconds(
                        snapshot_age_seconds(region.source_snapshot_ts, now), carpark.source_updated_at, now
                    )
                    stale = region.source_status == "degraded" or (
                        age > self._settings.source_stale_threshold_seconds
                    )
                    if stale and scoring.is_meter(carpark.type):
                        value = None  # card says UNCONFIRMED (classify); commit must agree, not lock a stale meter
                    return CapacityReading(value, stale=stale)
        return CapacityReading(None, stale=True)

    async def recommend(self, request: RecommendRequest) -> dict[str, Any]:
        destination = None
        status = "SUCCESS"
        clarification: dict[str, Any] = {}
        if request.dest_lat is not None and request.dest_lng is not None:
            destination = (request.dest_lat, request.dest_lng)
        elif request.dest_name:
            status, poi, options = self._poi_catalog.resolve(request.dest_name)
            if poi:
                destination = (poi.lat, poi.lng)
                clarification["resolved_destination"] = {
                    "poi_id": poi.poi_id,
                    "name": poi.name,
                    "lat": poi.lat,
                    "lng": poi.lng,
                }
            elif status == "NEED_CLARIFICATION":
                await self._repository.touch_session(
                    request.session_id,
                    destination_epoch=request.destination_epoch,
                    require_open=True,
                    session_started_at=request.started_at(),
                )
                return {
                    "status": status,
                    "clarification_prompt": "你想去邊一個地點？",
                    "options": options,
                }
            else:
                await self._repository.touch_session(
                    request.session_id,
                    destination_epoch=request.destination_epoch,
                    require_open=True,
                    session_started_at=request.started_at(),
                )
                return {"status": "NOT_FOUND", "suggestions": options}

        if destination is None:
            raise ValueError("A destination could not be resolved")
        destination_record = {
            "lat": destination[0],
            "lng": destination[1],
            "name": request.dest_name or "",
        }
        await self._repository.touch_session(
            request.session_id,
            destination_record,
            destination_epoch=request.destination_epoch,
            require_open=True,
            session_started_at=request.started_at(),
        )

        snapshots = await self._snapshots.load_regions()
        now = self._clock().astimezone(ZoneInfo("Asia/Hong_Kong"))
        flattened = [
            (region, carpark)
            for region in snapshots
            for carpark in region.carparks
        ]
        radius = self._settings.search_radius_meters
        if request.expand_scarcity:
            radius = round(radius * self._settings.scarcity_expand_multiplier)

        nearby = [
            (region, carpark)
            for region, carpark in flattened
            if carpark.is_open
            and _distance_meters(destination, (carpark.lat, carpark.lng)) <= radius
        ]
        if request.preference == Preference.INDOOR_ONLY:
            nearby = [entry for entry in nearby if not scoring.is_meter(entry[1].type)]
        def build_pool(scarcity: bool):
            pool, zeros = [], []
            for region, carpark in nearby:
                age = scoring.record_age_seconds(
                    snapshot_age_seconds(region.source_snapshot_ts, now), carpark.source_updated_at, now
                )
                stale = scoring.is_stale(age, self._settings)
                degraded = region.source_status == "degraded"
                decision = scoring.pool_decision(carpark, stale, degraded, self._settings, scarcity)
                if decision == scoring.VALID:
                    pool.append((region, carpark, age, stale, degraded))
                elif decision == scoring.REFERENCE_ONLY:
                    zeros.append((region, carpark, age))
            return pool, zeros

        candidates, fresh_zero = build_pool(False)
        # §9.7: scarcity is decided on the valid pool; it never re-admits excluded records.
        scarcity_mode = len(candidates) < self._settings.scarcity_pool_min_count
        if scarcity_mode:
            candidates, fresh_zero = build_pool(True)  # only relaxes the meter >=2 -> >=1 rule

        candidate_scores = []
        for region, carpark, age, stale, degraded in candidates:
            distance = _distance_meters(destination, (carpark.lat, carpark.lng))
            # Preliminary Top-K: distance + snapshot vacancies only (no remote reads).
            preliminary = scoring.score(
                vacancies=carpark.vacancies, locks=0, distance_m=distance,
                age_seconds=age, settings=self._settings,
            ).total
            candidate_scores.append((preliminary, distance, region, carpark, age, stale, degraded))
        candidate_scores.sort(key=lambda entry: entry[0], reverse=True)

        k = self._settings.lock_count_top_k_candidates
        if request.preference == Preference.METERS_FOCUS:
            # Meters take the Top-K slots first (by score); indoor fills the rest as backup.
            candidate_scores.sort(key=lambda e: not scoring.is_meter(e[3].type))
        top = candidate_scores[:k]
        fallback_id = None
        if request.preference == Preference.SMART_HYBRID and not any(
            scoring.is_meter(e[3].type) for e in top
        ):
            # Fallback meter: no meter made the primary Top-K by score. The best remaining
            # meter takes the LOWEST preliminary slot (never adds a 6th card or extra reads),
            # is flagged is_fallback, and is ordered after every viable primary.
            best_meter = next((e for e in candidate_scores[k:] if scoring.is_meter(e[3].type)), None)
            if best_meter is not None:
                top = top[: k - 1] + [best_meter]
                fallback_id = best_meter[3].carpark_id
        # Bounded remote reads: exactly len(top) <= K lock/pain/perk lookups, no refill.
        lock_counts = await asyncio.gather(
            *(self._count_locks(e[3].carpark_id, request.session_id) for e in top)
        )
        pain_points = await asyncio.gather(*(self._get_pain_point(e[3].carpark_id) for e in top))
        perks = await asyncio.gather(*(self._get_perk(e[3].carpark_id) for e in top))

        cards = []
        for entry, lock_count, pain, perk in zip(top, lock_counts, pain_points, perks, strict=True):
            _, distance, region, carpark, age, stale, degraded = entry
            meter = scoring.is_meter(carpark.type)
            breakdown = scoring.score(
                vacancies=carpark.vacancies, locks=lock_count, distance_m=distance,
                age_seconds=age, settings=self._settings, scarcity=scarcity_mode,
                perk=perk, pain=pain, now=now, jitter=self._jitter(),
            )
            availability = scoring.classify(
                vacancies=carpark.vacancies, locks=lock_count, stale=stale,
                degraded=degraded, meter=meter, status=str(carpark.effective_status()),
            )
            meter_unknown = meter and availability == scoring.UNCONFIRMED
            effective_vacancies = (
                -1 if availability == scoring.UNCONFIRMED else max(carpark.vacancies - lock_count, 0)
            )
            cards.append(
                {
                    "carpark_id": carpark.carpark_id,
                    "name": carpark.name,
                    "type": carpark.type,
                    "lat": carpark.lat,
                    "lng": carpark.lng,
                    "vacancies": effective_vacancies,
                    "distance_meters": distance,
                    "fee_summary": perk.get("fee_summary", carpark.fee_summary),
                    "spoken_cue": "",
                    "is_stale": stale,
                    "crowd_warning": scoring.crowd_active(pain, now),
                    "rank_capped": scoring.crowd_rank_capped(pain, now, self._settings),
                    "_score": breakdown.total,
                    "availability": availability,
                    "vacancy_status": str(carpark.effective_status()),
                    "data_source": region.source or region.region_id,
                    "source_updated_at": carpark.source_updated_at,
                    "commit_allowed": availability in scoring.COMMITTABLE,
                    "is_fallback": carpark.carpark_id == fallback_id,
                    "_meter_unknown": meter_unknown,
                    "_stale": stale,
                    "_degraded": degraded,
                    "_age_seconds": age,
                }
            )
        cards.sort(key=lambda card: card["_score"], reverse=True)
        if scarcity_mode:
            # Reference-only: fresh official 0 nearby, outside the valid pool, appended only to
            # fill up to K cards (no remote reads; V=0 so no lock lookup is needed).
            fresh_zero.sort(key=lambda e: _distance_meters(destination, (e[1].lat, e[1].lng)))
            for region, carpark, age in fresh_zero[: max(k - len(cards), 0)]:
                cards.append(self._reference_card(region, carpark, age, destination))
        cards = scoring.arrange(cards, request.preference.value)
        scoring.compose_cues(cards, self._settings)
        for card in cards:
            for key in [key for key in card if key.startswith("_")]:
                card.pop(key)

        used_regions = []
        for _, _, region, *_ in top:
            if all(existing.region_id != region.region_id for existing in used_regions):
                used_regions.append(region)
        if not used_regions:
            used_regions = snapshots
        source_status = (
            "degraded"
            if any(region.source_status == "degraded" for region in used_regions)
            else "ok"
        )
        oldest_region = max(
            used_regions,
            key=lambda region: snapshot_age_seconds(region.source_snapshot_ts, now),
            default=None,
        )
        if not cards:
            status = "SUCCESS"
        speech_locale = request.speech_locale if request.speech_locale == "zh-HK" else "zh-HK"
        response = {
            "status": status,
            "speech_locale": speech_locale,
            "source_status": source_status,
            "source_snapshot_ts": (
                _format_hkt(oldest_region.source_snapshot_ts)
                if oldest_region
                else now.isoformat()
            ),
            "processed_at": (
                _format_hkt(oldest_region.processed_at)
                if oldest_region
                else now.isoformat()
            ),
            "server_now": now.isoformat(),
            "data_age_seconds": (
                snapshot_age_seconds(oldest_region.source_snapshot_ts, now)
                if oldest_region
                else 0
            ),
            "cards": cards,
        }
        if speech_locale != request.speech_locale:
            response["speech_locale_fallback"] = True
        if clarification:
            response["resolved_destination"] = clarification["resolved_destination"]
        if not any(card["availability"] in scoring.VIABLE for card in cards):
            response.update(_no_viable_prompt(request.expand_scarcity))
        elif not any(card["commit_allowed"] for card in cards):
            # Only unconfirmed options: say so up front; nothing here can be reserved.
            response["spoken_cue"] = "附近暫時冇可以預留嘅車位，以下只係空位未確認嘅選擇。"
            response["await_voice_reply"] = False
        return response

    async def _count_locks(self, carpark_id: str, session_id: str) -> int:
        async with self._count_semaphore:
            return await self._repository.count_locks(
                carpark_id,
                exclude_session_id=session_id,
            )

    async def _get_pain_point(self, carpark_id: str) -> dict[str, Any]:
        now = time.monotonic()
        cached = self._pain_cache.get(carpark_id)
        if cached and cached[0] > now:
            return cached[1]
        pain_point = await self._repository.get_pain_point(carpark_id)
        self._pain_cache[carpark_id] = (
            now + self._settings.in_memory_cache_ttl_seconds,
            pain_point,
        )
        return pain_point

    async def _get_perk(self, carpark_id: str) -> dict[str, Any]:
        now = time.monotonic()
        cached = self._perk_cache.get(carpark_id)
        if cached and cached[0] > now:
            return cached[1]
        perk = await self._repository.get_perk(carpark_id)
        self._perk_cache[carpark_id] = (
            now + self._settings.in_memory_cache_ttl_seconds,
            perk,
        )
        return perk

    def _reference_card(self, region, carpark, age, destination) -> dict[str, Any]:
        return {
            "carpark_id": carpark.carpark_id, "name": carpark.name, "type": carpark.type,
            "lat": carpark.lat, "lng": carpark.lng, "vacancies": 0,
            "distance_meters": _distance_meters(destination, (carpark.lat, carpark.lng)),
            "fee_summary": carpark.fee_summary, "spoken_cue": "", "is_stale": False,
            "crowd_warning": False, "rank_capped": False,
            "availability": scoring.NO_AVAILABILITY, "commit_allowed": False, "is_fallback": False,
            "vacancy_status": "COUNT", "data_source": region.source or region.region_id,
            "source_updated_at": carpark.source_updated_at,
            "_score": 0.0, "_meter_unknown": False, "_stale": False, "_degraded": False,
            "_age_seconds": age,
        }


def _no_viable_prompt(expanded: bool) -> dict[str, Any]:
    """No actionable lead (every card is reference-only, or no card). Ask; never auto-expand."""
    if expanded:
        return {"spoken_cue": "擴大範圍後都冇確認有位嘅車場。", "await_voice_reply": False}
    return {
        "spoken_cue": "附近暫時冇確認有位嘅車場。要唔要幫你搵遠少少、大概三公里外嘅場？",
        "await_voice_reply": True,
    }


def _distance_meters(start: tuple[float, float], end: tuple[float, float]) -> int:
    earth_radius_meters = 6_371_000
    start_lat, start_lng = map(math.radians, start)
    end_lat, end_lng = map(math.radians, end)
    delta_lat = end_lat - start_lat
    delta_lng = end_lng - start_lng
    value = (
        math.sin(delta_lat / 2) ** 2
        + math.cos(start_lat) * math.cos(end_lat) * math.sin(delta_lng / 2) ** 2
    )
    return round(earth_radius_meters * 2 * math.atan2(math.sqrt(value), math.sqrt(1 - value)))


def _format_hkt(timestamp: str) -> str:
    return datetime.fromisoformat(timestamp).astimezone(
        ZoneInfo("Asia/Hong_Kong")
    ).isoformat()
