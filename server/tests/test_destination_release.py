"""Contract v2 session/lock rules against an in-memory Firestore stand-in.

These tests exercise FirestoreRepository's real transaction bodies with a sequential fake
transaction. They do NOT prove cross-session anti-oversell (no concurrency / phantom reads).
"""

import asyncio
import types
from datetime import UTC, datetime, timedelta

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.firestore import FirestoreRepository, LockConflictError, SessionMissingError


class _Snap:
    def __init__(self, data, doc_id=None):
        self._data = None if data is None else dict(data)
        self.id = doc_id

    @property
    def exists(self):
        return self._data is not None

    def to_dict(self):
        return None if self._data is None else dict(self._data)


class _Doc:
    def __init__(self, db, coll, doc_id):
        self.db, self.coll, self.id = db, coll, doc_id

    async def get(self, transaction=None):
        return _Snap(self.db.data.setdefault(self.coll, {}).get(self.id))

    def _set(self, data, merge=False):
        table = self.db.data.setdefault(self.coll, {})
        if merge and self.id in table:
            table[self.id] = {**table[self.id], **data}
        else:
            table[self.id] = dict(data)

    async def set(self, data, merge=False):
        self._set(data, merge)

    def _delete(self):
        self.db.data.setdefault(self.coll, {}).pop(self.id, None)

    async def delete(self):
        self._delete()


class _Query:
    def __init__(self, db, coll, filters=()):
        self.db, self.coll, self.filters = db, coll, tuple(filters)

    def where(self, field, op, value):
        return _Query(self.db, self.coll, self.filters + ((field, op, value),))

    def _match(self, doc):
        for field, op, value in self.filters:
            have = doc.get(field)
            if have is None:
                return False
            if op == "==" and have != value:
                return False
            if op == ">=" and not have >= value:
                return False
        return True

    async def stream(self, transaction=None):
        for doc_id, doc in list(self.db.data.get(self.coll, {}).items()):
            if self._match(doc):
                yield _Snap(doc, doc_id)

    def count(self, alias=None):
        query = self

        class _Agg:
            async def get(self, transaction=None):
                n = sum(1 for doc in query.db.data.get(query.coll, {}).values() if query._match(doc))
                return [[types.SimpleNamespace(value=n)]]

        return _Agg()


class _Coll(_Query):
    def document(self, doc_id):
        return _Doc(self.db, self.coll, doc_id)


class FakeDb:
    def __init__(self):
        self.data = {}

    def collection(self, name):
        return _Coll(self, name)


class _Tx:
    def __init__(self):
        self.ops = []

    def set(self, ref, data, merge=False):
        self.ops.append(("set", ref, data, merge))

    def delete(self, ref):
        self.ops.append(("delete", ref, None, None))

    def apply(self):
        for op, ref, data, merge in self.ops:
            if op == "set":
                ref._set(data, merge)
            else:
                ref._delete()


class MemoryRepository(FirestoreRepository):
    def __init__(self, settings):
        self._settings = settings
        self._client = FakeDb()

    async def _run_transaction(self, body):
        tx = _Tx()
        result = await body(tx)  # raising aborts: no writes applied
        tx.apply()
        return result

    def session(self, sid="s1"):
        return self._client.data.get("active_sessions", {}).get(sid)

    def lock(self, sid="s1"):
        return self._client.data.get("virtual_locks", {}).get(sid)


NOW = datetime.now(UTC)


def run(coro):
    return asyncio.run(coro)


@pytest.fixture
def repo():
    return MemoryRepository(Settings(internal_api_key="k", capacity_bootstrap_enabled=True))


@pytest.fixture
def legacy_repo():
    return MemoryRepository(Settings(internal_api_key="k", capacity_bootstrap_enabled=True, legacy_session_create_enabled=True))


DEST_A = {"lat": 22.3, "lng": 114.17, "name": "A"}
DEST_B = {"lat": 22.32, "lng": 114.17, "name": "B"}


def code_of(excinfo):
    return excinfo.value.code


def test_immediate_destination_release_keeps_session_active_and_next_commit_uses_new_epoch(repo):
    run(repo.touch_session("s1", DEST_A, destination_epoch=1, session_started_at=NOW, require_open=True))
    first = run(repo.commit_lock("s1", "CP_A", 10, destination_epoch=1, idempotency_key="k-0001"))
    assert first["lock_revision"] == 1 and repo.lock()["carpark_id"] == "CP_A"
    result = run(repo.release_lock("s1", "CP_A", reason="DESTINATION_CHANGED", lock_revision=1, new_destination_epoch=2))
    assert result["released"] is True and result["status"] == "ACTIVE"
    assert repo.lock() is None
    session = repo.session()
    assert session["status"] == "ACTIVE" and session["destination_epoch"] == 2 and session["lock_revision"] == 1
    assert "terminal" not in session
    second = run(repo.commit_lock("s1", "CP_B", 10, destination_epoch=2, idempotency_key="k-0002"))
    assert second["lock_revision"] == 2 and second["lock_id"] == "s1:2"


