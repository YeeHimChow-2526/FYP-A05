"""ARCHITECTURE.md §9 recommendation formula (pure functions, no I/O).

S_i = W_dist*(1 - D_i/D_max) + W_vac*min((V_i - L_i)/15, 1.0)
      + M_i - P_i - C_i + R_i + B_scarcity - S_stale

Interpretation choices where the spec is silent are marked "CHOICE" and listed in
docs (HANDOFF_SHORT / DATABASE.md); they are not product-approved policy.
"""

from __future__ import annotations

import random
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Callable

from .config import Settings
from .scoring_records import PainLevel, active_pain, active_perk

METER_TYPES = {"METER", "METERS"}
METER_UNKNOWN_CUE = "附近可能有咪錶，資料暫時未更新"
CROWD_CUE = "近期有司機回報疑似霸位，請留意現場。"

Jitter = Callable[[], float]


def default_jitter(settings: Settings) -> Jitter:
    return lambda: random.uniform(0.0, float(settings.random_jitter_max_points))


def is_meter(type_: str) -> bool:
    return type_.upper() in METER_TYPES


def is_stale(age_seconds: float, settings: Settings) -> bool:
    # §5 / §9.4: stale when strictly older than SOURCE_STALE_THRESHOLD_SECONDS.
    return age_seconds > settings.source_stale_threshold_seconds


VALID, REFERENCE_ONLY, EXCLUDE = "VALID", "REFERENCE_ONLY", "EXCLUDE"
UNKNOWN_STATUSES = {"NO_COUNT", "SOURCE_STALE", "UNKNOWN"}


def pool_decision(carpark: Any, stale: bool, degraded: bool, settings: Settings,
                  scarcity: bool = False) -> str:
    """Valid-pool filter driven by the typed vacancy_status (see models.VacancyStatus).
    EXCLUDE: closed, or NOT_FOR_USE (TD meter NU) — the only fault signal any source provides.
    REFERENCE_ONLY: fresh published 0 (outside the pool; may return as a reference card in
      scarcity). Fresh meter clusters below METER_HOTSPOT_MIN_VACANCIES (2) are excluded unless
      scarcity relaxes the threshold to >=1 (§9.7).
    Unknown statuses (no count / stale record / legacy -1) stay VALID -> UNCONFIRMED card:
    -1 is never assumed to mean maintenance or fault."""
    status = str(carpark.effective_status())
    if not carpark.is_open or status in ("CLOSED", "NOT_FOR_USE"):
        return EXCLUDE
    if status != "COUNT" or stale or degraded:
        return VALID
    if carpark.vacancies == 0:
        return REFERENCE_ONLY
    if is_meter(carpark.type):
        minimum = 1 if scarcity else settings.meter_hotspot_min_vacancies
        if carpark.vacancies < minimum:
            return EXCLUDE
    return VALID


def is_fresh_invalid(vacancies: int, stale: bool, degraded: bool, meter: bool = False) -> bool:
    """Legacy helper kept for callers/tests: fresh published 0 only (no -1 = fault assumption)."""
    return not stale and not degraded and vacancies == 0


def record_age_seconds(region_age: float, source_updated_at: str | None, now: datetime) -> float:
    """Age = the older of the region snapshot and the publisher's own per-record timestamp."""
    if not source_updated_at:
        return region_age
    item = (now - datetime.fromisoformat(source_updated_at)).total_seconds()
    return max(region_age, max(item, 0.0))


def stale_penalty(age_seconds: float, settings: Settings) -> int:
    """§9.2 S_stale: '15–25 分鐘扣 5；＞25 分鐘扣 20'.
    CHOICE: tier-1 range is inclusive on both ends (900 s and 1500 s -> 5);
    tier 2 strictly greater than 25 min (1501 s -> 20); < 900 s -> 0."""
    tier1 = settings.stale_tier1_minutes * 60
    tier2 = settings.stale_tier2_minutes * 60
    if age_seconds > tier2:
        return settings.stale_tier2_penalty_points
    if age_seconds >= tier1:
        return settings.stale_tier1_penalty_points
    return 0


