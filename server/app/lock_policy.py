"""Pure session/lock decision rules (no I/O) for commit, release and session touch.

Contract v2 (destination-change release):
* ``active_sessions/{id}`` keeps a monotonic ``destination_epoch`` and ``lock_revision``.
  The session document is never deleted by request handling; its TTL field ``expire_at`` is a
  retention horizon (>= ``session_retention_hours``) while ``idle_expire_at`` is the logical
  activity expiry. Legacy documents without ``idle_expire_at`` fall back to ``expire_at``.
* ``DESTINATION_CHANGED`` is non-terminal: it advances the epoch and deletes only the lock that
  belongs to an older epoch (and matches ``lock_revision`` when given). Session stays ACTIVE.
* ``PARKED_CONFIRMED`` / ``USER_ABORTED`` are terminal tombstones: nothing reopens them.
* A session that ever sent a versioned request (``contract_version`` >= 2) rejects unversioned
  commits/releases that would touch its lock (no silent legacy bypass).
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timedelta
from typing import Any

CONTRACT_VERSION = 2
TERMINAL_STATUSES = frozenset({"PARKED_CONFIRMED", "USER_ABORTED"})
TERMINAL_RELEASE_REASONS = frozenset({"PARKED_CONFIRMED", "USER_ABORTED"})


class LockConflictError(RuntimeError):
    """Request is well-formed but conflicts with session/lock state (HTTP 409 unless noted)."""

    http_status = 409

    def __init__(self, code: str, message: str = "") -> None:
        super().__init__(message or code)
        self.code = code


class SessionMissingError(LockConflictError):
    http_status = 404

    def __init__(self) -> None:
        super().__init__("SESSION_NOT_FOUND", "Unknown session; commits fail closed")


@dataclass(frozen=True)
class CapacityReading:
    """Snapshot capacity for one car park.

    ``value`` is the last-known vacancy count (None = never known -> commit rejected);
    ``stale`` marks a value older than the freshness threshold (still used, never unlimited).
    """

    value: int | None
    stale: bool = False

    @staticmethod
    def of(raw: "int | CapacityReading | None") -> "CapacityReading":
        if isinstance(raw, CapacityReading):
            return raw
        if raw is None or int(raw) < 0:
            return CapacityReading(None, stale=True)
        return CapacityReading(int(raw))


def valid_leases(capacity_doc: dict[str, Any] | None, now: datetime) -> dict[str, dict[str, Any]]:
    """Leases whose ``expire_at`` is still in the future (expired ones are pruned on write)."""
    leases = (capacity_doc or {}).get("leases") or {}
    return {sid: dict(lease) for sid, lease in leases.items() if lease.get("expire_at") and lease["expire_at"] > now}


def capacity_doc_with(leases: dict[str, dict[str, Any]], now: datetime, *, bootstrapped_from: str | None = None, previous: dict[str, Any] | None = None) -> dict[str, Any]:
    doc = {
        "schema": 1,
        "bootstrapped": True,
        "bootstrapped_at": (previous or {}).get("bootstrapped_at") or now,
        "bootstrapped_from": (previous or {}).get("bootstrapped_from") or bootstrapped_from or "EMPTY",
        "leases": leases,
        "lease_count": len(leases),
        "updated_at": now,
    }
    return doc


def bootstrap_leases(legacy_locks: list[tuple[str, dict[str, Any]]], *, lock_timeout: timedelta, now: datetime) -> dict[str, dict[str, Any]]:
    """Leases from pre-capacity lock documents (only once legacy writers are drained)."""
    leases: dict[str, dict[str, Any]] = {}
    for session_id, lock in legacy_locks:
        created = lock.get("created_at")
        if created is None:
            continue
        expire = lock.get("expire_at") or created + lock_timeout
        if expire > now:
            leases[session_id] = {"lock_revision": lock.get("lock_revision"), "expire_at": expire, "committed_at": lock.get("committed_at", created)}
    return leases


def session_contract_version(session: dict[str, Any] | None) -> int:
    if not session:
        return 1
    return int(session.get("contract_version", 1))


def idle_expiry(session: dict[str, Any]) -> datetime | None:
    return session.get("idle_expire_at", session.get("expire_at"))


def is_terminal(session: dict[str, Any] | None) -> bool:
    return bool(session) and session.get("status") in TERMINAL_STATUSES


def is_idle_expired(session: dict[str, Any], now: datetime) -> bool:
    expiry = idle_expiry(session)
    return expiry is not None and expiry <= now


def retention_until(now: datetime, retention_hours: int, *, at_least: datetime | None = None) -> datetime:
    horizon = now + timedelta(hours=retention_hours)
    if at_least is not None and at_least > horizon:
        return at_least
    return horizon


def _lock_epoch(lock: dict[str, Any]) -> int:
    return int(lock.get("destination_epoch", 0))


def _lock_revision(lock: dict[str, Any]) -> int | None:
    value = lock.get("lock_revision")
    return None if value is None else int(value)


@dataclass
class CommitDecision:
    replay: bool
    lock: dict[str, Any]
    session_patch: dict[str, Any] = field(default_factory=dict)
    capacity_writes: dict[str, dict[str, Any]] = field(default_factory=dict)


def decide_commit(
    *,
    session_id: str,
    session: dict[str, Any] | None,
    existing_lock: dict[str, Any] | None,
    carpark_id: str,
    capacity: "int | CapacityReading | None",
    capacity_doc: dict[str, Any] | None,
    destination_epoch: int | None,
    idempotency_key: str | None,
    now: datetime,
    lock_timeout: timedelta,
    committed_idle: timedelta,
    retention_hours: int,
    old_capacity_doc: dict[str, Any] | None = None,
    legacy_locks: list[tuple[str, dict[str, Any]]] | None = None,
    max_leases: int = 3000,
) -> CommitDecision:
    if session is None:
        raise SessionMissingError()
    if is_terminal(session):
        raise LockConflictError("SESSION_CLOSED", "Session already ended")
    if is_idle_expired(session, now):
        raise LockConflictError("SESSION_EXPIRED", "Session idle-expired; refresh with recommend first")
    versioned_session = session_contract_version(session) >= CONTRACT_VERSION
    if versioned_session and destination_epoch is None:
        raise LockConflictError("EPOCH_REQUIRED", "Versioned session requires destination_epoch")
    session_epoch = int(session.get("destination_epoch", 0))
    if destination_epoch is not None and destination_epoch < session_epoch:
        raise LockConflictError("STALE_EPOCH", "Commit belongs to an older destination")
    # A newer epoch (client re-confirmed a destination before any recommend) advances the session.
    commit_epoch = session_epoch if destination_epoch is None else destination_epoch

    cutoff = now - lock_timeout
    live_lock = existing_lock or None
    if live_lock is not None:
        created = live_lock.get("created_at")
        if created is None or created < cutoff:
            live_lock = None

    if (
        live_lock is not None
        and idempotency_key is not None
        and live_lock.get("idempotency_key") == idempotency_key
    ):
        if live_lock.get("carpark_id") != carpark_id or _lock_epoch(live_lock) != commit_epoch:
            raise LockConflictError("IDEMPOTENCY_KEY_REUSED", "Key reused with a different commit")
        return CommitDecision(replay=True, lock=dict(live_lock))

    same_carpark_live = live_lock is not None and live_lock.get("carpark_id") == carpark_id
    reading = CapacityReading.of(capacity)
    if reading.value is None:
        raise LockConflictError("CAPACITY_UNKNOWN", "Car park capacity was never known; commit rejected")
    if capacity_doc is None or not capacity_doc.get("bootstrapped"):
        if legacy_locks is None:
            raise LockConflictError("CAPACITY_NOT_READY", "Capacity ledger not bootstrapped; fail closed")
        target_leases = bootstrap_leases(legacy_locks, lock_timeout=lock_timeout, now=now)
        bootstrapped_from = "LEGACY_LOCKS"
    else:
        target_leases = valid_leases(capacity_doc, now)
        bootstrapped_from = None
    others = {sid: lease for sid, lease in target_leases.items() if sid != session_id}
    if len(others) >= max_leases:
        raise LockConflictError("CAPACITY_LEDGER_FULL", "Per-car-park lease map at its bound")
    if reading.value - len(others) <= 0:
        from .firestore_errors import LockFullError

        raise LockFullError("No uncommitted vacancies remain")

    revision = int(session.get("lock_revision", 0)) + 1
    created_at = live_lock["created_at"] if same_carpark_live else now
    lock = {
        "carpark_id": carpark_id,
        "created_at": created_at,
        "committed_at": now,
        "expire_at": created_at + lock_timeout,
        "lock_revision": revision,
        "lock_id": f"{session_id}:{revision}",
        "destination_epoch": commit_epoch,
        "idempotency_key": idempotency_key,
    }
    patch: dict[str, Any] = {
        "status": "COMMITTED",
        "ratable": False,
        "target_carpark_id": carpark_id,
        "lock_revision": revision,
        "current_lock_id": lock["lock_id"],
        "last_seen_at": now,
        "idle_expire_at": now + committed_idle,
        "expire_at": retention_until(now, retention_hours, at_least=now + committed_idle),
        "updated_at": now,
    }
    if destination_epoch is not None:
        patch["contract_version"] = CONTRACT_VERSION
        patch["destination_epoch"] = commit_epoch
    others[session_id] = {"lock_revision": revision, "expire_at": lock["expire_at"], "committed_at": now}
    capacity_writes = {carpark_id: capacity_doc_with(others, now, bootstrapped_from=bootstrapped_from, previous=capacity_doc)}
    old_carpark = None if existing_lock is None else existing_lock.get("carpark_id")
    if old_carpark and old_carpark != carpark_id and old_capacity_doc is not None:
        old_leases = {sid: lease for sid, lease in valid_leases(old_capacity_doc, now).items() if sid != session_id}
        capacity_writes[old_carpark] = capacity_doc_with(old_leases, now, previous=old_capacity_doc)
    return CommitDecision(replay=False, lock=lock, session_patch=patch, capacity_writes=capacity_writes)


@dataclass
class ReleaseDecision:
    delete_lock: bool
    session_patch: dict[str, Any]
    result: dict[str, Any]


def decide_release(
    *,
    session_id: str,
    session: dict[str, Any] | None,
    lock: dict[str, Any] | None,
    reason: str,
    target_carpark_id: str,
    lock_revision: int | None,
    new_destination_epoch: int | None,
    now: datetime,
    active_idle: timedelta,
    retention_hours: int,
    parked_retention_floor: datetime | None = None,
) -> ReleaseDecision:
    if session is None:
        raise SessionMissingError()
    terminal_reason = reason in TERMINAL_RELEASE_REASONS
    versioned_session = session_contract_version(session) >= CONTRACT_VERSION
    session_epoch = int(session.get("destination_epoch", 0))

    if is_terminal(session):
        if terminal_reason:
            # Idempotent repeat of a terminal release: never reopen, never touch a lock.
            return ReleaseDecision(
                delete_lock=False,
                session_patch={},
                result=_release_result(session_id, session["status"], session, released=False, note="ALREADY_CLOSED"),
            )
        raise LockConflictError("SESSION_CLOSED", "Destination change on an ended session")

    if lock is not None and versioned_session and lock_revision is None and reason != "DESTINATION_CHANGED":
        raise LockConflictError("REVISION_REQUIRED", "Versioned lock requires lock_revision")

    current_revision = None if lock is None else _lock_revision(lock)
    if lock is not None and lock_revision is not None and current_revision is not None and current_revision != lock_revision:
        if reason != "DESTINATION_CHANGED" or _lock_epoch(lock) < (new_destination_epoch or 0):
            raise LockConflictError("STALE_REVISION", "Lock revision does not match")

    if reason == "DESTINATION_CHANGED":
        if new_destination_epoch is None:
            raise LockConflictError("EPOCH_REQUIRED", "DESTINATION_CHANGED requires new_destination_epoch")
        if new_destination_epoch < session_epoch:
            raise LockConflictError("STALE_EPOCH", "Older destination change")
        delete = lock is not None and _lock_epoch(lock) < new_destination_epoch and (
            lock_revision is None or current_revision is None or current_revision == lock_revision
        )
        status = "ACTIVE" if (delete or lock is None or _lock_epoch(lock) < new_destination_epoch) else session.get("status", "ACTIVE")
        patch = {
            "status": status,
            "destination_epoch": new_destination_epoch,
            "contract_version": CONTRACT_VERSION,
            "last_seen_at": now,
            "idle_expire_at": now + active_idle,
            "expire_at": retention_until(now, retention_hours),
            "updated_at": now,
        }
        if delete:
            patch["current_lock_id"] = None
        note = "RELEASED" if delete else ("NO_LOCK" if lock is None else "LOCK_NEWER_KEPT")
        merged = {**session, **patch}
        return ReleaseDecision(delete_lock=delete, session_patch=patch, result=_release_result(session_id, status, merged, released=delete, note=note))

    # Terminal: PARKED_CONFIRMED / USER_ABORTED.
    floor = parked_retention_floor if reason == "PARKED_CONFIRMED" else None
    patch = {
        "status": reason,
        "terminal": True,
        "ratable": False if reason == "USER_ABORTED" else session.get("ratable", False),
        "target_carpark_id": target_carpark_id,
        "current_lock_id": None,
        "completed_at": session.get("completed_at") or now,
        "last_seen_at": now,
        "idle_expire_at": floor or now,
        "expire_at": retention_until(now, retention_hours, at_least=floor),
        "updated_at": now,
    }
    merged = {**session, **patch}
    return ReleaseDecision(
        delete_lock=lock is not None,
        session_patch=patch,
        result=_release_result(session_id, reason, merged, released=lock is not None, note="CLOSED"),
    )


def release_capacity_write(
    *, session_id: str, lock: dict[str, Any] | None, capacity_doc: dict[str, Any] | None, now: datetime
) -> dict[str, Any] | None:
    """Capacity doc after removing this session's lease, only if it is the same lock revision."""
    if lock is None or capacity_doc is None:
        return None
    leases = valid_leases(capacity_doc, now)
    lease = leases.get(session_id)
    if lease is not None and lease.get("lock_revision") not in (None, lock.get("lock_revision")):
        return capacity_doc_with(leases, now, previous=capacity_doc)  # newer lease kept; prune only
    leases.pop(session_id, None)
    return capacity_doc_with(leases, now, previous=capacity_doc)