def test_late_old_commit_after_destination_change_cannot_revive_lock(repo):
    run(repo.touch_session("s1", DEST_A, destination_epoch=1, session_started_at=NOW))
    run(repo.release_lock("s1", "CP_A", reason="DESTINATION_CHANGED", new_destination_epoch=2))
    with pytest.raises(LockConflictError) as err:
        run(repo.commit_lock("s1", "CP_A", 10, destination_epoch=1, idempotency_key="k-late-1"))
    assert code_of(err) == "STALE_EPOCH"
    assert repo.lock() is None


def test_a_b_a_rejects_old_a_and_retried_release_keeps_newer_lock(repo):
    run(repo.touch_session("s1", DEST_A, destination_epoch=1, session_started_at=NOW))
    run(repo.commit_lock("s1", "CP_A", 10, destination_epoch=1, idempotency_key="k-a1-xx"))
    run(repo.release_lock("s1", "CP_A", reason="DESTINATION_CHANGED", lock_revision=1, new_destination_epoch=2))
    run(repo.release_lock("s1", "CP_A", reason="DESTINATION_CHANGED", new_destination_epoch=3))
    with pytest.raises(LockConflictError) as err:
        run(repo.commit_lock("s1", "CP_A", 10, destination_epoch=1, idempotency_key="k-a1-xx"))
    assert code_of(err) == "STALE_EPOCH"
    fresh = run(repo.commit_lock("s1", "CP_A", 10, destination_epoch=3, idempotency_key="k-a3-xx"))
    assert fresh["lock_revision"] == 2
    # A delayed retry of the epoch-3 release (sent before the new commit) must not delete lock 2.
    retry = run(repo.release_lock("s1", "CP_A", reason="DESTINATION_CHANGED", lock_revision=1, new_destination_epoch=3))
    assert retry["released"] is False and retry["result"] == "LOCK_NEWER_KEPT"
    assert repo.lock()["lock_revision"] == 2
    with pytest.raises(LockConflictError) as err:
        run(repo.release_lock("s1", "CP_A", reason="DESTINATION_CHANGED", new_destination_epoch=2))
    assert code_of(err) == "STALE_EPOCH"


def test_destination_release_retry_is_idempotent(repo):
    run(repo.touch_session("s1", DEST_A, destination_epoch=1, session_started_at=NOW))
    run(repo.commit_lock("s1", "CP_A", 10, destination_epoch=1, idempotency_key="k-0001"))
    one = run(repo.release_lock("s1", "CP_A", reason="DESTINATION_CHANGED", lock_revision=1, new_destination_epoch=2))
    two = run(repo.release_lock("s1", "CP_A", reason="DESTINATION_CHANGED", lock_revision=1, new_destination_epoch=2))
    assert one["released"] is True
    assert two["released"] is False and two["result"] == "NO_LOCK"
    assert repo.session()["destination_epoch"] == 2


def test_terminal_release_with_stale_revision_is_rejected_and_lock_kept(repo):
    run(repo.touch_session("s1", DEST_A, destination_epoch=1, session_started_at=NOW))
    run(repo.commit_lock("s1", "CP_A", 10, destination_epoch=1, idempotency_key="k-0001"))
    run(repo.commit_lock("s1", "CP_B", 10, destination_epoch=1, idempotency_key="k-0002"))
    with pytest.raises(LockConflictError) as err:
        run(repo.release_lock("s1", "CP_A", reason="USER_ABORTED", lock_revision=1))
    assert code_of(err) == "STALE_REVISION"
    assert repo.lock()["lock_revision"] == 2


