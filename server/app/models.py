from enum import StrEnum
from typing import Any, Literal

from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


class Preference(StrEnum):
    INDOOR_ONLY = "INDOOR_ONLY"
    SMART_HYBRID = "SMART_HYBRID"
    METERS_FOCUS = "METERS_FOCUS"


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class ReleaseReason(StrEnum):
    PARKED_CONFIRMED = "PARKED_CONFIRMED"
    USER_ABORTED = "USER_ABORTED"
    DESTINATION_CHANGED = "DESTINATION_CHANGED"


# Contract v2 typed fields. ``None`` means a legacy (unversioned) client; the server decides per
# session whether that is still allowed (see app/lock_policy.py) instead of trusting Optional.
Epoch = Field(default=None, ge=0, le=2_147_483_647)


class RecommendRequest(StrictModel):
    session_id: str = Field(min_length=1, max_length=128, pattern=r"^[^/]+$")
    origin_lat: float = Field(ge=-90, le=90)
    origin_lng: float = Field(ge=-180, le=180)
    dest_lat: float | None = Field(default=None, ge=-90, le=90)
    dest_lng: float | None = Field(default=None, ge=-180, le=180)
    dest_name: str | None = Field(default=None, min_length=1, max_length=200)
    preference: Preference = Preference.SMART_HYBRID
    expand_scarcity: bool = False
    speech_locale: str = Field(default="zh-HK", max_length=35)
    destination_epoch: int | None = Epoch
    # Unix seconds when the client started this session id; required to CREATE a session.
    session_started_at: int | None = Field(default=None, ge=1_500_000_000, le=4_102_444_800)

    def started_at(self):
        from datetime import UTC, datetime

        return None if self.session_started_at is None else datetime.fromtimestamp(self.session_started_at, UTC)

    @model_validator(mode="after")
    def validate_destination(self) -> "RecommendRequest":
        if (self.dest_lat is None) != (self.dest_lng is None):
            raise ValueError("dest_lat and dest_lng must be provided together")
        if self.dest_lat is None and not self.dest_name:
            raise ValueError("Provide destination coordinates or dest_name")
        return self


class CommitRequest(StrictModel):
    session_id: str = Field(min_length=1, max_length=128, pattern=r"^[^/]+$")
    target_carpark_id: str = Field(min_length=1, max_length=128, pattern=r"^[^/]+$")
    destination_epoch: int | None = Epoch
    idempotency_key: str | None = Field(
        default=None, min_length=8, max_length=128, pattern=r"^[A-Za-z0-9_.:-]+$"
    )

    @model_validator(mode="after")
    def versioned_fields_travel_together(self) -> "CommitRequest":
        if self.idempotency_key is not None and self.destination_epoch is None:
            raise ValueError("idempotency_key requires destination_epoch")
        return self


class ReleaseRequest(StrictModel):
    session_id: str = Field(min_length=1, max_length=128, pattern=r"^[^/]+$")
    target_carpark_id: str = Field(min_length=1, max_length=128, pattern=r"^[^/]+$")
    reason: ReleaseReason
    lock_revision: int | None = Field(default=None, ge=1, le=2_147_483_647)
    new_destination_epoch: int | None = Field(default=None, ge=1, le=2_147_483_647)

    @model_validator(mode="after")
    def destination_change_is_versioned(self) -> "ReleaseRequest":
        if self.reason == ReleaseReason.DESTINATION_CHANGED and self.new_destination_epoch is None:
            raise ValueError("DESTINATION_CHANGED requires new_destination_epoch")
        if self.reason != ReleaseReason.DESTINATION_CHANGED and self.new_destination_epoch is not None:
            raise ValueError("new_destination_epoch is only valid for DESTINATION_CHANGED")
        return self


class SyncOutboxRequest(StrictModel):
    session_id: str = Field(min_length=1, max_length=128, pattern=r"^[^/]+$")
    event_type: str
    timestamp: int
    # v2 parked event: identity captured when the event was created and replayed unchanged.
    contract_version: int | None = Field(default=None, ge=2, le=2)
    destination_epoch: int | None = Epoch
    # Exact lock owned at event creation; omitted in a v2 event = "held no lock".
    lock_revision: int | None = Field(default=None, ge=1, le=2_147_483_647)

    @model_validator(mode="after")
    def v2_identity_is_complete(self) -> "SyncOutboxRequest":
        if self.contract_version is None and (self.lock_revision is not None or self.destination_epoch is not None):
            raise ValueError("lock_revision/destination_epoch require contract_version 2")
        if self.contract_version is not None and self.destination_epoch is None:
            raise ValueError("contract_version 2 requires destination_epoch")
        return self


class CrowdReportRequest(StrictModel):
    session_id: str = Field(min_length=1, max_length=128, pattern=r"^[^/]+$")
    carpark_id: str = Field(min_length=1, max_length=128, pattern=r"^[^/]+$")
    report_type: str = Field(min_length=1, max_length=64)
    client_timestamp: int