def _release_result(session_id: str, status: str, session: dict[str, Any], *, released: bool, note: str) -> dict[str, Any]:
    return {
        "status": status,
        "session_id": session_id,
        "released": released,
        "result": note,
        "destination_epoch": int(session.get("destination_epoch", 0)),
        "lock_revision": int(session.get("lock_revision", 0)),
        "contract_version": session_contract_version(session),
    }


def decide_touch(
    *,
    session: dict[str, Any] | None,
    destination: dict[str, Any] | None,
    destination_epoch: int | None,
    require_open: bool,
    now: datetime,
    active_idle: timedelta,
    committed_idle: timedelta,
    retention_hours: int,
    session_started_at: datetime | None = None,
    create: bool = True,
    create_window: timedelta = timedelta(hours=12),
    clock_skew: timedelta = timedelta(minutes=5),
    legacy_create_enabled: bool = False,
) -> tuple[dict[str, Any] | None, bool]:
    """Return (session_patch or None for no write, delete_legacy_lock).

    Creation proof (no post-TTL resurrection): a missing session is only created when the client
    sends ``session_started_at`` with ``now - started <= create_window`` (and not in the future
    beyond ``clock_skew``). Every document is retained until at least ``created + retention`` and
    ``retention > create_window + clock_skew`` (Settings validator), so if an earlier document for
    the same id ever existed it is still present whenever creation is allowed; TTL can only have
    removed documents whose id is now outside the window, which therefore can never be recreated.
    """
    if session is None:
        if not create:
            return None, False
        if session_started_at is None:
            if not legacy_create_enabled:
                raise LockConflictError("SESSION_START_REQUIRED", "New sessions must send session_started_at")
        elif session_started_at > now + clock_skew:
            raise LockConflictError("SESSION_START_INVALID", "session_started_at is in the future")
        elif now - session_started_at > create_window:
            raise LockConflictError("SESSION_START_EXPIRED", "Session id too old to create; start a new session")
    elif (
        session_started_at is not None
        and session.get("session_started_at") is not None
        and abs(session["session_started_at"] - session_started_at) > timedelta(seconds=1)
    ):
        raise LockConflictError("SESSION_ID_REUSED", "Session id already used by another trip")
    current = session or {}
    if is_terminal(current):
        if require_open:
            raise LockConflictError("SESSION_CLOSED", "Session already ended")
        return None, False
    session_epoch = int(current.get("destination_epoch", 0))
    if destination_epoch is not None and destination_epoch < session_epoch:
        raise LockConflictError("STALE_EPOCH", "Request for an older destination")
    status = current.get("status", "ACTIVE")
    if is_idle_expired(current, now) if current else False:
        # Idle expiry is not a tombstone; the same trip may resume. Lock windows expire by created_at.
        status = "ACTIVE"
    versioned = destination_epoch is not None or session_contract_version(current) >= CONTRACT_VERSION
    delete_legacy_lock = (
        not versioned
        and destination is not None
        and current.get("destination") not in (None, destination)
    )
    idle = committed_idle if status == "COMMITTED" else active_idle
    patch: dict[str, Any] = {
        "status": status,
        "last_seen_at": now,
        "idle_expire_at": now + idle,
        "expire_at": retention_until(now, retention_hours, at_least=now + idle),
        "updated_at": now,
    }
    if "ratable" not in current:
        patch["ratable"] = False
    if destination is not None:
        patch["destination"] = destination
    if destination_epoch is not None:
        patch["destination_epoch"] = max(session_epoch, destination_epoch)
        patch["contract_version"] = CONTRACT_VERSION
    if "lock_revision" not in current:
        patch["lock_revision"] = 0
    if session is None:
        patch["created_at"] = now
        if session_started_at is not None:
            patch["session_started_at"] = session_started_at
        else:
            patch["legacy_created"] = True
    return patch, delete_legacy_lock