@pytest.mark.parametrize("reason", ["USER_ABORTED", "PARKED_CONFIRMED"])
def test_terminal_tombstone_is_never_reopened(repo, reason):
    run(repo.touch_session("s1", DEST_A, destination_epoch=1, session_started_at=NOW))
    run(repo.commit_lock("s1", "CP_A", 10, destination_epoch=1, idempotency_key="k-0001"))
    closed = run(repo.release_lock("s1", "CP_A", reason=reason, lock_revision=1))
    assert closed["released"] is True and repo.lock() is None
    session = repo.session()
    assert session["status"] == reason and session["terminal"] is True
    assert session["expire_at"] >= datetime.now(UTC) + timedelta(hours=23)
    for call in (
        lambda: repo.touch_session("s1", DEST_B, destination_epoch=2, session_started_at=NOW, require_open=True),
        lambda: repo.commit_lock("s1", "CP_A", 10, destination_epoch=1, idempotency_key="k-late-9"),
        lambda: repo.release_lock("s1", "CP_A", reason="DESTINATION_CHANGED", new_destination_epoch=2),
    ):
        with pytest.raises(LockConflictError) as err:
            run(call())
        assert code_of(err) == "SESSION_CLOSED"
    run(repo.touch_session("s1"))  # e.g. crowd report: no-op, does not reopen
    assert repo.session()["status"] == reason
    again = run(repo.release_lock("s1", "CP_A", reason=reason, lock_revision=1))
    assert again["result"] == "ALREADY_CLOSED"
    assert repo.lock() is None


def test_missing_session_commit_fails_closed_and_creates_nothing(repo):
    with pytest.raises(SessionMissingError):
        run(repo.commit_lock("ghost", "CP_A", 10, destination_epoch=0, idempotency_key="k-0001"))
    with pytest.raises(SessionMissingError):
        run(repo.commit_lock("ghost", "CP_A", 10))
    assert repo.session("ghost") is None and repo.lock("ghost") is None


def test_idle_expired_session_is_kept_with_epoch_and_commit_fails_until_refreshed(repo):
    run(repo.touch_session("s1", DEST_A, destination_epoch=4, session_started_at=NOW))
    repo._client.data["active_sessions"]["s1"]["idle_expire_at"] = datetime.now(UTC) - timedelta(seconds=1)
    with pytest.raises(LockConflictError) as err:
        run(repo.commit_lock("s1", "CP_A", 10, destination_epoch=4, idempotency_key="k-0001"))
    assert code_of(err) == "SESSION_EXPIRED"
    run(repo.touch_session("s1", DEST_A, destination_epoch=4, session_started_at=NOW, require_open=True))
    assert repo.session()["destination_epoch"] == 4 and repo.session()["status"] == "ACTIVE"
    assert run(repo.commit_lock("s1", "CP_A", 10, destination_epoch=4, idempotency_key="k-0001"))["lock_revision"] == 1


def test_touch_with_older_epoch_is_rejected(repo):
    run(repo.touch_session("s1", DEST_B, destination_epoch=3, session_started_at=NOW))
    with pytest.raises(LockConflictError) as err:
        run(repo.touch_session("s1", DEST_A, destination_epoch=2, session_started_at=NOW, require_open=True))
    assert code_of(err) == "STALE_EPOCH"
    assert repo.session()["destination"] == DEST_B


def test_idempotent_commit_replays_without_new_revision(repo):
    run(repo.touch_session("s1", DEST_A, destination_epoch=1, session_started_at=NOW))
    one = run(repo.commit_lock("s1", "CP_A", 10, destination_epoch=1, idempotency_key="k-same-1"))
    two = run(repo.commit_lock("s1", "CP_A", 10, destination_epoch=1, idempotency_key="k-same-1"))
    assert two["replayed"] is True and two["lock_revision"] == one["lock_revision"]
    assert repo.session()["lock_revision"] == 1
    with pytest.raises(LockConflictError) as err:
        run(repo.commit_lock("s1", "CP_B", 10, destination_epoch=1, idempotency_key="k-same-1"))
    assert code_of(err) == "IDEMPOTENCY_KEY_REUSED"


def test_legacy_session_keeps_legacy_behaviour(legacy_repo):
    repo = legacy_repo
    run(repo.touch_session("s1", DEST_A))
    run(repo.commit_lock("s1", "CP_A", 10))
    assert repo.lock()["carpark_id"] == "CP_A"
    run(repo.touch_session("s1", DEST_B))  # legacy: destination change via recommend drops the lock
    assert repo.lock() is None
    run(repo.commit_lock("s1", "CP_B", 10))
    out = run(repo.release_lock("s1", "CP_B", reason="PARKED_CONFIRMED"))
    assert out["status"] == "PARKED_CONFIRMED" and repo.lock() is None


def test_versioned_session_rejects_unversioned_commit_and_release(repo):
    run(repo.touch_session("s1", DEST_A, destination_epoch=1, session_started_at=NOW))
    with pytest.raises(LockConflictError) as err:
        run(repo.commit_lock("s1", "CP_A", 10))
    assert code_of(err) == "EPOCH_REQUIRED"
    run(repo.commit_lock("s1", "CP_A", 10, destination_epoch=1, idempotency_key="k-0001"))
    with pytest.raises(LockConflictError) as err:
        run(repo.release_lock("s1", "CP_A", reason="PARKED_CONFIRMED"))
    assert code_of(err) == "REVISION_REQUIRED"
    assert repo.lock() is not None
    run(repo.touch_session("s1", DEST_B))  # unversioned recommend on a v2 session must not drop the lock
    assert repo.lock() is not None


