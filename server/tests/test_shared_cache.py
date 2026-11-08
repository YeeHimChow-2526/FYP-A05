"""Shared on-demand cache protocol against a FAKE generation-checked store (mock evidence only;
no real GCS). Fixtures are the labelled TD-schema samples."""

import asyncio
import json
import threading
import time
import unittest
from datetime import timedelta
from pathlib import Path

from app import shared_cache as sc
from app.config import Settings
from app.ingest import td_carpark as td
from app.snapshots import GcsSnapshotSource, SnapshotUnavailable
from test_ingest_td import NOW, SOURCE_TS, FakeStore, _raw

FIX = Path(__file__).parent / "fixtures"
CFG = sc.RefreshConfig()
VAC = _raw("td_vacancy_sample.json")
INFO = _raw("td_info_sample.json")


class Upstream:
    """Routes URLs; records calls; per-URL overrides."""

    def __init__(self, vac=VAC, vac_etag='"v1"', last_modified=SOURCE_TS, info_etag='"i1"'):
        self.vac, self.vac_etag, self.lm, self.info_etag = vac, vac_etag, last_modified, info_etag
        self.calls, self.fail, self.hook = [], set(), None
        self.meter_occ = (FIX / "td_meter_occupancy_sample.csv").read_bytes()
        self.meter_spaces = (FIX / "td_meter_spaces_sample.csv").read_bytes()

    def __call__(self, url, etag):
        self.calls.append((url, etag))
        if self.hook:
            hook, self.hook = self.hook, None
            hook()
        if url in self.fail:
            raise td.FetchError("boom")
        if url == td.VACANCY_URL:
            if etag == self.vac_etag:
                return td.FetchResult(304, etag=etag)
            return td.FetchResult(200, self.vac, self.vac_etag, self.lm)
        if url == td.INFO_URL:
            if etag == self.info_etag:
                return td.FetchResult(304, etag=etag)
            return td.FetchResult(200, INFO, self.info_etag, None)
        if url == sc.METER_OCCUPANCY_URL:
            return td.FetchResult(200, self.meter_occ, '"m1"', None)
        if url == sc.METER_SPACES_URL:
            return td.FetchResult(200, self.meter_spaces, '"s1"', None)
        raise AssertionError(url)

    def count(self, url):
        return sum(1 for u, _ in self.calls if u == url)


def at(t):
    return lambda: t


def manifest(store):
    return store.json(sc.MANIFEST_OBJECT)


