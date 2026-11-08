"""Golden numbers for ARCHITECTURE.md §9 scoring, filters, preferences and spoken cues.
Unit-level only (fake repository/snapshots); not an end-to-end or live-Firestore test."""

import asyncio
import unittest
from datetime import UTC, datetime, timedelta
from zoneinfo import ZoneInfo

from app import scoring
from app.config import Settings
from app.models import CarparkSnapshot, RecommendRequest, RegionSnapshot
from app.recommend import RecommendationService, _distance_meters

S = Settings(internal_api_key="k", gcs_state_bucket="b")
HKT = ZoneInfo("Asia/Hong_Kong")
DEST = (22.3, 114.17)


def rec(now, **extra):
    return {"source": "TEST_FIXTURE", "provenance": "unit-test fixture, not live data",
            "observed_at": now - timedelta(days=1), "expires_at": now + timedelta(days=1), **extra}


def sc(**kw):
    base = dict(vacancies=10, locks=4, distance_m=300, age_seconds=0, settings=S)
    base.update(kw)
    return scoring.score(**base)


class ScoreGoldenTests(unittest.TestCase):
    def test_base_terms_and_units(self):
        b = sc()
        self.assertAlmostEqual(b.distance, 40.0)  # 50*(1-300/1500)
        self.assertAlmostEqual(b.vacancy, 16.0)  # 40*min(6/15,1)
        self.assertAlmostEqual(b.total, 56.0)

    def test_vacancy_cap_distance_floor_negative_and_unknown(self):
        self.assertAlmostEqual(sc(vacancies=40, locks=0).vacancy, 40.0)
        self.assertAlmostEqual(sc(distance_m=2000).distance, 0.0)
        self.assertAlmostEqual(sc(vacancies=2, locks=5).vacancy, -8.0)  # 40*(-3/15)
        self.assertAlmostEqual(sc(vacancies=-1, locks=3).vacancy, 0.0)

    def test_perk_pain_crowd_jitter_scarcity(self):
        now = datetime.now(HKT)
        perk = lambda c: {"perk_record": rec(now, confidence=c)}  # noqa: E731
        pain = lambda lvl: {"pain_record": rec(now, level=lvl)}  # noqa: E731
        self.assertEqual(sc(perk=perk(0.9), now=now).total, 71.0)
        self.assertEqual(sc(perk=perk(0.79), now=now).perk, 0)
        self.assertEqual(sc(pain=pain("GENERAL"), now=now).total, 36.0)
        self.assertEqual(sc(pain=pain("CHRONIC"), now=now).total, 16.0)
        scarce = sc(pain=pain("CHRONIC"), scarcity=True, now=now)
        self.assertEqual((scarce.pain, scarce.scarcity, scarce.total), (0, 30, 86.0))
        crowd = {"recent_independent_report_sources": 1,
                 "crowd_window_expires_at": now + timedelta(minutes=30)}
        self.assertEqual(sc(pain=crowd, now=now).total, 11.0)
        expired = dict(crowd, crowd_window_expires_at=now - timedelta(seconds=1))
        self.assertEqual(sc(pain=expired, now=now).crowd, 0)
        self.assertEqual(sc(jitter=3).total, 59.0)
        self.assertEqual(sc(jitter=9).jitter, 5.0)
        self.assertEqual(sc(jitter=-2).jitter, 0.0)

    def test_stale_tier_boundaries(self):
        self.assertEqual([scoring.stale_penalty(a, S) for a in (0, 899, 900, 1500, 1501)],
                         [0, 0, 5, 5, 20])
        self.assertEqual(sc(age_seconds=1501).total, 36.0)
        self.assertFalse(scoring.is_stale(600, S))
        self.assertTrue(scoring.is_stale(601, S))

    def test_exclusion_rules(self):
        # t1229: -1 is never assumed to be maintenance/fault; only typed CLOSED / NOT_FOR_USE exclude.
        self.assertTrue(scoring.is_fresh_invalid(0, False, False))
        self.assertFalse(scoring.is_fresh_invalid(-1, False, False))
        P = lambda **kw: CarparkSnapshot(carpark_id="x", name="x", type=kw.pop("type", "INDOOR"),  # noqa: E731
                                         lat=22.3, lng=114.1, **kw)
        d = lambda c, stale=False, scarcity=False: scoring.pool_decision(c, stale, False, S, scarcity)  # noqa: E731
        self.assertEqual(d(P(vacancies=-1)), scoring.VALID)  # legacy unknown -> UNCONFIRMED card
        self.assertEqual(d(P(vacancies=-1, vacancy_status="NO_COUNT")), scoring.VALID)
        self.assertEqual(d(P(vacancies=-1, vacancy_status="CLOSED")), scoring.EXCLUDE)
        self.assertEqual(d(P(vacancies=-1, vacancy_status="NOT_FOR_USE", type="METER")), scoring.EXCLUDE)
        self.assertEqual(d(P(vacancies=0)), scoring.REFERENCE_ONLY)
        self.assertEqual(d(P(vacancies=0), stale=True), scoring.VALID)
        self.assertEqual(d(P(vacancies=1, type="METER")), scoring.EXCLUDE)  # < hotspot min 2
        self.assertEqual(d(P(vacancies=1, type="METER"), scarcity=True), scoring.VALID)
        self.assertEqual(d(P(vacancies=2, type="METER")), scoring.VALID)
        self.assertFalse(scoring.is_fresh_invalid(0, True, False))
        self.assertFalse(scoring.is_fresh_invalid(0, False, True))
        self.assertFalse(scoring.is_fresh_invalid(-1, True, False))
        self.assertFalse(scoring.is_fresh_invalid(3, False, False))
        self.assertFalse(scoring.is_fresh_invalid(-1, False, False, meter=True))  # meter unknown
        self.assertTrue(scoring.is_fresh_invalid(0, False, False, meter=True))