def decide_parked(
    *,
    session: dict[str, Any] | None,
    lock: dict[str, Any] | None,
    completed_at: datetime,
    lock_revision: int | None,
    now: datetime,
    retention_hours: int,
    retention_floor: datetime,
    clock_skew: timedelta,
    contract_version: int | None = None,
    destination_epoch: int | None = None,
) -> tuple[bool, dict[str, Any]]:
    """Driver-confirmed parking (outbox). Returns (delete_lock, session_patch).

    v2 event (``contract_version`` 2, identity captured when the event was created): requires
    ``destination_epoch`` >= session epoch and an EXACT ``lock_revision`` match for any existing lock
    (``None`` = "held no lock" -> any lock is newer -> STALE_REVISION). No time fallback.
    Legacy (unversioned) event: may never touch a v2 session (REVISION_REQUIRED); on a legacy v1
    session the old committed_at <= timestamp + skew heuristic is kept. That heuristic is NOT a
    guarantee (clock skew / late replay) and is isolated to sessions created by legacy clients.
    """
    if session is None:
        raise SessionMissingError()
    status = session.get("status")
    if status == "USER_ABORTED":
        raise LockConflictError("SESSION_CLOSED", "Aborted session cannot be confirmed parked")
    delete = False
    versioned_event = contract_version is not None and contract_version >= CONTRACT_VERSION
    if status != "PARKED_CONFIRMED":
        if versioned_event:
            if destination_epoch is None:
                raise LockConflictError("EPOCH_REQUIRED", "v2 parked event requires destination_epoch")
            if destination_epoch < int(session.get("destination_epoch", 0)):
                raise LockConflictError("STALE_EPOCH", "Parked event belongs to an older destination")
            if lock is not None:
                if lock_revision is None or _lock_revision(lock) != lock_revision:
                    raise LockConflictError("STALE_REVISION", "Parked event does not own the current lock")
                delete = True
        else:
            if session_contract_version(session) >= CONTRACT_VERSION:
                raise LockConflictError("REVISION_REQUIRED", "Legacy parked event cannot modify a v2 session")
            if lock is not None:
                committed = lock.get("committed_at", lock.get("created_at"))
                if committed is not None and committed > completed_at + clock_skew:
                    raise LockConflictError("STALE_PARKED", "Lock is newer than the parked confirmation")
                delete = True
    first_completed = session.get("completed_at") or completed_at
    patch = {
        "status": "PARKED_CONFIRMED",
        "terminal": True,
        "ratable": True,
        "current_lock_id": None,
        "completed_at": first_completed,
        "last_seen_at": now,
        "idle_expire_at": retention_floor,
        "expire_at": retention_until(now, retention_hours, at_least=retention_floor),
        "updated_at": now,
    }
    return delete, patch
