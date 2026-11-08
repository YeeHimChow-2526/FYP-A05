import hashlib
import hmac
from datetime import UTC, datetime, timedelta
from typing import Any

from google.cloud import firestore
from google.cloud.firestore_v1.async_transaction import async_transactional

from .config import Settings
from .firestore_errors import LockFullError
from . import lock_policy
from .lock_policy import LockConflictError, SessionMissingError

class ContentionError(RuntimeError):
    """Same-document contention exhausted retries (HTTP 503, client may retry)."""


__all__ = ["ContentionError", "FirestoreRepository", "LockFullError", "LockConflictError", "SessionMissingError"]


class FirestoreRepository:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._client = firestore.AsyncClient(project=settings.project_id)

    async def close(self) -> None:
        # AsyncClient.close() is synchronous in current google-cloud-firestore (found via emulator).
        result = self._client.close()
        if hasattr(result, "__await__"):
            await result

    async def _run_transaction(self, body: Any) -> Any:
        """Run ``body(transaction)`` in a Firestore transaction (overridable for tests)."""
        import asyncio
        import random

        @async_transactional
        async def run(transaction: Any) -> Any:
            return await body(transaction)

        attempts = self._settings.firestore_txn_max_attempts
        rounds = self._settings.firestore_txn_outer_rounds
        for round_index in range(rounds):
            try:
                return await run(self._client.transaction(max_attempts=attempts))
            except ValueError as error:
                # Raised by the SDK after max_attempts ABORTED commits (same-document contention).
                if "Failed to commit transaction" not in str(error):
                    raise
                if round_index == rounds - 1:
                    raise ContentionError("Transaction contention; retry later") from error
                await asyncio.sleep(random.uniform(0.05, 0.25) * (round_index + 1))
        raise ContentionError("unreachable")

    def _timeouts(self) -> dict[str, Any]:
        settings = self._settings
        return {
            "active_idle": timedelta(minutes=settings.active_session_idle_timeout_minutes),
            "committed_idle": timedelta(hours=settings.committed_session_idle_timeout_hours),
            "retention_hours": settings.session_retention_hours,
        }

    def _creation_policy(self) -> dict[str, Any]:
        settings = self._settings
        return {
            "create_window": timedelta(hours=settings.session_create_window_hours),
            "clock_skew": timedelta(seconds=settings.client_clock_skew_seconds),
            "legacy_create_enabled": settings.legacy_session_create_enabled,
        }

    def _capacity_ref(self, carpark_id: str) -> Any:
        return self._client.collection("carpark_capacity").document(carpark_id)

    async def _legacy_locks(self, carpark_id: str, cutoff: datetime, transaction: Any) -> list[tuple[str, dict[str, Any]]] | None:
        """Pre-ledger locks for a one-time bootstrap; None (fail closed) unless enabled.

        Only safe once no old-revision instance can still write virtual_locks without the ledger.
        """
        if not self._settings.capacity_bootstrap_enabled:
            return None
        query = (
            self._client.collection("virtual_locks")
            .where("carpark_id", "==", carpark_id)
            .where("created_at", ">=", cutoff)
        )
        return [(snap.id, snap.to_dict() or {}) async for snap in query.stream(transaction=transaction)]

    async def touch_session(
        self,
        session_id: str,
        destination: dict[str, Any] | None = None,
        *,
        destination_epoch: int | None = None,
        require_open: bool = False,
        session_started_at: datetime | None = None,
        create: bool = True,
    ) -> None:
        """Refresh activity. Never reopens a terminal session and never deletes the session doc.

        Raises LockConflictError(SESSION_CLOSED) when ``require_open`` and the session ended, and
        LockConflictError(STALE_EPOCH) for a request older than the stored destination epoch.
        """
        now = datetime.now(UTC)
        session_ref = self._client.collection("active_sessions").document(session_id)
        lock_ref = self._client.collection("virtual_locks").document(session_id)
        timeouts = self._timeouts()

        async def body(transaction: Any) -> None:
            snapshot = await session_ref.get(transaction=transaction)
            current = snapshot.to_dict() if snapshot.exists else None
            patch, delete_legacy_lock = lock_policy.decide_touch(
                session=current,
                destination=destination,
                destination_epoch=destination_epoch,
                require_open=require_open,
                now=now,
                session_started_at=session_started_at,
                create=create,
                **self._creation_policy(),
                **timeouts,
            )
            if patch is None:
                return
            if delete_legacy_lock:
                lock_snapshot = await lock_ref.get(transaction=transaction)
                lock = lock_snapshot.to_dict() if lock_snapshot.exists else None
                cap = None
                if lock is not None and lock.get("carpark_id"):
                    cap_snapshot = await self._capacity_ref(lock["carpark_id"]).get(transaction=transaction)
                    cap = lock_policy.release_capacity_write(
                        session_id=session_id, lock=lock,
                        capacity_doc=cap_snapshot.to_dict() if cap_snapshot.exists else None, now=now,
                    )
                if lock is not None:
                    transaction.delete(lock_ref)
                if cap is not None:
                    transaction.set(self._capacity_ref(lock["carpark_id"]), cap)
            transaction.set(session_ref, patch, merge=True)

        await self._run_transaction(body)

    async def get_session(self, session_id: str) -> dict[str, Any] | None:
        snapshot = await self._client.collection("active_sessions").document(session_id).get()
        if not snapshot.exists:
            return None
        values = snapshot.to_dict() or {}
        if values.get("status") in lock_policy.TERMINAL_STATUSES:
            # Tombstones stay readable (trip reports) until their retention horizon.
            expire_at = values.get("expire_at")
            if expire_at and expire_at <= datetime.now(UTC):
                return None
            return values
        if lock_policy.is_idle_expired(values, datetime.now(UTC)):
            return None
        return values

    async def count_locks(self, carpark_id: str, *, exclude_session_id: str | None = None) -> int:
        """Advisory valid-lease count for ranking (authoritative check happens in commit_lock)."""
        snapshot = await self._capacity_ref(carpark_id).get()
        leases = lock_policy.valid_leases(snapshot.to_dict() if snapshot.exists else None, datetime.now(UTC))
        leases.pop(exclude_session_id or "", None)
        return len(leases)

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
        capacity: "int | lock_policy.CapacityReading | None",
        *,
        destination_epoch: int | None = None,
        idempotency_key: str | None = None,
    ) -> dict[str, Any]:
        """Commit/renew/switch a lock. Session, lock and every affected per-car-park ledger
        (target and, on a switch, the previous car park) are read and written in ONE transaction,
        so concurrent commits on the same car park conflict on the same document."""
        now = datetime.now(UTC)
        lock_ref = self._client.collection("virtual_locks").document(session_id)
        session_ref = self._client.collection("active_sessions").document(session_id)
        lock_timeout = timedelta(minutes=self._settings.virtual_lock_timeout_minutes)
        timeouts = self._timeouts()
        target_ref = self._capacity_ref(carpark_id)

        async def body(transaction: Any) -> dict[str, Any]:
            existing = await lock_ref.get(transaction=transaction)
            session_snapshot = await session_ref.get(transaction=transaction)
            existing_lock = existing.to_dict() if existing.exists else None
            target_snapshot = await target_ref.get(transaction=transaction)
            target_doc = target_snapshot.to_dict() if target_snapshot.exists else None
            old_carpark = (existing_lock or {}).get("carpark_id")
            old_doc = None
            if old_carpark and old_carpark != carpark_id:
                old_snapshot = await self._capacity_ref(old_carpark).get(transaction=transaction)
                old_doc = old_snapshot.to_dict() if old_snapshot.exists else None
            legacy = None
            if target_doc is None or not target_doc.get("bootstrapped"):
                legacy = await self._legacy_locks(carpark_id, now - lock_timeout, transaction)
            decision = lock_policy.decide_commit(
                session_id=session_id,
                session=session_snapshot.to_dict() if session_snapshot.exists else None,
                existing_lock=existing_lock,
                carpark_id=carpark_id,
                capacity=capacity,
                capacity_doc=target_doc,
                old_capacity_doc=old_doc,
                legacy_locks=legacy,
                max_leases=self._settings.capacity_max_leases,
                destination_epoch=destination_epoch,
                idempotency_key=idempotency_key,
                now=now,
                lock_timeout=lock_timeout,
                committed_idle=timeouts["committed_idle"],
                retention_hours=timeouts["retention_hours"],
            )
            if not decision.replay:
                transaction.set(lock_ref, decision.lock)
                transaction.set(session_ref, decision.session_patch, merge=True)
                for cp_id, doc in decision.capacity_writes.items():
                    transaction.set(self._capacity_ref(cp_id), doc)
            return {
                "lock_id": decision.lock.get("lock_id"),
                "lock_revision": decision.lock.get("lock_revision"),
                "destination_epoch": decision.lock.get("destination_epoch", 0),
                "replayed": decision.replay,
            }

        return await self._run_transaction(body)

    async def release_lock(
        self,
        session_id: str,
        target_carpark_id: str,
        *,
        reason: str = "PARKED_CONFIRMED",
        lock_revision: int | None = None,
        new_destination_epoch: int | None = None,
    ) -> dict[str, Any]:
        now = datetime.now(UTC)
        lock_ref = self._client.collection("virtual_locks").document(session_id)
        session_ref = self._client.collection("active_sessions").document(session_id)
        timeouts = self._timeouts()

        async def body(transaction: Any) -> dict[str, Any]:
            lock_snapshot = await lock_ref.get(transaction=transaction)
            session_snapshot = await session_ref.get(transaction=transaction)
            session = session_snapshot.to_dict() if session_snapshot.exists else None
            lock = lock_snapshot.to_dict() if lock_snapshot.exists else None
            capacity_doc = None
            if lock is not None and lock.get("carpark_id"):
                cap_snapshot = await self._capacity_ref(lock["carpark_id"]).get(transaction=transaction)
                capacity_doc = cap_snapshot.to_dict() if cap_snapshot.exists else None
            decision = lock_policy.decide_release(
                session_id=session_id,
                session=session,
                lock=lock,
                reason=reason,
                target_carpark_id=target_carpark_id,
                lock_revision=lock_revision,
                new_destination_epoch=new_destination_epoch,
                now=now,
                active_idle=timeouts["active_idle"],
                retention_hours=timeouts["retention_hours"],
                parked_retention_floor=_end_of_hkt_day(now) if reason == "PARKED_CONFIRMED" else None,
            )
            if decision.delete_lock:
                transaction.delete(lock_ref)
                cap = lock_policy.release_capacity_write(session_id=session_id, lock=lock, capacity_doc=capacity_doc, now=now)
                if cap is not None:
                    transaction.set(self._capacity_ref(lock["carpark_id"]), cap)
            if decision.session_patch:
                transaction.set(session_ref, decision.session_patch, merge=True)
            return decision.result

        return await self._run_transaction(body)

    async def confirm_parked(
        self,
        session_id: str,
        completed_at_timestamp: int,
        *,
        lock_revision: int | None = None,
        contract_version: int | None = None,
        destination_epoch: int | None = None,
    ) -> None:
        """Driver-confirmed parking (outbox). Terminal + ratable; atomically validates session,
        lock revision / commit time and ledger. Missing session -> 404 (never recreated)."""
        now = datetime.now(UTC)
        completed_at = datetime.fromtimestamp(completed_at_timestamp, UTC)
        lock_ref = self._client.collection("virtual_locks").document(session_id)
        session_ref = self._client.collection("active_sessions").document(session_id)

        async def body(transaction: Any) -> None:
            session_snapshot = await session_ref.get(transaction=transaction)
            lock_snapshot = await lock_ref.get(transaction=transaction)
            current = session_snapshot.to_dict() if session_snapshot.exists else None
            lock = lock_snapshot.to_dict() if lock_snapshot.exists else None
            capacity_doc = None
            if lock is not None and lock.get("carpark_id"):
                cap_snapshot = await self._capacity_ref(lock["carpark_id"]).get(transaction=transaction)
                capacity_doc = cap_snapshot.to_dict() if cap_snapshot.exists else None
            floor = _end_of_hkt_day((current or {}).get("completed_at") or completed_at)
            delete, patch = lock_policy.decide_parked(
                session=current,
                lock=lock,
                completed_at=completed_at,
                lock_revision=lock_revision,
                contract_version=contract_version,
                destination_epoch=destination_epoch,
                now=now,
                retention_hours=self._settings.session_retention_hours,
                retention_floor=floor,
                clock_skew=timedelta(seconds=self._settings.client_clock_skew_seconds),
            )
            if delete:
                transaction.delete(lock_ref)
                cap = lock_policy.release_capacity_write(session_id=session_id, lock=lock, capacity_doc=capacity_doc, now=now)
                if cap is not None:
                    transaction.set(self._capacity_ref(lock["carpark_id"]), cap)
            transaction.set(session_ref, patch, merge=True)

        await self._run_transaction(body)

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
        await self.touch_session(session_id, create=False)
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