class ProtocolTests(unittest.TestCase):
    def test_stalled_leader_is_fenced_after_takeover(self):
        store, up = FakeStore(), Upstream()
        sc.refresh_once(store, up, CFG, clock=at(NOW))  # bootstrap
        later = NOW + timedelta(minutes=2)
        results = {}

        def b_takes_over():  # A stalls past its lease; B runs a full refresh meanwhile
            up.vac_etag = '"v2"'
            results["B"] = sc.refresh_once(store, Upstream(vac=VAC.replace(b"42", b"41"), vac_etag='"v2"'), CFG,
                                           clock=at(later + timedelta(seconds=CFG.lease_seconds + 5)), token="B")

        up.vac_etag = '"vA"'
        up.hook = b_takes_over
        results["A"] = sc.refresh_once(store, up, CFG, clock=at(later), token="A")
        self.assertEqual(results["B"].outcome, "published")
        self.assertEqual(results["A"].outcome, "fenced")
        m = manifest(store)
        self.assertIsNone(m["owner_token"])
        self.assertIn("-B", m["regions"]["HK_TD"]["object"])  # B's pointer stands; A never rebased

    def test_expired_lease_without_takeover_still_publishes_late(self):
        store, up = FakeStore(), Upstream()
        clock = {"t": NOW}
        up.hook = lambda: clock.update(t=NOW + timedelta(seconds=CFG.lease_seconds * 3))
        r = sc.refresh_once(store, up, CFG, clock=lambda: clock["t"], token="A")
        self.assertEqual(r.outcome, "published")  # documented: expiry alone does not fence

    def test_concurrent_first_bootstrap_one_winner(self):
        store, results = FakeStore(), {}
        real_write = store.write

        def racing_write(name, data, gen):
            if name == sc.MANIFEST_OBJECT and gen == 0 and "B" not in results:
                results["B"] = None
                results["B"] = sc.refresh_once(store, Upstream(), CFG, clock=at(NOW), token="B")
            return real_write(name, data, gen)

        store.write = racing_write
        results["A"] = sc.refresh_once(store, Upstream(), CFG, clock=at(NOW), token="A")
        self.assertEqual(sorted([results["A"].outcome, results["B"].outcome]), ["lost_lease", "published"])

    def test_live_lease_makes_others_serve_published(self):
        store, up = FakeStore(), Upstream()
        sc.refresh_once(store, up, CFG, clock=at(NOW))
        t = NOW + timedelta(minutes=2)
        seen = {}
        up.vac_etag = '"v2"'
        up.hook = lambda: seen.update(other=sc.refresh_once(store, Upstream(), CFG, clock=at(t), token="X"))
        sc.refresh_once(store, up, CFG, clock=at(t), token="L")
        self.assertEqual(seen["other"].outcome, "leased")

    def test_304_single_check_within_interval_and_source_age_unchanged(self):
        store, up = FakeStore(), Upstream()
        sc.refresh_once(store, up, CFG, clock=at(NOW))
        pointer = manifest(store)["regions"]["HK_TD"]
        t1 = NOW + timedelta(seconds=61)
        self.assertEqual(sc.refresh_once(store, up, CFG, clock=at(t1)).sources, {"td_carpark": "not_modified"})
        self.assertEqual(sc.refresh_once(store, up, CFG, clock=at(t1 + timedelta(seconds=30))).outcome, "fresh")
        self.assertEqual(up.count(td.VACANCY_URL), 2)
        m = manifest(store)
        self.assertEqual(m["regions"]["HK_TD"], pointer)
        self.assertEqual(m["checked_at"], t1.isoformat())

    def test_failure_backoff_bounded_and_shared(self):
        store, up = FakeStore(), Upstream()
        sc.refresh_once(store, up, CFG, clock=at(NOW))
        up.fail.add(td.VACANCY_URL)
        up.vac_etag = '"v2"'
        t, delays = NOW + timedelta(seconds=61), []
        for _ in range(7):
            r = sc.refresh_once(store, up, CFG, clock=at(t))
            self.assertEqual(r.sources, {"td_carpark": "failed"})
            nxt = sc._ts(manifest(store)["sources"]["td_carpark"]["next_attempt_after"])
            delays.append((nxt - t).total_seconds())
            self.assertEqual(sc.refresh_once(store, up, CFG, clock=at(t + timedelta(seconds=1))).outcome, "fresh")
            t = nxt
        self.assertEqual(delays, [30, 60, 120, 240, 480, 600, 600])
        self.assertEqual(manifest(store)["checked_at"], NOW.isoformat())
        self.assertEqual(manifest(store)["regions"]["HK_TD"]["source_snapshot_ts"], SOURCE_TS.isoformat())

    def test_source_clock_backwards_not_published(self):
        store, up = FakeStore(), Upstream()
        sc.refresh_once(store, up, CFG, clock=at(NOW))
        older = Upstream(vac=VAC.replace(b"42", b"40"), vac_etag='"v2"', last_modified=SOURCE_TS - timedelta(minutes=5))
        t = NOW + timedelta(minutes=2)
        r = sc.refresh_once(store, older, CFG, clock=at(t))
        self.assertEqual(r.sources, {"td_carpark": "source_clock_backwards"})
        m = manifest(store)
        self.assertEqual(m["regions"]["HK_TD"]["source_snapshot_ts"], SOURCE_TS.isoformat())
        self.assertEqual(m["checked_at"], t.isoformat())

    def test_no_publisher_timestamp_is_a_failure_not_fetch_time(self):
        raw = json.loads(VAC.decode("utf-8-sig"))
        for row in raw["car_park"]:
            for v in row["vehicle_type"]:
                for c in v["service_category"]:
                    c.pop("lastupdate", None)
        store = FakeStore()
        r = sc.refresh_once(store, Upstream(vac=json.dumps(raw).encode(), last_modified=None), CFG, clock=at(NOW))
        self.assertEqual(r.sources, {"td_carpark": "failed"})
        self.assertEqual(manifest(store)["regions"], {})  # first bootstrap fails truthfully

    def test_static_metadata_etag_ttl_and_rollback(self):
        store, up = FakeStore(), Upstream()
        sc.refresh_once(store, up, CFG, clock=at(NOW))
        for i, minutes in enumerate((2, 4)):
            up.vac_etag, up.vac = f'"x{i}"', VAC.replace(b"42", str(30 + i).encode())
            sc.refresh_once(store, up, CFG, clock=at(NOW + timedelta(minutes=minutes)))
        self.assertEqual(up.count(td.INFO_URL), 1)  # within TTL: no re-download
        late = NOW + timedelta(seconds=CFG.static_ttl_seconds + 60)
        up.vac_etag, up.vac = '"late"', VAC.replace(b"42", b"20")
        sc.refresh_once(store, up, CFG, clock=at(late))
        self.assertEqual(up.calls[-1], (td.INFO_URL, '"i1"'))  # conditional re-check -> 304
        obj = manifest(store)["sources"]["td_carpark"]["static_object"]
        up.fail.add(td.INFO_URL)
        up.vac_etag, up.vac = '"later"', VAC.replace(b"42", b"21")
        r = sc.refresh_once(store, up, CFG, clock=at(late + timedelta(seconds=CFG.static_ttl_seconds + 60)))
        self.assertEqual(r.sources, {"td_carpark": "published"})  # old metadata kept
        self.assertEqual(manifest(store)["sources"]["td_carpark"]["static_object"], obj)

    def test_bad_manifest_never_overwritten(self):
        store = FakeStore({sc.MANIFEST_OBJECT: b"{not json"})
        self.assertEqual(sc.refresh_once(store, Upstream(), CFG, clock=at(NOW)).outcome, "bad_manifest")
        self.assertEqual(store.writes, [])
        store = FakeStore({sc.MANIFEST_OBJECT: b'{"regions":{},"surprise":1}'})
        self.assertEqual(sc.refresh_once(store, Upstream(), CFG, clock=at(NOW)).outcome, "bad_manifest")

    def test_demo_and_other_regions_preserved(self):
        seed = {"source": "DEMO", "is_demo": True, "regions": {
            "MK_DEMO": {"object": "regions/MK_DEMO/snapshot.json"}, "OTHER": {"object": "regions/OTHER/x.json"}}}
        store = FakeStore({sc.MANIFEST_OBJECT: json.dumps(seed).encode()})
        sc.refresh_once(store, Upstream(), CFG, clock=at(NOW))
        self.assertEqual(sorted(manifest(store)["regions"]), ["HK_TD", "MK_DEMO", "OTHER"])

    def test_job_uses_same_protocol_and_respects_api_lease(self):
        store, up = FakeStore(), Upstream()
        sc.refresh_once(store, up, CFG, clock=at(NOW))
        up.vac_etag = '"v2"'
        seen = {}
        up.hook = lambda: seen.update(job=td.run_once(store, Upstream(vac_etag='"v3"'), now=NOW + timedelta(minutes=2)))
        sc.refresh_once(store, up, CFG, clock=at(NOW + timedelta(minutes=2)), token="API")
        self.assertEqual(seen["job"].outcome, "leased")

    def test_meters_flagged_separate_state_and_failure_isolation(self):
        cfg = sc.RefreshConfig(meters_enabled=True)
        store, up = FakeStore(), Upstream()
        up.fail.add(sc.METER_OCCUPANCY_URL)
        r = sc.refresh_once(store, up, cfg, clock=at(NOW))
        self.assertEqual(r.sources, {"td_carpark": "published", "td_meters": "failed"})
        self.assertIn("HK_TD", manifest(store)["regions"])
        up.fail.clear()
        r = sc.refresh_once(store, up, cfg, clock=at(NOW + timedelta(seconds=40)))
        self.assertEqual(r.sources, {"td_meters": "published"})  # carpark not due: no coupling
        m = manifest(store)
        self.assertEqual(m["regions"]["HK_TD_METERS"]["source_snapshot_ts"], "2026-11-08T06:01:00+08:00")
        self.assertNotEqual(m["sources"]["td_meters"]["checked_at"], m["sources"]["td_carpark"]["checked_at"])
        self.assertEqual(sc.refresh_once(store, up, CFG, clock=at(NOW + timedelta(minutes=5))).sources,
                         {"td_carpark": "not_modified"})  # flag off: meters untouched