class PreferenceContractTests(unittest.TestCase):
    def test_enum_matches_app_and_rejects_legacy(self):
        from pydantic import ValidationError
        from app.models import Preference
        self.assertEqual([p.value for p in Preference], ["INDOOR_ONLY", "SMART_HYBRID", "METERS_FOCUS"])
        for legacy in ("NEAREST", "MOST_VACANCIES"):
            with self.assertRaises(ValidationError):
                RecommendRequest(session_id="s", origin_lat=22.3, origin_lng=114.1,
                                 dest_lat=22.3, dest_lng=114.1, preference=legacy)


class FakeRepo:
    def __init__(self, locks=None, pain=None, perk=None):
        self.locks, self.pain, self.perk = locks or {}, pain or {}, perk or {}
        self.lock_calls = []

    async def touch_session(self, *a, **k):
        return None

    async def count_locks(self, carpark_id, *, exclude_session_id=None):
        self.lock_calls.append((carpark_id, exclude_session_id))
        return self.locks.get(carpark_id, 0)

    async def get_pain_point(self, cid):
        return self.pain.get(cid, {})

    async def get_perk(self, cid):
        return self.perk.get(cid, {})


class FakeSnaps:
    def __init__(self, regions):
        self.regions = regions

    async def load_regions(self):
        return self.regions


def cp(cid, meters, vac, type_="INDOOR", fee=""):
    return CarparkSnapshot(carpark_id=cid, name=cid, type=type_, lat=DEST[0] + meters / 111195,
                           lng=DEST[1], vacancies=vac, fee_summary=fee)


def region(carparks, age_min=0, status="ok"):
    ts = (datetime.now(UTC) - timedelta(minutes=age_min)).isoformat()
    return RegionSnapshot(region_id=f"R{age_min}{status}", source_snapshot_ts=ts,
                          processed_at=ts, source_status=status, carparks=carparks)


def run(regions, repo=None, **req):
    repo = repo or FakeRepo()
    svc = RecommendationService(S, None, FakeSnaps(regions), repo, jitter=lambda: 0.0)
    body = dict(session_id="me", origin_lat=22.29, origin_lng=114.17,
                dest_lat=DEST[0], dest_lng=DEST[1])
    body.update(req)
    return asyncio.run(svc.recommend(RecommendRequest(**body))), repo


def ids(resp):
    return [c["carpark_id"] for c in resp["cards"]]


