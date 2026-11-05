import asyncio
import math
import time
from datetime import UTC, datetime
from typing import Any
from zoneinfo import ZoneInfo

from .config import Settings
from .models import CarparkSnapshot, Preference, RecommendRequest, RegionSnapshot
from .poi import PoiCatalog
from .snapshots import SnapshotSource, snapshot_age_seconds


class RecommendationService:
    def __init__(
        self,
        settings: Settings,
        poi_catalog: PoiCatalog,
        snapshots: SnapshotSource,
        repository: Any,
    ) -> None:
        self._settings = settings
        self._poi_catalog = poi_catalog
        self._snapshots = snapshots
        self._repository = repository
        self._count_semaphore = asyncio.Semaphore(
            settings.firestore_max_concurrent_lock_counts
        )
        self._pain_cache: dict[str, tuple[float, dict[str, Any]]] = {}
        self._perk_cache: dict[str, tuple[float, dict[str, Any]]] = {}

    async def current_vacancies(self, carpark_id: str) -> int:
        snapshots = await self._snapshots.load_regions()
        now = datetime.now(UTC)
        for region in snapshots:
            for carpark in region.carparks:
                if carpark.carpark_id == carpark_id:
                    if region.source_status == "degraded":
                        return -1
                    if (
                        snapshot_age_seconds(region.source_snapshot_ts, now)
                        > self._settings.source_stale_threshold_seconds
                    ):
                        return -1
                    return carpark.vacancies
        return -1

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
                await self._repository.touch_session(request.session_id)
                return {
                    "status": status,
                    "clarification_prompt": "你想去邊一個地點？",
                    "options": options,
                }
            else:
                await self._repository.touch_session(request.session_id)
                return {"status": "NOT_FOUND", "suggestions": options}

        if destination is None:
            raise ValueError("A destination could not be resolved")
        destination_record = {
            "lat": destination[0],
            "lng": destination[1],
            "name": request.dest_name or "",
        }
        await self._repository.touch_session(request.session_id, destination_record)

        snapshots = await self._snapshots.load_regions()
        now = datetime.now(ZoneInfo("Asia/Hong_Kong"))
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
        candidates = [entry for entry in nearby if entry[1].vacancies != 0]
        scarcity_mode = len(candidates) < self._settings.scarcity_pool_min_count
        if scarcity_mode:
            candidates = nearby

        candidate_scores = []
        for region, carpark in candidates:
            distance = _distance_meters(destination, (carpark.lat, carpark.lng))
            age = snapshot_age_seconds(region.source_snapshot_ts, now)
            preliminary = self._score(
                carpark.vacancies,
                0,
                distance,
                age,
                request.preference,
            )
            if scarcity_mode:
                preliminary += self._settings.scarcity_bonus_points
            candidate_scores.append((preliminary, distance, region, carpark, age))
        candidate_scores.sort(key=lambda entry: entry[0], reverse=True)

        top = candidate_scores[: self._settings.lock_count_top_k_candidates]
        lock_counts = await asyncio.gather(
            *(
                self._count_locks(carpark.carpark_id, request.session_id)
                for _, _, _, carpark, _ in top
            )
        )
        lock_by_id = {
            entry[3].carpark_id: count
            for entry, count in zip(top, lock_counts, strict=True)
        }
        pain_points = await asyncio.gather(
            *(self._get_pain_point(carpark.carpark_id) for _, _, _, carpark, _ in top)
        )
        perks = await asyncio.gather(
            *(self._get_perk(carpark.carpark_id) for _, _, _, carpark, _ in top)
        )
        pain_by_id = {
            entry[3].carpark_id: pain
            for entry, pain in zip(top, pain_points, strict=True)
        }
        perk_by_id = {
            entry[3].carpark_id: perk
            for entry, perk in zip(top, perks, strict=True)
        }

        cards = []
        for _, distance, region, carpark, age in candidate_scores[
            : self._settings.lock_count_top_k_candidates
        ]:
            lock_count = lock_by_id.get(carpark.carpark_id, 0)
            pain = pain_by_id.get(carpark.carpark_id, {})
            perk = perk_by_id.get(carpark.carpark_id, {})
            crowd_active = pain.get("crowd_window_expires_at", now) > now
            effective_vacancies = (
                -1 if carpark.vacancies == -1 else max(carpark.vacancies - lock_count, 0)
            )
            is_stale = age > self._settings.source_stale_threshold_seconds
            degraded = region.source_status == "degraded"
            is_meter = carpark.type.upper() in {"METER", "METERS"}
            if is_meter and (is_stale or degraded):
                effective_vacancies = -1
                spoken_cue = "附近可能有咪錶，資料暫時未更新"
            else:
                spoken_cue = self._spoken_cue(carpark, effective_vacancies, distance)
            score = self._score(
                carpark.vacancies,
                lock_count,
                distance,
                age,
                request.preference,
            )
            if crowd_active:
                score -= int(pain.get("crowd_penalty_points", 0))
            if crowd_active and pain.get("crowd_rank_capped"):
                spoken_cue += "近期有司機回報疑似霸位，請留意現場。"
            cards.append(
                {
                    "carpark_id": carpark.carpark_id,
                    "name": carpark.name,
                    "type": carpark.type,
                    "vacancies": effective_vacancies,
                    "distance_meters": distance,
                    "fee_summary": perk.get("fee_summary", carpark.fee_summary),
                    "spoken_cue": spoken_cue,
                    "is_stale": is_stale,
                    "crowd_warning": bool(crowd_active and pain.get("crowd_warning", False)),
                    "rank_capped": bool(crowd_active and pain.get("crowd_rank_capped", False)),
                    "_score": score,
                }
            )
        cards.sort(key=lambda card: card["_score"], reverse=True)
        if any(card["rank_capped"] for card in cards) and any(
            not card["rank_capped"] for card in cards
        ):
            unreported = [card for card in cards if not card["rank_capped"]]
            capped = [card for card in cards if card["rank_capped"]]
            cards = unreported + capped
        low_watermark_card = next(
            (
                card
                for card in cards
                if 0 <= card["vacancies"] <= self._settings.low_watermark_vacancies
            ),
            None,
        )
        if low_watermark_card is not None:
            backup = next(
                (
                    card
                    for card in cards
                    if card["carpark_id"] != low_watermark_card["carpark_id"]
                ),
                None,
            )
            if backup is not None:
                low_watermark_card["spoken_cue"] += f" 後備可以去{backup['name']}。"
        for card in cards:
            card.pop("_score")

        used_regions = []
        for _, _, region, _, _ in top:
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
        response.update(self._scarcity_prompt(candidates, cards, request.expand_scarcity))
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

    def _score(
        self,
        vacancies: int,
        lock_count: int,
        distance: int,
        age_seconds: int,
        preference: Preference,
    ) -> float:
        remaining = max(vacancies - lock_count, 0) if vacancies >= 0 else -1
        distance_score = 1 - min(distance / self._settings.d_max_meters, 1)
        vacancy_score = (
            min(remaining / self._settings.vacancy_score_denominator, 1)
            if remaining >= 0
            else 0.5
        )
        score = self._settings.w_dist * distance_score + self._settings.w_vac * vacancy_score
        age_minutes = age_seconds / 60
        if age_minutes >= self._settings.stale_tier2_minutes:
            score -= self._settings.stale_tier2_penalty_points
        elif age_minutes >= self._settings.stale_tier1_minutes:
            score -= self._settings.stale_tier1_penalty_points
        if preference == Preference.NEAREST:
            score += self._settings.w_dist * distance_score
        elif preference == Preference.MOST_VACANCIES and remaining >= 0:
            score += self._settings.w_vac * vacancy_score
        return score

    def _spoken_cue(
        self,
        carpark: CarparkSnapshot,
        vacancies: int,
        distance: int,
    ) -> str:
        if vacancies == -1:
            return f"前方首選{carpark.name}，{distance}米，空位資料暫時未更新。"
        if vacancies <= self._settings.low_watermark_vacancies:
            return f"{carpark.name}只剩{vacancies}個位，隨時會滿。"
        return f"前方首選{carpark.name}，{distance}米，剩{vacancies}個位。"

    def _scarcity_prompt(
        self,
        candidates: list[tuple[RegionSnapshot, CarparkSnapshot]],
        cards: list[dict[str, Any]],
        expanded: bool,
    ) -> dict[str, Any]:
        if expanded or len(candidates) >= self._settings.scarcity_pool_min_count:
            return {}
        if len(cards) == 1 and cards[0]["vacancies"] == 0:
            return {
                "spoken_cue": "該區僅有一場，目前已滿。要唔要幫你搵遠少少、大概三公里外嘅場？",
                "await_voice_reply": True,
            }
        return {}


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
