"""Source -> typed snapshot -> scoring -> card contract, using LABELLED FIXTURES only.
Fixtures (tests/fixtures/td_*_sample.*) are hand-made in the verified TD schemas; they are not
live evidence. Writes the App contract fixture tests/fixtures/contract_recommend_*.json, which
the Flutter test test/core/net/source_contract_test.dart parses (copied into the App repo)."""

import asyncio
import copy
import json
import unittest
from datetime import UTC, timedelta
from pathlib import Path

from app import scoring
from app.config import Settings
from app.ingest import td_carpark as td
from app.ingest import td_meters
from app.models import RecommendRequest, RegionSnapshot
from app.recommend import RecommendationService
from app.scoring_records import active_pain, active_perk
from test_ingest_td import NOW, SOURCE_TS, _load
from test_scoring import FakeRepo, FakeSnaps

FIX = Path(__file__).parent / "fixtures"
S = Settings(internal_api_key="k", gcs_state_bucket="b")
DEST = dict(dest_lat=22.3125, dest_lng=114.2250)


def meters_snapshot():
    return td_meters.transform(
        (FIX / "td_meter_spaces_sample.csv").read_text(encoding="utf-8"),
        (FIX / "td_meter_occupancy_sample.csv").read_text(encoding="utf-8"),
        source_ts=NOW - timedelta(minutes=2), now=NOW,
    )


def td_snapshot(vacancy=None):
    snap, _ = td.transform(vacancy or _load("td_vacancy_sample.json"), _load("td_info_sample.json"), NOW, SOURCE_TS)
    assert snap["source_status"] == "degraded"  # < MIN_OK_CARPARKS rows in the small fixture
    snap["source_status"] = "ok"  # simulate a full-size healthy feed for the scoring path
    return snap


def recommend(regions, repo=None, **req):
    svc = RecommendationService(S, None, FakeSnaps([RegionSnapshot.model_validate(r) for r in regions]),
                                repo or FakeRepo(), jitter=lambda: 0.0, clock=lambda: NOW)
    body = dict(session_id="me", origin_lat=22.30, origin_lng=114.22, **DEST)
    body.update(req)
    return asyncio.run(svc.recommend(RecommendRequest(**body)))


class IngestStatusTests(unittest.TestCase):
    def test_td_typed_status_and_publisher_timestamp(self):
        by = {c["carpark_id"]: c for c in td_snapshot()["carparks"]}
        self.assertEqual((by["tdc_a_fresh"]["vacancy_status"], by["tdc_a_fresh"]["vacancy_reason"]), ("COUNT", "A_COUNT"))
        self.assertEqual(by["tdc_a_fresh"]["source_updated_at"], "2026-11-05T22:25:00+08:00")
        self.assertEqual((by["tdc_b_full"]["vacancy_status"], by["tdc_b_full"]["vacancies"]), ("COUNT", 0))
        self.assertEqual(by["tdc_b_flag"]["vacancy_status"], "NO_COUNT")  # not "maintenance"
        self.assertEqual(by["tdc_c_closed"]["vacancy_status"], "CLOSED")
        self.assertEqual(by["tdc_a_stale"]["vacancy_status"], "SOURCE_STALE")

    def test_td_malformed_lastupdate_and_negative_count(self):
        raw = _load("td_vacancy_sample.json")
        row = raw["car_park"][0]["vehicle_type"][0]["service_category"][0]
        row["vacancy"] = -1
        by = {c["carpark_id"]: c for c in td_snapshot(raw)["carparks"]}
        self.assertEqual(by["tdc_a_fresh"]["vacancy_status"], "NO_COUNT")
        del row["lastupdate"]
        by = {c["carpark_id"]: c for c in td_snapshot(raw)["carparks"]}
        self.assertEqual((by["tdc_a_fresh"]["vacancy_status"], by["tdc_a_fresh"]["vacancy_reason"]),
                         ("UNKNOWN", "LASTUPDATE_MISSING"))

    def test_meter_status_normalisation(self):
        n = td_meters.normalize_space
        self.assertEqual([n("N", "V"), n("N", "O"), n("NU", "V"), n("N", "X"), n(None, None)],
                         ["VACANT", "OCCUPIED", "NOT_FOR_USE", "UNKNOWN", "UNKNOWN"])
        by = {c["name"]: c for c in meters_snapshot()["carparks"]}
        self.assertEqual(set(by), {"海濱道甲段咪錶", "巧明街乙段咪錶", "偉業街丙段咪錶"})  # G + test pole dropped
        a = by["海濱道甲段咪錶"]
        self.assertEqual((a["vacancies"], a["vacancy_status"], a["vacancy_reason"]), (2, "COUNT", "PARTIAL_SENSOR_COVERAGE"))
        self.assertEqual(by["巧明街乙段咪錶"]["vacancy_status"], "NOT_FOR_USE")
        self.assertEqual(by["偉業街丙段咪錶"]["vacancy_status"], "NO_COUNT")  # no sensor row: unknown, not healthy

    def test_malformed_meter_csv_rejected(self):
        with self.assertRaises(ValueError):
            td_meters.transform("nonsense", "nonsense", NOW, NOW)