class ServiceTests(unittest.TestCase):
    def test_preferences(self):
        regs = [region([cp("I1", 100, 10), cp("I2", 200, 10), cp("I3", 300, 10),
                        cp("M1", 50, 10, "METER")])]
        self.assertNotIn("M1", ids(run(regs, preference="INDOOR_ONLY")[0]))
        self.assertEqual(ids(run(regs, preference="METERS_FOCUS")[0])[0], "M1")
        # SMART_HYBRID: pure score order, no 2-indoor/1-meter quota (M1 is nearest -> first).
        self.assertEqual(ids(run(regs, preference="SMART_HYBRID")[0]), ["M1", "I1", "I2", "I3"])

    def test_filters_and_scarcity_never_readmit_invalid(self):
        regs = [region([cp("FRESH0", 50, 0), cp("OK", 400, 5)]),
                region([cp("STALE0", 500, 0)], age_min=20)]
        resp, _ = run(regs)
        cards = {c["carpark_id"]: c for c in resp["cards"]}
        self.assertEqual(ids(resp)[0], "OK")  # valid pool of 2 -> scarcity; only OK viable
        self.assertEqual(cards["STALE0"]["availability"], "LAST_KNOWN_ZERO")
        self.assertEqual(cards["FRESH0"]["availability"], "NO_AVAILABILITY")  # reference only
        self.assertFalse(cards["FRESH0"]["commit_allowed"] or cards["STALE0"]["commit_allowed"])

    def test_effective_full_not_first_and_own_lock_excluded(self):
        regs = [region([cp("NEAR", 50, 2), cp("FAR", 900, 10), cp("MID", 600, 8)])]
        resp, repo = run(regs, FakeRepo(locks={"NEAR": 2}))
        self.assertEqual(ids(resp)[-1], "NEAR")
        self.assertEqual(resp["cards"][-1]["vacancies"], 0)
        self.assertTrue(all(sess == "me" for _, sess in repo.lock_calls))

    def test_bounded_lock_queries(self):
        regs = [region([cp(f"C{i}", 100 + i * 50, 10) for i in range(9)])]
        _, repo = run(regs)
        self.assertEqual(len(repo.lock_calls), S.lock_count_top_k_candidates)

    def test_rank_cap_after_first_unreported(self):
        now = datetime.now(HKT)
        pain = {"A": {"recent_independent_report_sources": 2,
                      "crowd_window_expires_at": now + timedelta(minutes=30)}}
        regs = [region([cp("A", 10, 15), cp("B", 1400, 1), cp("C", 1450, 1)])]
        resp, _ = run(regs, FakeRepo(pain=pain), preference="INDOOR_ONLY")
        self.assertEqual(ids(resp)[:2], ["B", "A"])
        self.assertTrue(resp["cards"][1]["spoken_cue"].endswith(scoring.CROWD_CUE))

    def test_cues_rank_fee_lowwater_backup(self):
        regs = [region([cp("A", 100, 3, fee="首小時$32"), cp("B", 900, 10), cp("C", 1200, 10)])]
        resp, _ = run(regs, preference="INDOOR_ONLY")
        d = _distance_meters(DEST, (DEST[0] + 100 / 111195, DEST[1]))
        self.assertEqual(resp["cards"][0]["spoken_cue"],
                         f"前方首選A，{d}米，只剩3個位，隨時會滿，首小時$32。後備可以去B。")
        self.assertTrue(resp["cards"][1]["spoken_cue"].startswith("另一個選擇B"))
        self.assertNotIn("首選", resp["cards"][1]["spoken_cue"])
        self.assertNotIn("後備", resp["cards"][1]["spoken_cue"] + resp["cards"][2]["spoken_cue"])

    def test_stale_and_meter_cues_no_numbers(self):
        regs = [region([cp("S", 100, 2), cp("M", 50, 4, "METER")], age_min=20),
                region([cp("F", 900, 10)])]
        resp, _ = run(regs)
        cards = {c["carpark_id"]: c for c in resp["cards"]}
        self.assertIn("約20分鐘前", cards["S"]["spoken_cue"])
        self.assertNotIn("剩", cards["S"]["spoken_cue"])
        self.assertNotIn("後備", cards["S"]["spoken_cue"])
        self.assertEqual(cards["M"]["vacancies"], -1)
        self.assertEqual(cards["M"]["spoken_cue"], scoring.METER_UNKNOWN_CUE + "，唔保證有位，唔可以預留。")
        self.assertEqual(cards["M"]["availability"], "UNCONFIRMED")


