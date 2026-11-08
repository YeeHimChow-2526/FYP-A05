"""TD metered-parking CSVs -> meter-cluster RegionSnapshot (pure, local, NOT scheduled).

Schema verified against TD data spec "Distribution of Metered Parking Spaces and Occupancy of
those Installed with New Parking Meters" (spaceinfo/parkingspaces.csv,
occupancystatus/occupancystatus.csv) and a public read on 2026-11-08 HKT:
  ParkingMeterStatus: N = Normal, NU = Not for Use
  OccupancyStatus:    V = Vacant, O = Occupied
  OccupancyDateChanged = time of the last occupancy CHANGE (not an observation/freshness time).
  PoleId > 90000 = internal test meters. Only spaces with NEW meters have occupancy rows.

There is no Cloud Run job, bucket write or schedule for this module: it is a typed normaliser
for local import/tests. Wiring it into ingest is a separate (cost-bearing) decision.

Cluster = (District, Street, SectionOfStreet), VehicleType "A" only (CHOICE: the TD section of
street is the grouping; METER_CLUSTER_RADIUS_METERS is not applied here). The count is the number
of N+V spaces among sensor-covered spaces: a lower bound, never a guarantee.
"""

from __future__ import annotations

import csv
import io
from collections import defaultdict
from datetime import datetime
from typing import Any

from ..models import RegionSnapshot

REGION_ID = "HK_TD_METERS"
SOURCE = "TD_DATA_GOV_HK_METERS"
TEST_POLE_MIN = 90000


def _rows(text: str, header_start: str) -> list[dict[str, str]]:
    lines = text.lstrip("\ufeff").splitlines()
    for i, line in enumerate(lines):
        if line.startswith(header_start):
            return list(csv.DictReader(io.StringIO("\n".join(lines[i:]))))
    raise ValueError(f"CSV header starting with {header_start!r} not found")


def normalize_space(meter_status: str | None, occupancy: str | None) -> str:
    """Per-space state from the two verified fields; anything else stays UNKNOWN."""
    meter_status = (meter_status or "").strip().upper()
    occupancy = (occupancy or "").strip().upper()
    if meter_status == "NU":
        return "NOT_FOR_USE"
    if meter_status == "N" and occupancy == "V":
        return "VACANT"
    if meter_status == "N" and occupancy == "O":
        return "OCCUPIED"
    return "UNKNOWN"


def transform(spaces_csv: str, occupancy_csv: str, source_ts: datetime, now: datetime) -> dict[str, Any]:
    if source_ts.tzinfo is None or now.tzinfo is None:
        raise ValueError("timestamps must include a timezone")
    occ = {
        r["ParkingSpaceId"].strip(): normalize_space(r.get("ParkingMeterStatus"), r.get("OccupancyStatus"))
        for r in _rows(occupancy_csv, "ParkingSpaceId")
        if r.get("ParkingSpaceId")
    }
    clusters: dict[tuple[str, str, str], list[tuple[float, float, str, str]]] = defaultdict(list)
    for r in _rows(spaces_csv, "PoleId"):
        try:
            if int(r["PoleId"]) > TEST_POLE_MIN or r.get("VehicleType", "").strip() != "A":
                continue
            lat, lng = float(r["Latitude"]), float(r["Longitude"])
        except (KeyError, TypeError, ValueError):
            continue
        key = (r.get("District", "").strip(), r.get("Street", "").strip(), r.get("SectionOfStreet", "").strip())
        name = f"{r.get('Street_tc', '').strip()}{r.get('SectionOfStreet_tc', '').strip()}咪錶" or "咪錶"
        clusters[key].append((lat, lng, occ.get(r["ParkingSpaceId"].strip(), "NO_SENSOR"), name))

    carparks = []
    for (district, street, section), spaces in sorted(clusters.items()):
        states = [s[2] for s in spaces]
        usable = [s for s in states if s in ("VACANT", "OCCUPIED")]
        if usable:
            vacancies, status = states.count("VACANT"), "COUNT"
            reason = "SENSOR_COUNT" if len(usable) == len(states) else "PARTIAL_SENSOR_COVERAGE"
        elif states and all(s == "NOT_FOR_USE" for s in states):
            vacancies, status, reason = -1, "NOT_FOR_USE", "ALL_NU"
        elif all(s == "NO_SENSOR" for s in states):
            vacancies, status, reason = -1, "NO_COUNT", "NO_SENSOR_DATA"
        else:
            vacancies, status, reason = -1, "UNKNOWN", "UNRECOGNISED_STATUS"
        carparks.append({
            "carpark_id": f"MTR_{district}_{street}_{section}".replace(" ", "_")[:128].replace("/", "_"),
            "name": spaces[0][3],
            "type": "METER",
            "lat": sum(s[0] for s in spaces) / len(spaces),
            "lng": sum(s[1] for s in spaces) / len(spaces),
            "vacancies": vacancies,
            "fee_summary": "",
            "is_open": True,  # CHOICE: operating periods are not evaluated here (unknown != closed)
            "region_id": district.upper().replace(" ", "_"),
            "vacancy_status": status,
            "vacancy_reason": reason,
            "source_updated_at": source_ts.isoformat(),
        })
    snapshot = {
        "region_id": REGION_ID,
        "source": SOURCE,
        "source_snapshot_ts": source_ts.isoformat(),
        "processed_at": now.isoformat(),
        "source_status": "ok" if carparks else "degraded",
        "carparks": carparks,
    }
    RegionSnapshot.model_validate(snapshot)
    return snapshot