class RecordTests(unittest.TestCase):
    def test_perk_pain_records_require_provenance_and_expire(self):
        now = NOW
        good = {"source": "FIXTURE", "provenance": "fixture", "observed_at": (now - timedelta(days=1)).isoformat(),
                "expires_at": (now + timedelta(days=1)).isoformat()}
        self.assertIsNotNone(active_perk({"perk_record": dict(good, confidence=0.9)}, now))
        self.assertIsNone(active_perk({"perk_record": good}, now))  # no confidence -> no default
        self.assertIsNone(active_perk({"confidence": 1.0}, now))  # legacy untyped field ignored
        self.assertIsNone(active_perk({"perk_record": dict(good, confidence=0.9, provenance="")}, now))
        self.assertIsNone(active_perk({"perk_record": dict(good, confidence=0.9, expires_at=now.isoformat())}, now))
        self.assertIsNone(active_pain({"pain_record": dict(good, level="CHRONIC", observed_at="2026-11-01T00:00:00")}, now))
        self.assertIsNone(active_pain({"pain_record": dict(good, level="SEVERE")}, now))
        self.assertIsNotNone(active_pain({"pain_record": dict(good, level="GENERAL")}, now))
        self.assertEqual(scoring.perk_points({}, S, now), 0)  # no producer -> M=0, no penalty


class PipelineTests(unittest.TestCase):
    def test_mixed_known_and_unknown(self):
        resp = recommend([td_snapshot(), meters_snapshot()])
        cards = {c["carpark_id"]: c for c in resp["cards"]}
        self.assertNotIn("tdc_c_closed", cards)
        self.assertFalse(any(c["vacancy_status"] == "NOT_FOR_USE" for c in resp["cards"]))
        lead = resp["cards"][0]
        self.assertEqual((lead["carpark_id"], lead["availability"], lead["commit_allowed"]),
                         ("tdc_a_fresh", "AVAILABLE", True))
        self.assertTrue(lead["spoken_cue"].startswith("前方首選"))
        flag = cards["tdc_b_flag"]
        self.assertEqual((flag["availability"], flag["commit_allowed"], flag["vacancies"]), ("UNCONFIRMED", False, -1))
        self.assertIn("唔可以預留", flag["spoken_cue"])
        self.assertEqual(lead["data_source"], "TD_DATA_GOV_HK")
        self.assertNotIn("spoken_cue", resp)
        (FIX / "contract_recommend_mixed.json").write_text(json.dumps(resp, ensure_ascii=False, indent=1), encoding="utf-8")

    def test_all_unknown_has_no_reservable_lead(self):
        raw = _load("td_vacancy_sample.json")
        row = raw["car_park"][0]["vehicle_type"][0]["service_category"][0]
        row["vacancy_type"], row["vacancy"] = "B", 1
        resp = recommend([td_snapshot(raw)], preference="INDOOR_ONLY")
        self.assertTrue(all(not c["commit_allowed"] for c in resp["cards"]))
        lead = resp["cards"][0]
        self.assertEqual(lead["availability"], "UNCONFIRMED")
        self.assertNotIn("首選", lead["spoken_cue"])
        self.assertIn("冇可以預留", resp["spoken_cue"])
        self.assertIn("tdc_b_full", [c["carpark_id"] for c in resp["cards"]])  # scarcity reference
        (FIX / "contract_recommend_all_unknown.json").write_text(json.dumps(resp, ensure_ascii=False, indent=1), encoding="utf-8")

    def test_expired_and_typed_perk_pain_through_service(self):
        good = {"source": "FIXTURE", "provenance": "fixture", "observed_at": (NOW - timedelta(days=1)).isoformat(),
                "expires_at": (NOW + timedelta(days=1)).isoformat()}
        expired = dict(good, expires_at=(NOW - timedelta(seconds=1)).isoformat())
        base = recommend([td_snapshot()], preference="INDOOR_ONLY")
        same = recommend([td_snapshot()], FakeRepo(perk={"tdc_a_fresh": {"perk_record": dict(expired, confidence=0.99)}}),
                         preference="INDOOR_ONLY")
        self.assertEqual([c["carpark_id"] for c in base["cards"]], [c["carpark_id"] for c in same["cards"]])
        pained = recommend([td_snapshot()], FakeRepo(pain={"tdc_a_fresh": {"pain_record": dict(good, level="CHRONIC")}}),
                           preference="INDOOR_ONLY")
        self.assertEqual(pained["cards"][0]["carpark_id"], "tdc_a_fresh")  # only viable count; pain lowers score, not eligibility


if __name__ == "__main__":
    unittest.main()
