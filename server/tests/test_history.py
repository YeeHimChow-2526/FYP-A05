"""History cold path: schedule boundaries + idempotent capture, fake store only (no GCS)."""

import json
import unittest
from datetime import UTC, datetime, timedelta

from app import history as h
from app import shared_cache as sc
from app.ingest import td_carpark as td
from test_ingest_td import NOW, FakeStore
from test_shared_cache import CFG, Upstream, at


def hkt(hh, mm, day=8):
    return datetime(2026, 11, day, hh, mm, tzinfo=h.HKT)


class ScheduleTests(unittest.TestCase):
    def test_boundaries_half_open(self):
        cases = {
            (0, 59): (15, "0045"), (1, 0): (60, "0100"), (1, 5): (60, "0100"), (5, 59): (60, "0500"),
            (6, 0): (15, "0600"), (7, 29): (15, "0715"), (7, 30): (5, "0730"), (9, 29): (5, "0925"),
            (9, 30): (15, "0930"), (17, 29): (15, "1715"), (17, 30): (5, "1730"), (19, 59): (5, "1955"),
            (20, 0): (15, "2000"), (23, 59): (15, "2345"), (0, 0): (15, "0000"),
        }
        for (hh, mm), (interval, key) in cases.items():
            s = h.slot_for(hkt(hh, mm))
            self.assertEqual((s.interval_minutes, s.key), (interval, key), (hh, mm))

    def test_slot_anchors(self):
        self.assertEqual(h.slot_for(hkt(8, 4)).key, "0800")
        self.assertEqual(h.slot_for(hkt(8, 5)).key, "0805")
        self.assertEqual(h.slot_for(hkt(10, 14)).key, "1000")
        self.assertEqual(h.slot_for(hkt(10, 15)).key, "1015")
        self.assertEqual(h.slot_for(hkt(3, 59)).key, "0300")

    def test_utc_to_hkt_date_and_midnight(self):
        utc = datetime(2026, 11, 7, 16, 0, tzinfo=UTC)  # = 2026-11-08 00:00 HKT
        s = h.slot_for(utc)
        self.assertEqual((s.start.date().isoformat(), s.key), ("2026-11-08", "0000"))
        self.assertIn("dt=20261108/hour=00/", h.object_name(s, "HK_TD", False))
        self.assertEqual(h.slot_for(utc - timedelta(seconds=1)).start.date().isoformat(), "2026-11-07")
        with self.assertRaises(ValueError):
            h.slot_for(datetime(2026, 11, 8, 8, 0))


def live_store():
    store = FakeStore()
    sc.refresh_once(store, Upstream(), CFG, clock=at(NOW))
    return store