class ApprovedDecisionTests(unittest.TestCase):
    """Kenny t1228 decisions (reference cards, fallback meter, no preference points)."""

    def test_high_score_full_never_beats_lower_viable(self):
        perk = {"NEAR": {"confidence": 0.95}}
        regs = [region([cp("NEAR", 20, 3), cp("FAR", 1400, 1), cp("MID", 1300, 1)])]
        resp, _ = run(regs, FakeRepo(locks={"NEAR": 3}, perk=perk), preference="INDOOR_ONLY")
        self.assertEqual(ids(resp), ["MID", "FAR", "NEAR"])
        near = resp["cards"][2]
        self.assertEqual((near["availability"], near["commit_allowed"], near["vacancies"]),
                         ("NO_AVAILABILITY", False, 0))
        self.assertNotIn("首選", near["spoken_cue"])
        self.assertIn("只供參考", near["spoken_cue"])
        self.assertTrue(resp["cards"][0]["spoken_cue"].startswith("前方首選MID"))
        self.assertNotIn("await_voice_reply", resp)

    def test_rank_cap_cannot_promote_full(self):
        now = datetime.now(HKT)
        pain = {"A": {"recent_independent_report_sources": 2,
                      "crowd_window_expires_at": now + timedelta(minutes=30)}}
        regs = [region([cp("A", 10, 15), cp("FULL", 20, 2), cp("C", 1400, 1)])]
        resp, _ = run(regs, FakeRepo(pain=pain, locks={"FULL": 2}), preference="INDOOR_ONLY")
        self.assertEqual(ids(resp), ["C", "A", "FULL"])

    def test_all_full_or_empty_prompts_expand_without_auto_expand(self):
        regs = [region([cp("F1", 100, 0), cp("F2", 200, 0)]), region([cp("S0", 300, 0)], age_min=20)]
        resp, repo = run(regs)
        self.assertTrue(resp["await_voice_reply"])
        self.assertIn("三公里", resp["spoken_cue"])
        self.assertTrue(all(not c["commit_allowed"] for c in resp["cards"]))
        self.assertTrue(all("首選" not in c["spoken_cue"] for c in resp["cards"]))
        self.assertEqual(len(repo.lock_calls), 1)  # only S0 in valid pool; no extra radius query
        empty, _ = run([region([])])
        self.assertEqual((empty["cards"], empty["await_voice_reply"]), ([], True))
        expanded, _ = run(regs, expand_scarcity=True)
        self.assertFalse(expanded["await_voice_reply"])

    def test_unknown_meter_is_viable_unconfirmed_not_full(self):
        regs = [region([cp("M", 50, -1, "METER"), cp("I", 1400, 1)])]
        resp, _ = run(regs, preference="METERS_FOCUS")
        m = resp["cards"][0]
        self.assertEqual((m["carpark_id"], m["availability"], m["vacancies"], m["commit_allowed"]),
                         ("M", "UNCONFIRMED", -1, False))
        self.assertIn("唔保證有位", m["spoken_cue"])
        self.assertNotIn("await_voice_reply", resp)

    def test_stale_zero_label_and_commit_denied(self):
        regs = [region([cp("S0", 100, 0)], age_min=20), region([cp("OK", 900, 5)])]
        resp, _ = run(regs)
        s0 = next(c for c in resp["cards"] if c["carpark_id"] == "S0")
        self.assertEqual((s0["availability"], s0["commit_allowed"], s0["is_stale"]),
                         ("LAST_KNOWN_ZERO", False, True))
        self.assertIn("最後已知冇位，而家實際情況未知", s0["spoken_cue"])
        self.assertEqual(ids(resp)[0], "OK")
        # stale last-known with locks that reach 0 is also denied
        self.assertEqual(scoring.classify(vacancies=2, locks=2, stale=True, degraded=False, meter=False),
                         "LAST_KNOWN_ZERO")

    def test_meters_focus_full_or_invalid_meter_below_viable_indoor(self):
        regs = [region([cp("MFULL", 10, 2, "METER"), cp("MFRESH0", 20, 0, "METER"),
                        cp("MOK", 1300, 2, "METER"), cp("I1", 30, 9), cp("I2", 40, 9)])]
        resp, _ = run(regs, FakeRepo(locks={"MFULL": 2}), preference="METERS_FOCUS")
        self.assertEqual(ids(resp), ["MOK", "I1", "I2", "MFULL"])
        self.assertNotIn("MFRESH0", ids(resp))  # not scarcity -> fresh 0 excluded

    def test_hybrid_fallback_meter_within_max5(self):
        regs = [region([cp(f"I{i}", 50 + i * 10, 10) for i in range(6)]
                       + [cp("M", 1400, 10, "METER")])]
        resp, repo = run(regs, preference="SMART_HYBRID")
        self.assertEqual(len(resp["cards"]), 5)
        self.assertEqual(len(repo.lock_calls), 5)
        self.assertEqual(ids(resp), ["I0", "I1", "I2", "I3", "M"])  # I4 (5th) gave its slot
        self.assertTrue(resp["cards"][-1]["is_fallback"])
        self.assertTrue(resp["cards"][-1]["spoken_cue"].startswith("咪錶後備"))
        self.assertFalse(any(c["is_fallback"] for c in resp["cards"][:-1]))

    def test_preference_adds_no_points(self):
        regs = [region([cp("I", 300, 10), cp("M", 200, 10, "METER")])]
        for pref in ("SMART_HYBRID", "METERS_FOCUS", "INDOOR_ONLY"):
            resp, _ = run(regs, preference=pref)
            self.assertIn(ids(resp)[0], {"I", "M"})
        # Score is a function of terms only; preference is not a parameter of scoring.score.
        import inspect
        self.assertNotIn("preference", inspect.signature(scoring.score).parameters)

    def test_scarcity_reference_fill_capped_at_k(self):
        regs = [region([cp("OK", 100, 5)] + [cp(f"Z{i}", 200 + i, 0) for i in range(8)])]
        resp, repo = run(regs)
        self.assertEqual(len(resp["cards"]), 5)
        self.assertEqual(len(repo.lock_calls), 1)


if __name__ == "__main__":
    unittest.main()
