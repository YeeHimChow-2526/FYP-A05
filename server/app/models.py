from enum import StrEnum
from typing import Any, Literal

from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


class Preference(StrEnum):
    SMART_HYBRID = "SMART_HYBRID"
    NEAREST = "NEAREST"
    MOST_VACANCIES = "MOST_VACANCIES"


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


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


class ReleaseRequest(StrictModel):
    session_id: str = Field(min_length=1, max_length=128, pattern=r"^[^/]+$")
    target_carpark_id: str = Field(min_length=1, max_length=128, pattern=r"^[^/]+$")
    reason: str


class SyncOutboxRequest(StrictModel):
    session_id: str = Field(min_length=1, max_length=128, pattern=r"^[^/]+$")
    event_type: str
    timestamp: int


class CrowdReportRequest(StrictModel):
    session_id: str = Field(min_length=1, max_length=128, pattern=r"^[^/]+$")
    carpark_id: str = Field(min_length=1, max_length=128, pattern=r"^[^/]+$")
    report_type: str = Field(min_length=1, max_length=64)
    client_timestamp: int


class TripReportRequest(StrictModel):
    session_id: str = Field(min_length=1, max_length=128, pattern=r"^[^/]+$")
    trip_timestamp: int
    feedback: dict[str, Any]


class Poi(BaseModel):
    poi_id: str
    name: str
    aliases: list[str] = Field(default_factory=list)
    lat: float = Field(ge=-90, le=90)
    lng: float = Field(ge=-180, le=180)


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

    @model_validator(mode="after")
    def validate_vacancies(self) -> "CarparkSnapshot":
        if self.vacancies < -1:
            raise ValueError("vacancies must be -1 or a non-negative integer")
        return self


class RegionSnapshot(BaseModel):
    region_id: str
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


class SnapshotManifest(BaseModel):
    regions: dict[str, dict[str, str]]