def test_lock_full_still_enforced_with_versioned_commit(repo):
    from app.firestore import LockFullError

    run(repo.touch_session("s1", DEST_A, destination_epoch=1, session_started_at=NOW))
    run(repo.touch_session("s2", DEST_A, destination_epoch=1, session_started_at=NOW))
    run(repo.commit_lock("s1", "CP_A", 1, destination_epoch=1, idempotency_key="k-0001"))
    with pytest.raises(LockFullError):
        run(repo.commit_lock("s2", "CP_A", 1, destination_epoch=1, idempotency_key="k-0002"))
    assert repo.lock("s2") is None


def test_retention_must_cover_idle_and_lock_windows():
    with pytest.raises(ValueError):
        Settings(internal_api_key="k", session_retention_hours=1)
    assert Settings(internal_api_key="k").session_retention_hours >= 3


class _ApiRepo:
    def __init__(self, error=None):
        self.error = error

    async def close(self):
        return None

    async def touch_session(self, *args, **kwargs):
        return None

    async def commit_lock(self, session_id, carpark_id, vacancies, **kwargs):
        self.commit_kwargs = kwargs
        if self.error:
            raise self.error
        return {"lock_id": f"{session_id}:7", "lock_revision": 7, "destination_epoch": kwargs["destination_epoch"], "replayed": False}

    async def confirm_parked(self, session_id, ts, **kwargs):
        self.parked_kwargs = kwargs
        if self.error:
            raise self.error

    async def release_lock(self, session_id, target, **kwargs):
        self.release_kwargs = kwargs
        if self.error:
            raise self.error
        return {"status": "ACTIVE", "released": True, "result": "RELEASED", "destination_epoch": 2, "lock_revision": 7, "contract_version": 2}


class _Snaps:
    async def load_regions(self):
        return []


def _client(repo):
    app = __import__("main").create_app(
        Settings(internal_api_key="k", gcs_state_bucket="x"), repository=repo, snapshots=_Snaps()
    )
    return TestClient(app)


H = {"X-Internal-API-Key": "k"}


def test_api_typed_destination_changed_release_contract():
    repo = _ApiRepo()
    with _client(repo) as client:
        bad = client.post("/v1/session/release", headers=H, json={"session_id": "s1", "target_carpark_id": "CP", "reason": "DESTINATION_CHANGED"})
        assert bad.status_code == 422
        bad2 = client.post("/v1/session/release", headers=H, json={"session_id": "s1", "target_carpark_id": "CP", "reason": "USER_ABORTED", "new_destination_epoch": 2})
        assert bad2.status_code == 422
        ok = client.post("/v1/session/release", headers=H, json={"session_id": "s1", "target_carpark_id": "CP", "reason": "DESTINATION_CHANGED", "lock_revision": 7, "new_destination_epoch": 2})
        assert ok.status_code == 200 and ok.json()["status"] == "ACTIVE" and ok.json()["released"] is True
        assert repo.release_kwargs == {"reason": "DESTINATION_CHANGED", "lock_revision": 7, "new_destination_epoch": 2}
        commit = client.post("/v1/session/commit", headers=H, json={"session_id": "s1", "target_carpark_id": "CP", "destination_epoch": 2, "idempotency_key": "s1-2-CP-1"})
        assert commit.status_code == 200
        assert commit.json()["lock_revision"] == 7 and commit.json()["contract_version"] == 2
        unpaired = client.post("/v1/session/commit", headers=H, json={"session_id": "s1", "target_carpark_id": "CP", "idempotency_key": "s1-2-CP-1"})
        assert unpaired.status_code == 422


def test_api_maps_conflicts_to_explicit_status():
    repo = _ApiRepo(LockConflictError("STALE_EPOCH"))
    with _client(repo) as client:
        res = client.post("/v1/session/commit", headers=H, json={"session_id": "s1", "target_carpark_id": "CP", "destination_epoch": 1})
        assert res.status_code == 409 and res.json()["status"] == "STALE_EPOCH"
    repo = _ApiRepo(SessionMissingError())
    with _client(repo) as client:
        res = client.post("/v1/session/release", headers=H, json={"session_id": "s1", "target_carpark_id": "CP", "reason": "USER_ABORTED", "lock_revision": 1})
        assert res.status_code == 404 and res.json()["status"] == "SESSION_NOT_FOUND"
