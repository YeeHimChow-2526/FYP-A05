import unittest
from datetime import UTC, datetime

from fastapi.testclient import TestClient
from starlette.requests import Request

from app.config import Settings
from app.firestore import LockFullError
from main import client_ip_from_request, create_app
from app.models import CarparkSnapshot, RegionSnapshot


class FakeRepository:
    async def close(self):
        return None

    async def touch_session(self, session_id, destination=None, **kwargs):
        return None

    async def count_locks(self, carpark_id, *, exclude_session_id=None):
        return 0

    async def get_pain_point(self, carpark_id):
        return {}

    async def get_perk(self, carpark_id):
        return {}

    async def commit_lock(self, session_id, carpark_id, vacancies):
        self.committed = (session_id, carpark_id, vacancies)

    async def release_lock(self, session_id, target_carpark_id):
        self.released = (session_id, target_carpark_id)

    async def confirm_parked(self, session_id, timestamp):
        self.confirmed = (session_id, timestamp)

    async def add_crowd_report(self, **kwargs):
        return {"status": "ACCEPTED", **kwargs}

    async def submit_trip_report(self, session_id, trip_timestamp, feedback):
        self.trip_report = (session_id, trip_timestamp, feedback)


class FakeSnapshots:
    def __init__(self, regions):
        self.regions = regions

    async def load_regions(self):
        return self.regions


class ApiTests(unittest.TestCase):
    def setUp(self):
        now = datetime.now(UTC).isoformat()
        self.snapshots = FakeSnapshots(
            [
                RegionSnapshot(
                    region_id="KE",
                    source_snapshot_ts=now,
                    processed_at=now,
                    source_status="ok",
                    carparks=[
                        CarparkSnapshot(
                            carpark_id="CP_TEST",
                            name="測試停車場",
                            type="INDOOR",
                            lat=22.3152,
                            lng=114.1685,
                            vacancies=0,
                        )
                    ],
                )
            ]
        )
        self.repository = FakeRepository()
        self.app = create_app(
            Settings(
                internal_api_key="test-key",
                gcs_state_bucket="unused-in-tests",
            ),
            repository=self.repository,
            snapshots=self.snapshots,
        )
        self.client = TestClient(self.app)
        self.client.__enter__()
        self.headers = {"X-Internal-API-Key": "test-key"}

    def tearDown(self):
        self.client.__exit__(None, None, None)

    def test_api_key_is_required(self):
        response = self.client.post(
            "/v1/session/release",
            json={
                "session_id": "sess_1",
                "target_carpark_id": "CP_TEST",
                "reason": "PARKED_CONFIRMED",
            },
        )
        self.assertEqual(response.status_code, 401)

    def test_health_check_does_not_require_api_key(self):
        response = self.client.get("/healthz")
        self.assertEqual(response.status_code, 200)

    def test_client_ip_comes_from_load_balancer_header(self):
        request = Request(
            {
                "type": "http",
                "headers": [
                    (
                        b"x-load-balancer-client-ip",
                        b"203.0.113.8, 198.51.100.12",
                    )
                ],
            }
        )
        self.assertEqual(client_ip_from_request(request), "198.51.100.12")

    def test_recommend_returns_server_freshness_and_cantonese_fallback(self):
        response = self.client.post(
            "/v1/recommend-and-route",
            headers=self.headers,
            json={
                "session_id": "sess_1",
                "origin_lat": 22.3,
                "origin_lng": 114.16,
                "dest_lat": 22.3152,
                "dest_lng": 114.1685,
                "speech_locale": "en-US",
            },
        )
        self.assertEqual(response.status_code, 200)
        payload = response.json()
        self.assertEqual(payload["speech_locale"], "zh-HK")
        self.assertTrue(payload["speech_locale_fallback"])
        self.assertEqual(payload["cards"][0]["vacancies"], 0)
        self.assertIn("server_now", payload)
        self.assertTrue(payload["server_now"].endswith("+08:00"))
        self.assertTrue(payload["await_voice_reply"])

    def test_name_only_recommend_requires_a_poi_catalog(self):
        response = self.client.post(
            "/v1/recommend-and-route",
            headers=self.headers,
            json={
                "session_id": "sess_1",
                "origin_lat": 22.3,
                "origin_lng": 114.16,
                "dest_name": "朗豪坊",
            },
        )
        self.assertEqual(response.status_code, 503)

    def test_commit_uses_snapshot_and_returns_lock_conflict(self):
        class FullRepository(FakeRepository):
            async def commit_lock(self, session_id, carpark_id, vacancies):
                self.vacancies = vacancies
                raise LockFullError("full")

        full_repository = FullRepository()
        app = create_app(
            Settings(internal_api_key="test-key", gcs_state_bucket="unused"),
            repository=full_repository,
            snapshots=self.snapshots,
        )
        with TestClient(app) as client:
            response = client.post(
                "/v1/session/commit",
                headers=self.headers,
                json={"session_id": "sess_1", "target_carpark_id": "CP_TEST"},
            )
        self.assertEqual(response.status_code, 409)
        self.assertEqual(response.json()["status"], "LOCK_FULL")
        self.assertEqual(full_repository.vacancies, 0)

    def test_invalid_release_reason_is_rejected(self):
        response = self.client.post(
            "/v1/session/release",
            headers=self.headers,
            json={
                "session_id": "sess_1",
                "target_carpark_id": "CP_TEST",
                "reason": "APPROACH_MUTED",
            },
        )
        self.assertEqual(response.status_code, 422)

    def test_release_is_idempotent_response_for_parked_confirmation(self):
        response = self.client.post(
            "/v1/session/release",
            headers=self.headers,
            json={
                "session_id": "sess_1",
                "target_carpark_id": "CP_TEST",
                "reason": "PARKED_CONFIRMED",
            },
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["ratable"], False)
        self.assertEqual(self.repository.released, ("sess_1", "CP_TEST"))

    def test_sync_outbox_confirms_driver_parked_event(self):
        response = self.client.post(
            "/v1/sync/outbox",
            headers=self.headers,
            json={
                "session_id": "sess_1",
                "event_type": "DRIVER_CONFIRMED",
                "timestamp": 1791823900,
            },
        )
        self.assertEqual(response.status_code, 200)
        self.assertTrue(response.json()["ratable"])
        self.assertEqual(self.repository.confirmed, ("sess_1", 1791823900))

    def test_crowd_report_uses_load_balancer_ip_header(self):
        response = self.client.post(
            "/v1/feedback/crowd-report",
            headers={
                **self.headers,
                "X-Load-Balancer-Client-IP": "203.0.113.8",
            },
            json={
                "session_id": "sess_1",
                "carpark_id": "CP_TEST",
                "report_type": "BLOCKED_OCCUPIED",
                "client_timestamp": 1791823900,
            },
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["client_ip"], "203.0.113.8")

    def test_trip_report_uses_repository_ratable_checks(self):
        response = self.client.post(
            "/v1/feedback/trip-report",
            headers=self.headers,
            json={
                "session_id": "sess_1",
                "trip_timestamp": 1791823900,
                "feedback": {"parking_success": True},
            },
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(self.repository.trip_report[0], "sess_1")


if __name__ == "__main__":
    unittest.main()
