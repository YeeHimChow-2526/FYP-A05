"""Typed read interface for perk (M_i) and pain (P_i) records.

Producers are intentionally deferred (product decision t1230: perks would need a crawler + AI
review; no chronic-pain writer exists). With no valid record M_i = 0 and P_i = 0 — no penalty,
the rest of the ranking is unchanged. Records are never synthesised: a record without explicit
source, provenance, timestamps and (for perks) confidence is rejected, not defaulted.

Firestore fields (read only by the recommender):
  carpark_perks_master/{id}.perk_record
  carpark_pain_points/{id}.pain_record   (crowd fields on the same doc are separate: C_i)
"""

from __future__ import annotations

from datetime import datetime
from enum import StrEnum
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, ValidationError, field_validator


class _Record(BaseModel):
    model_config = ConfigDict(extra="forbid")
    source: str = Field(min_length=1, max_length=64)
    provenance: str = Field(min_length=1, max_length=500)  # URL / document / reviewer reference
    observed_at: datetime
    expires_at: datetime

    @field_validator("observed_at", "expires_at")
    @classmethod
    def _tz(cls, value: datetime) -> datetime:
        if value.tzinfo is None:
            raise ValueError("timestamps must include a timezone")
        return value

    def active(self, now: datetime) -> bool:
        return self.observed_at <= now < self.expires_at


class PerkRecord(_Record):
    confidence: float = Field(ge=0, le=1)
    fee_summary: str | None = Field(default=None, max_length=200)


class PainLevel(StrEnum):
    GENERAL = "GENERAL"
    CHRONIC = "CHRONIC"


class PainRecord(_Record):
    level: PainLevel


def _parse(model: type[_Record], raw: Any) -> _Record | None:
    if not isinstance(raw, dict):
        return None
    try:
        return model.model_validate(raw)
    except ValidationError:
        return None


def active_perk(doc: dict[str, Any], now: datetime) -> PerkRecord | None:
    record = _parse(PerkRecord, doc.get("perk_record"))
    return record if record is not None and record.active(now) else None  # type: ignore[return-value]


def active_pain(doc: dict[str, Any], now: datetime) -> PainRecord | None:
    record = _parse(PainRecord, doc.get("pain_record"))
    return record if record is not None and record.active(now) else None  # type: ignore[return-value]
