import json
import unittest
from datetime import UTC, datetime, timedelta, timezone
from pathlib import Path

from fastapi.testclient import TestClient

from app.config import Settings
from app.ingest import td_carpark as td
from app.models import RegionSnapshot, SnapshotManifest
from main import create_app
from test_api import FakeRepository, FakeSnapshots

FIXTURES = Path(__file__).parent / "fixtures"
HKT = timezone(timedelta(hours=8))
NOW = datetime(2026, 11, 5, 22, 30, tzinfo=HKT).astimezone(UTC)
SOURCE_TS = datetime(2026, 11, 5, 14, 29, 30, tzinfo=UTC)


def _raw(name):
    return (FIXTURES / name).read_bytes()


def _load(name):
    return json.loads(_raw(name).decode("utf-8-sig"))


class FakeStore:
    def __init__(self, objects=None):
        self.objects = {k: (v, 1) for k, v in (objects or {}).items()}
        self.writes = []
        self.fail_on = set()

    def read(self, name):
        if name not in self.objects:
            return None, 0
        return self.objects[name]

    def write(self, name, data, if_generation_match):
        current = self.objects.get(name, (None, 0))[1]
        if name in self.fail_on or current != if_generation_match:
            raise td.PreconditionFailed(name)
        self.objects[name] = (data, current + 1)
        self.writes.append(name)

    def json(self, name):
        return json.loads(self.objects[name][0].decode("utf-8"))


class FakeFetcher:
    def __init__(self, vacancy=None, info=None, etag='"v1"', not_modified=False, fail=False):
        self.vacancy = vacancy if vacancy is not None else _raw("td_vacancy_sample.json")
        self.info = info if info is not None else _raw("td_info_sample.json")
        self.etag = etag
        self.not_modified = not_modified
        self.fail = fail
        self.calls = []

    def __call__(self, url, etag):
        self.calls.append((url, etag))
        if self.fail:
            raise td.FetchError("boom")
        if url == td.VACANCY_URL:
            if self.not_modified and etag == self.etag:
                return td.FetchResult(status=304, etag=etag)
            return td.FetchResult(200, self.vacancy, self.etag, SOURCE_TS)
        return td.FetchResult(200, self.info, '"i1"', SOURCE_TS)


class TransformTests(unittest.TestCase):
    def setUp(self):
        self.snapshot, self.stats = td.transform(
            _load("td_vacancy_sample.json"), _load("td_info_sample.json"), NOW, SOURCE_TS
        )
        self.by_id = {c["carpark_id"]: c for c in self.snapshot["carparks"]}

    def test_mapping_cases(self):
        self.assertEqual(self.by_id["tdc_a_fresh"]["vacancies"], 42)
        self.assertEqual(self.by_id["tdc_a_fresh"]["name"], "apm 停車場")
        self.assertEqual(self.by_id["tdc_a_fresh"]["region_id"], "KWUN_TONG")
        self.assertEqual(self.by_id["tdc_a_fresh"]["type"], "INDOOR")
        self.assertEqual(
            (self.by_id["tdc_a_fresh"]["lat"], self.by_id["tdc_a_fresh"]["lng"]),
            (22.31255, 114.22512),
        )
        self.assertEqual(self.by_id["tdc_b_full"]["vacancies"], 0)
        self.assertEqual(self.by_id["tdc_b_flag"]["vacancies"], -1)
        self.assertEqual(self.by_id["tdc_c_closed"]["vacancies"], -1)
        self.assertFalse(self.by_id["tdc_c_closed"]["is_open"])
        self.assertEqual(self.by_id["tdc_c_closed"]["region_id"], "CENTRAL_AND_WESTERN")
        self.assertEqual(self.by_id["tdc_a_stale"]["vacancies"], -1)
        self.assertTrue(self.by_id["tdc_a_stale"]["is_open"])

    def test_dropped_rows(self):
        for dropped in ("tdc_out_bbox", "tdc_no_info", "tdc_moto_only", "tdc_monthly_only"):
            self.assertNotIn(dropped, self.by_id)
        self.assertEqual(self.stats["in"], 9)
        self.assertEqual(self.stats["out"], 5)
        self.assertEqual(self.stats["bad_geo"], 1)
        self.assertEqual(self.stats["no_info"], 1)
        self.assertEqual(self.stats["no_private_hourly"], 2)
        self.assertEqual(self.stats["with_vacancy"], 2)

    def test_snapshot_validates_and_is_degraded_when_small(self):
        region = RegionSnapshot.model_validate(self.snapshot)
        self.assertEqual(region.region_id, "HK_TD")
        self.assertEqual(region.source_snapshot_ts, SOURCE_TS.isoformat())
        self.assertEqual(region.source_status, "degraded")  # < 300 carparks
        self.assertFalse(self.snapshot["is_demo"])

    def test_manifest_values_are_strings_and_validate(self):
        manifest = td.build_manifest(self.snapshot, None, include_demo=False)
        SnapshotManifest.model_validate(manifest)
        self.assertEqual(list(manifest["regions"]), ["HK_TD"])
        for value in manifest["regions"]["HK_TD"].values():
            self.assertIsInstance(value, str)
        self.assertFalse(manifest["is_demo"])

    def test_manifest_keeps_demo_region_only_when_asked(self):
        existing = {"regions": {"MK_DEMO": {"object": "regions/MK_DEMO/snapshot.json"}}}
        self.assertNotIn(
            "MK_DEMO", td.build_manifest(self.snapshot, existing, include_demo=False)["regions"]
        )
        kept = td.build_manifest(self.snapshot, existing, include_demo=True)
        self.assertEqual(kept["regions"]["MK_DEMO"]["object"], "regions/MK_DEMO/snapshot.json")
        SnapshotManifest.model_validate(kept)