class TripReportFeedback(StrictModel):
    rating_tag: Literal["GOOD", "OK", "BAD"]
    perk_status_tag: Literal["VALID", "INVALID", "NONE"]
    perk_notes: str = Field(default="", max_length=140)


class TripReportRequest(StrictModel):
    session_id: str = Field(min_length=1, max_length=128, pattern=r"^[^/]+$")
    trip_timestamp: int = Field(ge=1_500_000_000, le=4_102_444_800)
    feedback: TripReportFeedback


class Poi(BaseModel):
    poi_id: str
    name: str
    aliases: list[str] = Field(default_factory=list)
    lat: float = Field(ge=-90, le=90)
    lng: float = Field(ge=-180, le=180)


class VacancyStatus(StrEnum):
    """What the upstream record actually says about the count (see DATABASE.md provenance).
    Absent on legacy snapshots -> derived: vacancies>=0 -> COUNT, else UNKNOWN (never healthy)."""

    COUNT = "COUNT"                # numeric count published (0 = full)
    NO_COUNT = "NO_COUNT"          # publisher sent no usable count (flag only / negative / malformed)
    SOURCE_STALE = "SOURCE_STALE"  # per-record publisher timestamp too old; count dropped
    CLOSED = "CLOSED"              # publisher says closed
    NOT_FOR_USE = "NOT_FOR_USE"    # TD meter ParkingMeterStatus=NU for every space in the cluster
    UNKNOWN = "UNKNOWN"            # no status information


class CarparkSnapshot(BaseModel):
    carpark_id: str
    name: str
    type: str
    lat: float = Field(ge=-90, le=90)
    lng: float = Field(ge=-180, le=180)
    vacancies: int = -1
    fee_summary: str = ""
    is_open: bool = True
    region_id: str = ""
    vacancy_status: VacancyStatus | None = None
    vacancy_reason: str = ""
    # Publisher's own per-record timestamp (TD lastupdate); ISO-8601 with zone.
    source_updated_at: str | None = None

    def effective_status(self) -> VacancyStatus:
        if self.vacancy_status is not None:
            return self.vacancy_status
        return VacancyStatus.COUNT if self.vacancies >= 0 else VacancyStatus.UNKNOWN

    @model_validator(mode="after")
    def validate_vacancies(self) -> "CarparkSnapshot":
        if self.vacancies < -1:
            raise ValueError("vacancies must be -1 or a non-negative integer")
        if self.vacancy_status is not None and (
            (self.vacancy_status == VacancyStatus.COUNT) != (self.vacancies >= 0)
        ):
            raise ValueError("vacancy_status COUNT requires vacancies>=0; other statuses require -1")
        if self.source_updated_at is not None and datetime.fromisoformat(self.source_updated_at).tzinfo is None:
            raise ValueError("source_updated_at must include a timezone")
        return self


class RegionSnapshot(BaseModel):
    region_id: str
    source: str = ""
    source_snapshot_ts: str
    processed_at: str
    source_status: Literal["ok", "degraded"]
    carparks: list[CarparkSnapshot]

    @field_validator("source_snapshot_ts", "processed_at")
    @classmethod
    def require_timezone(cls, value: str) -> str:
        timestamp = datetime.fromisoformat(value)
        if timestamp.tzinfo is None:
            raise ValueError("snapshot timestamps must include a timezone")
        return value


class SourceState(StrictModel):
    """Per-source refresh state inside manifest.json (shared across Cloud Run instances).
    checked_at = last SUCCESSFUL upstream check (200 or 304); never a failed attempt.
    source_snapshot_ts = publisher time of the published data (never the check/fetch time)."""

    checked_at: str | None = None
    etag: str | None = None
    sha256: str | None = None
    source_snapshot_ts: str | None = None
    consecutive_failures: int = Field(default=0, ge=0)
    next_attempt_after: str | None = None  # bounded exponential backoff after failures
    last_error: str | None = Field(default=None, max_length=500)
    # Static metadata (TD basic_info / meter space list), cached as an immutable GCS object.
    static_etag: str | None = None
    static_object: str | None = None
    static_checked_at: str | None = None


class SnapshotManifest(StrictModel):
    """Single GCS manifest = lease + pointers (plan §5 option B). Old manifests (regions plus
    source/is_demo/includes_demo_region only) stay valid: every new field is optional."""

    regions: dict[str, dict[str, str]]
    source: str | None = None
    is_demo: bool | None = None
    includes_demo_region: bool | None = None
    owner_token: str | None = Field(default=None, max_length=128)
    lease_until: str | None = None
    checked_at: str | None = None  # = sources["td_carpark"].checked_at (plan wording)
    sources: dict[str, SourceState] = Field(default_factory=dict)