class ReaderTests(unittest.TestCase):
    def settings(self, **kw):
        return Settings(internal_api_key="k", gcs_state_bucket="b", snapshot_on_demand_refresh_enabled=True, **kw)

    def test_reader_refreshes_and_never_regresses_generation(self):
        store, up = FakeStore(), Upstream()
        src = GcsSnapshotSource("b", 45, store=store, settings=self.settings(), fetch=up, clock=at(NOW))
        regions = asyncio.run(src.load_regions())
        self.assertEqual([r.region_id for r in regions], ["HK_TD"])
        gen = int(src._latest_generation[sc.MANIFEST_OBJECT])
        src.prime(sc.MANIFEST_OBJECT, gen - 1, {"regions": {}})  # stale view must lose
        self.assertEqual(int(src._latest_generation[sc.MANIFEST_OBJECT]), gen)

    def test_stale_data_served_within_wait_budget_while_upstream_hangs(self):
        store, up = FakeStore(), Upstream()
        sc.refresh_once(store, up, CFG, clock=at(NOW))
        gate = threading.Event()
        up.vac_etag = '"v2"'
        up.hook = lambda: gate.wait(5)
        clock = at(NOW + timedelta(minutes=2))
        src = GcsSnapshotSource("b", 45, store=store, settings=self.settings(snapshot_refresh_wait_seconds=0.2),
                                fetch=up, clock=clock)

        async def go():
            await src.load_regions(refresh=False)  # warm memory cache (stale view)
            started = time.monotonic()
            regions = await src.load_regions()
            elapsed = time.monotonic() - started
            gate.set()
            await src._refresh_task
            return regions, elapsed

        regions, elapsed = asyncio.run(go())
        self.assertLess(elapsed, 1.5)
        self.assertEqual(regions[0].region_id, "HK_TD")

    def test_disabled_flag_never_writes_and_empty_manifest_is_unavailable(self):
        store = FakeStore()
        src = GcsSnapshotSource("b", 45, store=store, settings=Settings(internal_api_key="k", gcs_state_bucket="b"))
        with self.assertRaises(SnapshotUnavailable):
            asyncio.run(src.load_regions())
        self.assertEqual(store.writes, [])
        sc.refresh_once(FakeStore(), Upstream(), CFG, clock=at(NOW))
        empty = FakeStore({sc.MANIFEST_OBJECT: b'{"regions":{}}'})
        with self.assertRaises(SnapshotUnavailable):
            asyncio.run(GcsSnapshotSource("b", 45, store=empty).load_regions())

    def test_manifest_degrade_override_applies(self):
        store = FakeStore()
        sc.refresh_once(store, Upstream(), CFG, clock=at(NOW))
        m = manifest(store)
        m["regions"]["HK_TD"]["source_status"] = "degraded"
        store.objects[sc.MANIFEST_OBJECT] = (json.dumps(m).encode(), store.objects[sc.MANIFEST_OBJECT][1] + 1)
        regions = asyncio.run(GcsSnapshotSource("b", 45, store=store).load_regions(refresh=False))
        self.assertEqual(regions[0].source_status, "degraded")


if __name__ == "__main__":
    unittest.main()