def crowd_active(pain: dict[str, Any], now: datetime) -> bool:
    expires = pain.get("crowd_window_expires_at")
    if expires is None or not expires > now:
        return False
    return int(pain.get("recent_independent_report_sources", 0) or 0) >= 1 or bool(
        pain.get("crowd_warning")
    )


def crowd_rank_capped(pain: dict[str, Any], now: datetime, settings: Settings) -> bool:
    return crowd_active(pain, now) and (
        int(pain.get("recent_independent_report_sources", 0) or 0)
        >= settings.crowd_report_rank_cap_min_sources
        or bool(pain.get("crowd_rank_capped"))
    )


def perk_points(perk: dict[str, Any], settings: Settings, now: datetime | None = None) -> int:
    """M_i: +PERK_BONUS_POINTS only for a valid, active, typed perk_record with confidence >=
    PERKS_VERIFIED_THRESHOLD. No record (the normal case: producer deferred) -> 0."""
    if now is None:
        return 0
    record = active_perk(perk, now)
    return settings.perk_bonus_points if record and record.confidence >= settings.perks_verified_threshold else 0


def pain_points(pain: dict[str, Any], scarcity: bool, settings: Settings,
                now: datetime | None = None) -> int:
    """P_i: active typed pain_record CHRONIC 40 / GENERAL 20; none -> 0; scarcity -> 0."""
    if scarcity or now is None:
        return 0
    record = active_pain(pain, now)
    if record is None:
        return 0
    if record.level == PainLevel.CHRONIC:
        return settings.chronic_blocked_penalty_points
    return settings.pain_general_penalty_points


@dataclass
class ScoreBreakdown:
    distance: float
    vacancy: float
    perk: int
    pain: int
    crowd: int
    jitter: float
    scarcity: int
    stale: int
    total: float = field(init=False)

    def __post_init__(self) -> None:
        self.total = (
            self.distance + self.vacancy + self.perk - self.pain - self.crowd
            + self.jitter + self.scarcity - self.stale
        )


def score(
    *,
    vacancies: int,
    locks: int,
    distance_m: float,
    age_seconds: float,
    settings: Settings,
    scarcity: bool = False,
    perk: dict[str, Any] | None = None,
    pain: dict[str, Any] | None = None,
    now: datetime | None = None,
    jitter: float = 0.0,
) -> ScoreBreakdown:
    """Unknown vacancies (-1) contribute 0 vacancy points (CHOICE, conservative).
    (V-L)/15 is not clamped below 0: §9.3 says V-L<=0 is scored by formula, not excluded."""
    distance = settings.w_dist * (1 - min(distance_m / settings.d_max_meters, 1.0))
    if vacancies < 0:
        vacancy = 0.0
    else:
        vacancy = settings.w_vac * min(
            (vacancies - locks) / settings.vacancy_score_denominator, 1.0
        )
    pain = pain or {}
    crowd = settings.crowd_report_single_penalty if now and crowd_active(pain, now) else 0
    jitter = max(0.0, min(float(jitter), float(settings.random_jitter_max_points)))
    return ScoreBreakdown(
        distance=distance,
        vacancy=vacancy,
        perk=perk_points(perk or {}, settings, now),
        pain=pain_points(pain, scarcity, settings, now),
        crowd=crowd,
        jitter=jitter,
        scarcity=settings.scarcity_bonus_points if scarcity else 0,
        stale=stale_penalty(age_seconds, settings),
    )


AVAILABLE = "AVAILABLE"            # fresh, V-L > 0
STALE_ESTIMATE = "STALE_ESTIMATE"  # stale/degraded last-known V-L > 0
UNCONFIRMED = "UNCONFIRMED"        # vacancy unknown (meter stale/degraded/-1, stale indoor -1): not full, no guarantee
LAST_KNOWN_ZERO = "LAST_KNOWN_ZERO"  # stale/degraded last-known V-L <= 0: current unknown, not definitely full
NO_AVAILABILITY = "NO_AVAILABILITY"  # fresh V-L <= 0 after valid locks
VIABLE = {AVAILABLE, STALE_ESTIMATE, UNCONFIRMED}
COMMITTABLE = {AVAILABLE, STALE_ESTIMATE}  # mirrors commit: last-known minus valid locks > 0; never-known rejected


