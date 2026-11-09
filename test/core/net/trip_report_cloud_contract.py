"""Feed one App encoder body into cloud commit 15aab20. Local fake repository only."""
import json
import os
import subprocess
import sys
from datetime import datetime, timezone

EXPECTED = "15aab205615ea56948d9f76105c2ce0ec2b5c82c"


def main() -> None:
    payload_path, server_dir, result_path = sys.argv[1], sys.argv[2], sys.argv[3]
    cloud_root = os.path.dirname(server_dir)
    sha = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=cloud_root, text=True).strip()
    if sha != EXPECTED:
        raise SystemExit(f"cloud HEAD {sha} != {EXPECTED}")
    sys.path.insert(0, server_dir)
    os.chdir(server_dir)

    from fastapi.testclient import TestClient
    from pydantic import ValidationError

    from app.config import Settings
    from app.models import TripReportRequest
    from main import create_app

    raw = json.loads(open(payload_path, encoding="utf-8").read())
    app_body = raw["app_body"]
    flat_body = raw["flat_body"]

    class FakeRepository:
        def __init__(self) -> None:
            self.reports = []

        async def close(self):
            return None

        async def submit_trip_report(self, session_id, trip_timestamp, feedback):
            self.reports.append(
                {"session_id": session_id, "trip_timestamp": trip_timestamp, "feedback": feedback}
            )

    parsed = TripReportRequest.model_validate_json(app_body)
    flat_error = None
    try:
        TripReportRequest.model_validate_json(flat_body)
    except ValidationError as error:
        flat_error = error.error_count()

    repo = FakeRepository()
    app = create_app(
        Settings(internal_api_key="test-key", gcs_state_bucket="unused-in-tests"),
        repository=repo,
        snapshots=object(),
    )
    with TestClient(app) as client:
        headers = {"X-Internal-API-Key": "test-key", "Content-Type": "application/json"}
        body_bytes = app_body.encode("utf-8")
        first = client.post("/v1/feedback/trip-report", headers=headers, content=body_bytes)
        second = client.post("/v1/feedback/trip-report", headers=headers, content=body_bytes)
        duplicate_writes = len(repo.reports)
        flat = client.post("/v1/feedback/trip-report", headers=headers, content=flat_body.encode("utf-8"))
        loose = json.loads(app_body)
        loose["feedback"] = {"rating_tag": "NOPE", "perk_status_tag": "MAYBE", "perk_notes": "x" * 500}
        bad_enum = client.post("/v1/feedback/trip-report", headers=headers, json=loose)
        # 2026-11-10 00:00 Asia/Hong_Kong == 2026-11-09 16:00 UTC. Route has no midnight reject.
        midnight_ts = int(datetime(2026, 11, 9, 16, 0, tzinfo=timezone.utc).timestamp())
        midnight = json.loads(app_body)
        midnight["trip_timestamp"] = midnight_ts
        midnight_response = client.post("/v1/feedback/trip-report", headers=headers, json=midnight)

    result = {
        "sha": sha,
        "parsed_feedback": parsed.feedback,
        "flat_error_count": flat_error,
        "success": first.status_code,
        "stored": repo.reports[0]["feedback"] if repo.reports else None,
        "duplicate_statuses": [first.status_code, second.status_code],
        "duplicate_writes": duplicate_writes,
        "flat_status": flat.status_code,
        "bad_enum_status": bad_enum.status_code,
        "midnight_status": midnight_response.status_code,
        "midnight_timestamp": midnight_ts,
    }
    with open(result_path, "w", encoding="utf-8") as handle:
        json.dump(result, handle, ensure_ascii=False)


if __name__ == "__main__":
    main()