class CaptureTests(unittest.TestCase):
    def history_objects(self, store):
        return sorted(k for k in store.objects if k.startswith("history/"))

    def test_capture_writes_once_per_slot_across_instances_and_retries(self):
        store = live_store()
        live_before = dict(store.objects)
        t = NOW + timedelta(minutes=1)
        first = h.capture_once(store, clock=at(t))
        second = h.capture_once(store, clock=at(t + timedelta(minutes=3)))  # same 15-min slot
        self.assertEqual(first.regions, {"HK_TD": "written"})
        self.assertEqual(second.regions, {"HK_TD": "exists"})
        objs = self.history_objects(store)
        self.assertEqual(len(objs), 1)
        self.assertIn("history/grid_occupancy/dt=20261105/hour=22/region=HK_TD/slot=2230.ndjson", objs)
        for k, v in live_before.items():
            self.assertEqual(store.objects[k], v)  # live manifest/snapshots untouched

    def test_record_time_fields_are_distinct_and_unknown_not_zero(self):
        store = live_store()
        t = NOW + timedelta(minutes=1)
        h.capture_once(store, clock=at(t))
        rows = [json.loads(line) for line in store.objects[self.history_objects(store)[0]][0].decode().splitlines()]
        by = {r["grid_id"]: r for r in rows}
        a = by["tdc_a_fresh"]
        self.assertEqual(a["vacant_count"], 42)
        self.assertEqual(a["record_source_updated_at"], "2026-11-05T22:25:00+08:00")
        self.assertEqual(a["record_source_age_seconds"], 360)
        self.assertEqual(a["captured_at"], t.isoformat())
        self.assertEqual(a["checked_at"], NOW.isoformat())
        self.assertNotEqual(a["source_snapshot_ts"], a["captured_at"])
        self.assertIsNone(by["tdc_b_flag"]["vacant_count"])
        self.assertEqual(by["tdc_b_flag"]["vacancy_status"], "NO_COUNT")

    def test_same_source_data_next_slot_is_labelled_with_real_age(self):
        store = live_store()
        up = Upstream()
        h.capture_once(store, clock=at(NOW + timedelta(minutes=1)))
        later = NOW + timedelta(minutes=20)
        sc.refresh_once(store, up, CFG, clock=at(later))  # 304: only checked_at moves
        h.capture_once(store, clock=at(later))
        objs = self.history_objects(store)
        self.assertEqual(len(objs), 2)
        row = json.loads(store.objects[objs[-1]][0].decode().splitlines()[0])
        self.assertEqual(row["source_snapshot_ts"], json.loads(store.objects[objs[0]][0].decode().splitlines()[0])["source_snapshot_ts"])
        self.assertGreater(row["snapshot_source_age_seconds"], 20 * 60)
        self.assertEqual(row["checked_at"], later.isoformat())

    def test_no_backfill_for_missed_slots(self):
        store = live_store()
        h.capture_once(store, clock=at(NOW + timedelta(minutes=1)))
        h.capture_once(store, clock=at(NOW + timedelta(hours=2)))
        self.assertEqual(len(self.history_objects(store)), 2)

    def test_empty_bad_and_demo_separation(self):
        self.assertEqual(h.capture_once(FakeStore(), clock=at(NOW)).outcome, "no_live_snapshot")
        bad = FakeStore({sc.MANIFEST_OBJECT: b"nope"})
        self.assertEqual(h.capture_once(bad, clock=at(NOW)).outcome, "bad_manifest")
        self.assertEqual(bad.writes, [])
        demo_snap = {"region_id": "MK_DEMO", "source": "DEMO", "source_snapshot_ts": NOW.isoformat(),
                     "processed_at": NOW.isoformat(), "source_status": "ok", "carparks": []}
        seed = {"regions": {"MK_DEMO": {"object": "regions/MK_DEMO/snapshot.json"}}, "is_demo": True}
        store = FakeStore({sc.MANIFEST_OBJECT: json.dumps(seed).encode(),
                           "regions/MK_DEMO/snapshot.json": json.dumps(demo_snap).encode()})
        sc.refresh_once(store, Upstream(), CFG, clock=at(NOW))
        r = h.capture_once(store, clock=at(NOW))
        self.assertEqual(r.regions, {"HK_TD": "written", "MK_DEMO": "skipped_demo"})
        r = h.capture_once(store, clock=at(NOW), include_demo=True)
        self.assertEqual(r.regions["MK_DEMO"], "written")
        self.assertTrue(any(k.startswith("history/demo/") for k in store.objects))
        self.assertIn("MK_DEMO", store.json(sc.MANIFEST_OBJECT)["regions"])

    def test_demo_rows_never_borrow_a_real_source_checked_at(self):
        # review t1235: checked_at belongs to the region's own source; demo has none.
        demo_snap = {"region_id": "MK_DEMO", "source": "DEMO", "source_snapshot_ts": NOW.isoformat(),
                     "processed_at": NOW.isoformat(), "source_status": "ok",
                     "carparks": [{"carpark_id": "D1", "name": "D1", "type": "INDOOR", "lat": 22.3, "lng": 114.1, "vacancies": 3}]}
        seed = {"regions": {"MK_DEMO": {"object": "regions/MK_DEMO/snapshot.json"}}, "is_demo": True}
        store = FakeStore({sc.MANIFEST_OBJECT: json.dumps(seed).encode(),
                           "regions/MK_DEMO/snapshot.json": json.dumps(demo_snap).encode()})
        sc.refresh_once(store, Upstream(), sc.RefreshConfig(meters_enabled=True), clock=at(NOW))
        self.assertIsNotNone(store.json(sc.MANIFEST_OBJECT)["sources"]["td_meters"]["checked_at"])
        h.capture_once(store, clock=at(NOW), include_demo=True)
        name = next(k for k in store.objects if k.startswith("history/demo/"))
        row = json.loads(store.objects[name][0].decode().splitlines()[0])
        self.assertIsNone(row["checked_at"])

    def test_meters_off_means_no_meter_history(self):
        store = live_store()
        self.assertNotIn("HK_TD_METERS", h.capture_once(store, clock=at(NOW)).regions)
        sc.refresh_once(store, Upstream(), sc.RefreshConfig(meters_enabled=True), clock=at(NOW + timedelta(seconds=61)))
        r = h.capture_once(store, clock=at(NOW + timedelta(minutes=16)))
        self.assertEqual(r.regions.get("HK_TD_METERS"), "written")

    def test_history_write_failure_bounded_and_live_unaffected(self):
        store = live_store()
        live_manifest = store.objects[sc.MANIFEST_OBJECT]
        calls = []
        real = store.write

        def flaky(name, data, gen):
            if name.startswith("history/"):
                calls.append(name)
                raise OSError("gcs down")
            return real(name, data, gen)

        store.write = flaky
        r = h.capture_once(store, clock=at(NOW))
        self.assertEqual(r.regions, {"HK_TD": "failed:OSError"})
        self.assertEqual(len(calls), h.WRITE_ATTEMPTS)
        self.assertEqual(store.objects[sc.MANIFEST_OBJECT], live_manifest)
        later = sc.refresh_once(store, Upstream(vac_etag='"v2"', vac=Upstream().vac.replace(b"42", b"40")),
                                CFG, clock=at(NOW + timedelta(minutes=2)))
        self.assertEqual(later.outcome, "published")  # live refresh unaffected by history failure

    def test_retry_after_lost_response_is_idempotent(self):
        store = live_store()
        real = store.write
        state = {"n": 0}

        def lost_ack(name, data, gen):
            if name.startswith("history/") and state["n"] == 0:
                state["n"] += 1
                real(name, data, gen)
                raise OSError("connection reset after write")
            return real(name, data, gen)

        store.write = lost_ack
        r = h.capture_once(store, clock=at(NOW))
        self.assertEqual(r.regions, {"HK_TD": "exists"})
        self.assertEqual(len([k for k in store.objects if k.startswith("history/")]), 1)

    def test_source_regression_not_rewritten_by_history(self):
        store = live_store()
        before = store.json(sc.MANIFEST_OBJECT)["regions"]["HK_TD"]["source_snapshot_ts"]
        h.capture_once(store, clock=at(NOW))
        self.assertEqual(store.json(sc.MANIFEST_OBJECT)["regions"]["HK_TD"]["source_snapshot_ts"], before)


if __name__ == "__main__":
    unittest.main()