def classify(*, vacancies: int, locks: int, stale: bool, degraded: bool, meter: bool,
             status: str = "COUNT") -> str:
    unknown_age = stale or degraded
    if status != "COUNT" or vacancies < 0 or (meter and unknown_age):
        return UNCONFIRMED
    if vacancies - locks <= 0:
        return LAST_KNOWN_ZERO if unknown_age else NO_AVAILABILITY
    return STALE_ESTIMATE if unknown_age else AVAILABLE


def arrange(cards: list[dict[str, Any]], preference: str) -> list[dict[str, Any]]:
    """Final order (cards arrive sorted by total score; no preference adds or subtracts points):
    1. viable primaries — SMART_HYBRID/INDOOR_ONLY: pure score order (no indoor/meter quota);
       METERS_FOCUS: viable meters by score, then viable indoor by score.
       Crowd rank cap is applied inside this group only.
    2. the fallback meter (if any) — see recommend.py: it took the lowest preliminary Top-K slot
       because no meter was among the primaries; it never sits above a primary.
    3. reference cards (LAST_KNOWN_ZERO / NO_AVAILABILITY), score order. Never the lead, never
       committable, so a crowd cap, perk or short distance cannot promote them over a viable card."""
    viable = [c for c in cards if c["availability"] in VIABLE and not c["is_fallback"]]
    fallback = [c for c in cards if c["availability"] in VIABLE and c["is_fallback"]]
    reference = [c for c in cards if c["availability"] not in VIABLE]
    if preference == "METERS_FOCUS":
        viable = [c for c in viable if is_meter(c["type"])] + [
            c for c in viable if not is_meter(c["type"])
        ]
    return apply_rank_cap(viable) + fallback + reference


def apply_rank_cap(cards: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """§9.6: a rank-capped card may not rank before the first unreported card."""
    first = next((i for i, c in enumerate(cards) if not c["rank_capped"]), None)
    if first is None or first == 0:
        return cards
    capped_before = [c for c in cards[:first] if c["rank_capped"]]
    return [cards[first]] + capped_before + cards[first + 1:]


def compose_cues(cards: list[dict[str, Any]], settings: Settings) -> None:
    lead_index = next((i for i, c in enumerate(cards) if c["availability"] in VIABLE), None)
    for rank, card in enumerate(cards):
        lead = rank == lead_index
        state = card["availability"]
        head = f"{card['name']}，{card['distance_meters']}米"
        backup_name = None
        if state not in VIABLE:
            text = (
                "暫時冇位" if state == NO_AVAILABILITY
                else "最後已知冇位，而家實際情況未知"
            )
            cue = f"{head}，{text}，只供參考，唔可以預留。"
        elif card["_meter_unknown"]:
            cue = METER_UNKNOWN_CUE + "，唔保證有位，唔可以預留。"
        else:
            if lead and card["commit_allowed"]:
                prefix = "前方首選"
            elif lead:
                prefix = "附近可以考慮"  # no 首選: cannot be confirmed/reserved
            else:
                prefix = "咪錶後備" if card["is_fallback"] else "另一個選擇"
            parts = [prefix + head]
            if state == UNCONFIRMED:
                parts.append("空位未確認，唔保證有位，唔可以預留")
            elif card["_stale"] or card["_degraded"]:
                minutes = int(card["_age_seconds"] // 60)
                parts.append(
                    f"空位資料約{minutes}分鐘前，未必準確" if card["_stale"]
                    else "空位資料暫時未更新"
                )
            else:
                n = card["vacancies"]
                if lead and n <= settings.low_watermark_vacancies:
                    parts.append(f"只剩{n}個位，隨時會滿")
                    backup_name = next(
                        (c["name"] for c in cards[rank + 1:] if c["availability"] in VIABLE), None
                    )
                elif is_meter(card["type"]):
                    # TD sensors cover new meters only: a lower bound, never a guarantee.
                    parts.append(f"感應器顯示有{n}個空位，唔保證")
                else:
                    parts.append(f"剩{n}個位")
            if card.get("fee_summary"):
                parts.append(str(card["fee_summary"]))
            cue = "，".join(parts) + "。"
            if backup_name:
                cue += f"後備可以去{backup_name}。"
        if card["crowd_warning"]:
            cue += CROWD_CUE
        card["spoken_cue"] = cue