class RunOnceTests(unittest.TestCase):
    def test_first_run_writes_region_then_manifest_then_state(self):
        store = FakeStore()
        result = td.run_once(store, FakeFetcher(), now=NOW)
        self.assertEqual(result.exit_code, 0)
        self.assertEqual(result.outcome, "written")
        self.assertEqual(
            store.writes, [td.SNAPSHOT_OBJECT, td.MANIFEST_OBJECT, td.STATE_OBJECT]
        )
        SnapshotManifest.model_validate(store.json(td.MANIFEST_OBJECT))
        RegionSnapshot.model_validate(store.json(td.SNAPSHOT_OBJECT))
        state = store.json(td.STATE_OBJECT)
        self.assertEqual(state["etag"], '"v1"')
        self.assertEqual(state["consecutive_failures"], 0)

    def test_not_modified_makes_no_writes(self):
        store = FakeStore()
        fetcher = FakeFetcher(not_modified=True)
        td.run_once(store, fetcher, now=NOW)
        store.writes.clear()
        result = td.run_once(store, fetcher, now=NOW)
        self.assertEqual(result.outcome, "not_modified")
        self.assertEqual(store.writes, [])
        self.assertEqual(fetcher.calls[-1], (td.VACANCY_URL, '"v1"'))

    def test_same_hash_makes_no_writes(self):
        store = FakeStore()
        td.run_once(store, FakeFetcher(etag='"a"'), now=NOW)
        store.writes.clear()
        fetcher = FakeFetcher(etag='"b"')
        result = td.run_once(store, fetcher, now=NOW)
        self.assertEqual(result.outcome, "unchanged")
        self.assertEqual(store.writes, [])
        self.assertEqual(len(fetcher.calls), 1)  # info feed not fetched

    def test_manifest_not_rewritten_when_unchanged(self):
        store = FakeStore()
        td.run_once(store, FakeFetcher(), now=NOW)
        store.writes.clear()
        changed = _load("td_vacancy_sample.json")
        changed["car_park"][0]["vehicle_type"][0]["service_category"][0]["vacancy"] = 41
        body = json.dumps(changed).encode()
        result = td.run_once(store, FakeFetcher(vacancy=body, etag='"v2"'), now=NOW)
        self.assertEqual(result.outcome, "written")
        self.assertEqual(store.writes, [td.SNAPSHOT_OBJECT, td.STATE_OBJECT])

    def test_existing_demo_manifest_is_replaced_unless_include_demo(self):
        demo = json.dumps(
            {"source": "DEMO", "is_demo": True,
             "regions": {"MK_DEMO": {"object": "regions/MK_DEMO/snapshot.json"}}}
        ).encode()
        store = FakeStore({td.MANIFEST_OBJECT: demo})
        td.run_once(store, FakeFetcher(), now=NOW)
        self.assertEqual(list(store.json(td.MANIFEST_OBJECT)["regions"]), ["HK_TD"])

        store = FakeStore({td.MANIFEST_OBJECT: demo})
        td.run_once(store, FakeFetcher(), now=NOW, include_demo=True)
        self.assertEqual(
            sorted(store.json(td.MANIFEST_OBJECT)["regions"]), ["HK_TD", "MK_DEMO"]
        )

    def test_precondition_failure_means_another_run_won(self):
        store = FakeStore()
        store.fail_on.add(td.SNAPSHOT_OBJECT)
        result = td.run_once(store, FakeFetcher(), now=NOW)
        self.assertEqual(result.exit_code, 0)
        self.assertTrue(result.outcome.startswith("precondition_failed"))
        self.assertEqual(store.writes, [])

    def test_fetch_failures_keep_snapshot_then_degrade(self):
        store = FakeStore()
        td.run_once(store, FakeFetcher(), now=NOW)
        good_snapshot = store.objects[td.SNAPSHOT_OBJECT][0]
        for attempt in (1, 2):
            store.writes.clear()
            result = td.run_once(store, FakeFetcher(fail=True), now=NOW)
            self.assertEqual(result.exit_code, 1)
            self.assertEqual(store.writes, [td.STATE_OBJECT])
            self.assertEqual(store.objects[td.SNAPSHOT_OBJECT][0], good_snapshot)
            self.assertEqual(store.json(td.STATE_OBJECT)["consecutive_failures"], attempt)
        store.writes.clear()
        result = td.run_once(store, FakeFetcher(fail=True), now=NOW)
        self.assertEqual(result.exit_code, 1)
        self.assertEqual(store.json(td.SNAPSHOT_OBJECT)["source_status"], "degraded")
        self.assertEqual(
            store.json(td.MANIFEST_OBJECT)["regions"]["HK_TD"]["source_status"], "degraded"
        )
        self.assertEqual(store.json(td.STATE_OBJECT)["consecutive_failures"], 3)
        # recovery resets the counter
        recovered = _load("td_vacancy_sample.json")
        recovered["car_park"][0]["vehicle_type"][0]["service_category"][0]["vacancy"] = 7
        td.run_once(store, FakeFetcher(vacancy=json.dumps(recovered).encode(), etag='"v9"'), now=NOW)
        self.assertEqual(store.json(td.STATE_OBJECT)["consecutive_failures"], 0)


