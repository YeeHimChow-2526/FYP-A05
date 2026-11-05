import hashlib
import hmac
from datetime import UTC, datetime, timedelta
from typing import Any

from google.cloud import firestore
from google.cloud.firestore_v1.async_transaction import async_transactional

from .config import Settings


class LockFullError(RuntimeError):
    pass


class FirestoreRepository:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._client = firestore.AsyncClient(project=settings.project_id)

    async def close(self) -> None:
        await self._client.close()

    async def touch_session(
        self,
        session_id: str,
        destination: dict[str, Any] | None = None,
        *,
        status: str | None = None,
        ratable: bool | None = None,
        completed_at: datetime | None = None,
    ) -> None:
        now = datetime.now(UTC)
        ref = self._client.collection("active_sessions").document(session_id)
        snapshot = await ref.get()
        current = snapshot.to_dict() or {}
        current_expiry = current.get("expire_at")
        if current_expiry is not None and current_expiry <= now:
            await ref.delete()
            await self._client.collection("virtual_locks").document(session_id).delete()
            current = {}
        if destination is not None and current.get("destination") not in (None, destination):
            await self._client.collection("virtual_locks").document(session_id).delete()

        next_status = status or current.get("status", "ACTIVE")
        if next_status == "PARKED_CONFIRMED":
            completed_at = current.get("completed_at") or completed_at or now
            expire_at = _end_of_hkt_day(completed_at)
        else:
            timeout = (
                timedelta(hours=self._settings.committed_session_idle_timeout_hours)
                if next_status == "COMMITTED"
                else timedelta(minutes=self._settings.active_session_idle_timeout_minutes)
            )
            expire_at = now + timeout

        values: dict[str, Any] = {
            "status": next_status,
            "last_seen_at": now,
            "expire_at": expire_at,
            "updated_at": now,
        }
        if destination is not None:
            values["destination"] = destination
        if next_status == "PARKED_CONFIRMED":
            values["completed_at"] = completed_at
        if ratable is not None:
            values["ratable"] = ratable
        elif "ratable" not in current:
            values["ratable"] = False
        await ref.set(values, merge=True)

    async def get_session(self, session_id: str) -> dict[str, Any] | None:
        snapshot = await self._client.collection("active_sessions").document(session_id).get()
        if not snapshot.exists:
            return None
        values = snapshot.to_dict() or {}
        expire_at = values.get("expire_at")
        if expire_at and expire_at <= datetime.now(UTC):
            return None
        return values

    async def count_locks(self, carpark_id: str, *, exclude_session_id: str | None = None) -> int:
        cutoff = datetime.now(UTC) - timedelta(minutes=self._settings.virtual_lock_timeout_minutes)
        query = (
            self._client.collection("virtual_locks")
            .where("carpark_id", "==", carpark_id)
            .where("created_at", ">=", cutoff)
        )
        result = await query.count(alias="active_locks").get()
        count = int(result[0][0].value)
        if exclude_session_id is not None:
            own_lock = await (
                self._client.collection("virtual_locks")
                .document(exclude_session_id)
                .get()
            )
            own_values = own_lock.to_dict() or {}
            own_created_at = own_values.get("created_at")
            if (
                own_lock.exists
                and own_values.get("carpark_id") == carpark_id
                and own_created_at is not None
                and own_created_at >= cutoff
            ):
                count -= 1
        return max(count, 0)

    async def get_pain_point(self, carpark_id: str) -> dict[str, Any]:
        snapshot = await (
            self._client.collection("carpark_pain_points")
            .document(carpark_id)
            .get()
        )
        return snapshot.to_dict() or {}

    async def get_perk(self, carpark_id: str) -> dict[str, Any]:
        snapshot = await (
            self._client.collection("carpark_perks_master")
            .document(carpark_id)
            .get()
        )
        return snapshot.to_dict() or {}

    async def commit_lock(
        self,
        session_id: str,
        carpark_id: str,
        vacancies: int,
    ) -> None:
        now = datetime.now(UTC)
        lock_ref = self._client.collection("virtual_locks").document(session_id)
        session_ref = self._client.collection("active_sessions").document(session_id)
        cutoff = now - timedelta(minutes=self._settings.virtual_lock_timeout_minutes)
        transaction = self._client.transaction()

        @async_transactional
        async def apply_lock(transaction: Any) -> None:
            existing = await lock_ref.get(transaction=transaction)
            session_snapshot = await session_ref.get(transaction=transaction)
            session_values = session_snapshot.to_dict() or {}
            session_expiry = session_values.get("expire_at")
            expired_session = session_expiry is not None and session_expiry <= now
            query = (
                self._client.collection("virtual_locks")
                .where("carpark_id", "==", carpark_id)
                .where("created_at", ">=", cutoff)
            )
            count_result = await query.count(alias="active_locks").get(
                transaction=transaction
            )
            active_count = int(count_result[0][0].value)
            prior_lock = existing.to_dict() or {}
            prior_created_at = prior_lock.get("created_at")
            if (
                existing.exists
                and prior_lock.get("carpark_id") == carpark_id
                and prior_created_at is not None
                and prior_created_at >= cutoff
            ):
                active_count = max(active_count - 1, 0)
            if vacancies != -1 and vacancies - active_count <= 0:
                raise LockFullError("No uncommitted vacancies remain")
            created_at = now
            if (
                existing.exists
                and prior_lock.get("carpark_id") == carpark_id
                and prior_created_at is not None
                and prior_created_at >= cutoff
            ):
                created_at = prior_created_at
            session_data = {
                "status": "COMMITTED",
                "ratable": False,
                "target_carpark_id": carpark_id,
                "last_seen_at": now,
                "expire_at": now
                + timedelta(hours=self._settings.committed_session_idle_timeout_hours),
                "updated_at": now,
            }
            transaction.set(
                lock_ref,
                {
                    "carpark_id": carpark_id,
                    "created_at": created_at,
                    "expire_at": created_at
                    + timedelta(minutes=self._settings.virtual_lock_timeout_minutes),
                },
            )
            transaction.set(
                session_ref,
                session_data,
                merge=not expired_session,
            )

        await apply_lock(transaction)

    async def release_lock(self, session_id: str, target_carpark_id: str) -> None:
        await self._client.collection("virtual_locks").document(session_id).delete()
        await self.touch_session(
            session_id,
            status="PARKED_CONFIRMED",
            ratable=False,
        )
        await self._client.collection("active_sessions").document(session_id).set(
            {"target_carpark_id": target_carpark_id}, merge=True
        )

    async def confirm_parked(self, session_id: str, completed_at_timestamp: int) -> None:
        await self._client.collection("virtual_locks").document(session_id).delete()
        await self.touch_session(
            session_id,
            status="PARKED_CONFIRMED",
            ratable=True,
            completed_at=datetime.fromtimestamp(completed_at_timestamp, UTC),
        )

    async def add_crowd_report(
        self,
        *,
        session_id: str,
        carpark_id: str,
        report_type: str,
        client_ip: str,
        client_timestamp: int,
    ) -> dict[str, Any]:
        secret = self._settings.crowd_ip_hash_secret
        if not secret:
            raise RuntimeError("CROWD_IP_HASH_SECRET must be configured")
        now = datetime.now(UTC)
        ip_digest = hmac.new(secret.encode(), client_ip.encode(), hashlib.sha256).hexdigest()
        bucket = int(now.timestamp() // 300)
        report_ref = (
            self._client.collection("carpark_pain_points")
            .document(carpark_id)
            .collection("reports")
            .document(f"{session_id}_{bucket}")
        )
        await self.touch_session(session_id)
        await report_ref.set(
            {
                "session_id": session_id,
                "report_type": report_type,
                "source_ip_hash": ip_digest,
                "ip_bucket": bucket,
                "created_at": now,
                "expire_at": now + timedelta(days=7),
                "client_timestamp": client_timestamp,
            },
            merge=True,
        )
        report_query = (
            self._client.collection("carpark_pain_points")
            .document(carpark_id)
            .collection("reports")
            .where("created_at", ">=", now - timedelta(hours=1))
        )
        recent_reports = [snapshot async for snapshot in report_query.stream()]
        reports_by_ip: dict[str, list[dict[str, Any]]] = {}
        for snapshot in recent_reports:
            values = snapshot.to_dict() or {}
            reports_by_ip.setdefault(values["source_ip_hash"], []).append(values)
        grouped_sessions: set[str] = set()
        for reports in reports_by_ip.values():
            reports.sort(key=lambda report: report["created_at"])
            group_start: datetime | None = None
            group_session: str | None = None
            for report in reports:
                report_time = report["created_at"]
                if (
                    group_start is None
                    or report_time - group_start > timedelta(minutes=5)
                ):
                    if group_session is not None:
                        grouped_sessions.add(group_session)
                    group_start = report_time
                    group_session = report["session_id"]
                else:
                    group_session = min(group_session or report["session_id"], report["session_id"])
            if group_session is not None:
                grouped_sessions.add(group_session)
        independent_sources = len(grouped_sessions)
        await (
            self._client.collection("carpark_pain_points")
            .document(carpark_id)
            .set(
                {
                    "recent_independent_report_sources": independent_sources,
                    "crowd_rank_capped": independent_sources >= 2,
                    "crowd_warning": independent_sources >= 1,
                    "crowd_penalty_points": 45,
                    "crowd_window_expires_at": now + timedelta(hours=1),
                    "updated_at": now,
                },
                merge=True,
            )
        )
        return {
            "status": "ACCEPTED",
            "carpark_id": carpark_id,
            "report_type": report_type,
            "independent_source_count": independent_sources,
            "rank_capped": independent_sources >= 2,
            "yellow_flag": independent_sources >= 1,
            "penalty_points": -45,
        }

    async def submit_trip_report(
        self,
        session_id: str,
        trip_timestamp: int,
        feedback: dict[str, Any],
    ) -> None:
        session = await self.get_session(session_id)
        if session is None:
            raise LookupError("Unknown or expired session")
        if not session.get("ratable", False) or session.get("status") != "PARKED_CONFIRMED":
            raise PermissionError("Trip must be driver-confirmed before rating")
        await (
            self._client.collection("active_sessions")
            .document(session_id)
            .set(
                {
                    "trip_report": {
                        "trip_timestamp": trip_timestamp,
                        "feedback": feedback,
                        "submitted_at": datetime.now(UTC),
                    }
                },
                merge=True,
            )
        )


def _end_of_hkt_day(now: datetime) -> datetime:
    from zoneinfo import ZoneInfo

    hong_kong = now.astimezone(ZoneInfo("Asia/Hong_Kong"))
    next_midnight = (hong_kong + timedelta(days=1)).replace(
        hour=0, minute=0, second=0, microsecond=0
    )
    return next_midnight.astimezone(UTC)