class HttpFetchTests(unittest.TestCase):
    def test_backoff_retries_then_raises(self):
        sleeps = []
        with self.assertRaises(td.FetchError):
            td.http_fetch("http://127.0.0.1:9/never", attempts=4, timeout=0.2, sleep=sleeps.append)
        self.assertEqual(sleeps, [2, 2, 2])


class IngestedCardsTests(unittest.TestCase):
    def test_recommend_cards_from_td_snapshot_include_lat_lng(self):
        snapshot, _ = td.transform(
            _load("td_vacancy_sample.json"),
            _load("td_info_sample.json"),
            datetime.now(UTC),
            datetime.now(UTC),
        )
        region = RegionSnapshot.model_validate(snapshot)
        app = create_app(
            Settings(internal_api_key="k", gcs_state_bucket="unused"),
            repository=FakeRepository(),
            snapshots=FakeSnapshots([region]),
        )
        with TestClient(app) as client:
            response = client.post(
                "/v1/recommend-and-route",
                headers={"X-Internal-API-Key": "k"},
                json={
                    "session_id": "sess_td",
                    "origin_lat": 22.31,
                    "origin_lng": 114.22,
                    "dest_lat": 22.31255,
                    "dest_lng": 114.22512,
                },
            )
        self.assertEqual(response.status_code, 200)
        cards = response.json()["cards"]
        self.assertTrue(cards)
        by_id = {card["carpark_id"]: card for card in cards}
        self.assertIn("tdc_a_fresh", by_id)
        self.assertEqual(by_id["tdc_a_fresh"]["lat"], 22.31255)
        self.assertEqual(by_id["tdc_a_fresh"]["lng"], 114.22512)
        for card in cards:
            self.assertIsInstance(card["lat"], float)
            self.assertIsInstance(card["lng"], float)
