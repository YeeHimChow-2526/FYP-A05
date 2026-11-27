"""Per-car-park capacity ledger, confirm_parked atomicity and session creation proof.

Sequential in-memory fake (see test_destination_release.py): validates decision logic and the
read/write sets of each transaction body. Real concurrency is in test_firestore_emulator.py.
"""

from datetime import UTC, datetime, timedelta

import pytest

from app.config import Settings
from app.firestore import LockConflictError, LockFullError, SessionMissingError
from app.lock_policy import CapacityReading
from test_destination_release import DEST_A, DEST_B, MemoryRepository, run

NOW = datetime.now(UTC)


def mk(**kw):
    return MemoryRepository(Settings(internal_api_key="k", capacity_bootstrap_enabled=True, **kw))


def start(repo, sid, epoch=1):
    run(repo.touch_session(sid, DEST_A, destination_epoch=epoch, session_started_at=NOW))


def commit(repo, sid, cp, cap, epoch=1, key=None):
    return run(repo.commit_lock(sid, cp, cap, destination_epoch=epoch, idempotency_key=key or f"k-{sid}-{cp}-{epoch}"))


def leases(repo, cp):
    return set((repo._client.data.get("carpark_capacity", {}).get(cp) or {}).get("leases", {}))


def code(err):
    return err.value.code


def test_last_spot_then_full_and_release_frees_it():
    repo = mk()
    start(repo, "s1"), start(repo, "s2")
    commit(repo, "s1", "CP", 1)
    with pytest.raises(LockFullError):
        commit(repo, "s2", "CP", 1)
    run(repo.release_lock("s1", "CP", reason="USER_ABORTED", lock_revision=1))
    assert leases(repo, "CP") == set()
    commit(repo, "s2", "CP", 1)
    assert leases(repo, "CP") == {"s2"}


def test_renew_and_idempotent_retry_do_not_double_subtract():
    repo = mk()
    start(repo, "s1")
    commit(repo, "s1", "CP", 1, key="k-renew-1")
    commit(repo, "s1", "CP", 1, key="k-renew-1")  # replay
    commit(repo, "s1", "CP", 1, key="k-renew-2")  # same-session renew
    assert leases(repo, "CP") == {"s1"}
    assert repo._client.data["carpark_capacity"]["CP"]["leases"]["s1"]["lock_revision"] == 2


def test_switch_a_b_a_moves_the_single_lease_atomically():
    repo = mk()
    start(repo, "s1"), start(repo, "s2")
    commit(repo, "s1", "CP_A", 1, key="k-sw-a1")
    commit(repo, "s1", "CP_B", 1, key="k-sw-b1")
    assert leases(repo, "CP_A") == set() and leases(repo, "CP_B") == {"s1"}
    commit(repo, "s2", "CP_A", 1)  # A's spot is free again
    with pytest.raises(LockFullError):
        commit(repo, "s1", "CP_A", 1, key="k-sw-a2")
    assert leases(repo, "CP_B") == {"s1"}, "failed switch leaves old lease untouched"


def test_destination_change_release_frees_lease_and_repeat_is_noop():
    repo = mk()
    start(repo, "s1")
    commit(repo, "s1", "CP", 1)
    run(repo.release_lock("s1", "CP", reason="DESTINATION_CHANGED", lock_revision=1, new_destination_epoch=2))
    again = run(repo.release_lock("s1", "CP", reason="DESTINATION_CHANGED", lock_revision=1, new_destination_epoch=2))
    assert again["result"] == "NO_LOCK" and leases(repo, "CP") == set()


def test_expired_lease_does_not_count_and_is_pruned_on_next_write():
    repo = mk()
    start(repo, "s1"), start(repo, "s2")
    commit(repo, "s1", "CP", 1)
    repo._client.data["carpark_capacity"]["CP"]["leases"]["s1"]["expire_at"] = NOW - timedelta(seconds=1)
    commit(repo, "s2", "CP", 1)
    assert leases(repo, "CP") == {"s2"}


@pytest.mark.parametrize(
    "reading,ok",
    [(CapacityReading(0, stale=True), False), (CapacityReading(1, stale=True), True), (CapacityReading(None, stale=True), None), (-1, None)],
)
def test_stale_snapshot_uses_last_known_minus_leases_and_unknown_is_rejected(reading, ok):
    repo = mk()
    start(repo, "s1")
    if ok is True:
        commit(repo, "s1", "CP", reading)
        return
    if ok is False:
        with pytest.raises(LockFullError):
            commit(repo, "s1", "CP", reading)
        return
    with pytest.raises(LockConflictError) as err:
        commit(repo, "s1", "CP", reading)
    assert code(err) == "CAPACITY_UNKNOWN"


def test_missing_ledger_fails_closed_until_bootstrap_enabled():
    repo = MemoryRepository(Settings(internal_api_key="k"))
    start(repo, "s1")
    with pytest.raises(LockConflictError) as err:
        commit(repo, "s1", "CP", 5)
    assert code(err) == "CAPACITY_NOT_READY" and repo.lock("s1") is None


def test_bootstrap_counts_live_legacy_locks_once():
    repo = mk()
    repo._client.data["virtual_locks"] = {
        "old1": {"carpark_id": "CP", "created_at": NOW - timedelta(minutes=5)},
        "old2": {"carpark_id": "CP", "created_at": NOW - timedelta(hours=2)},  # expired
    }
    start(repo, "s1"), start(repo, "s2")
    commit(repo, "s1", "CP", 2)
    assert leases(repo, "CP") == {"old1", "s1"}
    with pytest.raises(LockFullError):
        commit(repo, "s2", "CP", 2)


def test_ledger_bound_is_enforced():
    repo = mk(capacity_max_leases=1)
    start(repo, "s1"), start(repo, "s2")
    commit(repo, "s1", "CP", 100)
    with pytest.raises(LockConflictError) as err:
        commit(repo, "s2", "CP", 100)
    assert code(err) == "CAPACITY_LEDGER_FULL"


def park(repo, sid, ts, rev, epoch=1):
    return run(repo.confirm_parked(sid, ts, lock_revision=rev, contract_version=2, destination_epoch=epoch))


def test_confirm_parked_validates_and_frees_lease_idempotently():
    repo = mk()
    start(repo, "s1")
    commit(repo, "s1", "CP", 1)
    ts = int(NOW.timestamp())
    park(repo, "s1", ts, 1)
    assert repo.lock("s1") is None and leases(repo, "CP") == set()
    assert repo.session("s1")["status"] == "PARKED_CONFIRMED" and repo.session("s1")["ratable"] is True
    park(repo, "s1", ts, 1)  # duplicate replay: idempotent, no new effects
    assert repo.session("s1")["status"] == "PARKED_CONFIRMED"
    with pytest.raises(LockConflictError):
        commit(repo, "s1", "CP", 1, key="k-after-park")


def test_queued_park_at_a_replayed_after_switch_to_b_never_deletes_b():
    repo = mk()
    start(repo, "s1")
    commit(repo, "s1", "CP_A", 5, key="k-pa-1")  # rev 1 at A; parked event captured (rev 1, epoch 1)
    run(repo.release_lock("s1", "CP_A", reason="DESTINATION_CHANGED", lock_revision=1, new_destination_epoch=2))
    commit(repo, "s1", "CP_B", 5, epoch=2, key="k-pb-2")  # rev 2 at B
    ts = int(NOW.timestamp())
    with pytest.raises(LockConflictError) as err:
        park(repo, "s1", ts, 1, epoch=1)
    assert code(err) == "STALE_EPOCH"
    with pytest.raises(LockConflictError) as err:  # even with a forged current epoch, revision must match
        park(repo, "s1", ts, 1, epoch=2)
    assert code(err) == "STALE_REVISION"
    with pytest.raises(LockConflictError) as err:  # v2 event that held no lock cannot delete B
        park(repo, "s1", ts, None, epoch=2)
    assert code(err) == "STALE_REVISION"
    assert repo.lock("s1")["carpark_id"] == "CP_B" and leases(repo, "CP_B") == {"s1"}
    assert repo.session("s1")["status"] == "COMMITTED"


def test_legacy_event_cannot_touch_v2_session_even_with_old_timestamp_fallback():
    repo = mk()
    start(repo, "s1")
    commit(repo, "s1", "CP", 1)
    for ts in (int((NOW + timedelta(hours=1)).timestamp()), int((NOW - timedelta(hours=1)).timestamp())):
        with pytest.raises(LockConflictError) as err:
            run(repo.confirm_parked("s1", ts))
        assert code(err) == "REVISION_REQUIRED"
    assert repo.lock("s1") is not None and repo.session("s1")["status"] == "COMMITTED"


def test_legacy_v1_session_keeps_isolated_time_heuristic():
    repo = MemoryRepository(Settings(internal_api_key="k", capacity_bootstrap_enabled=True, legacy_session_create_enabled=True))
    run(repo.touch_session("s1", DEST_A))
    run(repo.commit_lock("s1", "CP", 1))
    with pytest.raises(LockConflictError) as err:
        run(repo.confirm_parked("s1", int((NOW - timedelta(hours=1)).timestamp())))
    assert code(err) == "STALE_PARKED"
    run(repo.confirm_parked("s1", int((NOW + timedelta(minutes=1)).timestamp())))
    assert repo.lock("s1") is None


def test_outbox_request_typed_validation():
    from test_destination_release import H, _ApiRepo, _client

    with _client(_ApiRepo()) as client:
        base = {"session_id": "s1", "event_type": "DRIVER_CONFIRMED", "timestamp": 1_700_000_000}
        assert client.post("/v1/sync/outbox", headers=H, json={**base, "lock_revision": 1}).status_code == 422
        assert client.post("/v1/sync/outbox", headers=H, json={**base, "contract_version": 2}).status_code == 422
        assert client.post("/v1/sync/outbox", headers=H, json={**base, "contract_version": 3, "destination_epoch": 1}).status_code == 422


def test_confirm_parked_missing_or_aborted_session():
    repo = mk()
    with pytest.raises(SessionMissingError):
        run(repo.confirm_parked("ghost", int(NOW.timestamp())))
    assert repo.session("ghost") is None
    start(repo, "s1")
    run(repo.release_lock("s1", "CP", reason="USER_ABORTED"))
    with pytest.raises(LockConflictError) as err:
        run(repo.confirm_parked("s1", int(NOW.timestamp())))
    assert code(err) == "SESSION_CLOSED"


def test_session_creation_requires_recent_start_proof():
    repo = mk()
    with pytest.raises(LockConflictError) as err:
        run(repo.touch_session("s1", DEST_A, destination_epoch=1, require_open=True))
    assert code(err) == "SESSION_START_REQUIRED"
    with pytest.raises(LockConflictError) as err:  # e.g. id whose doc was TTL-deleted after 24 h
        run(repo.touch_session("s1", DEST_A, destination_epoch=5, session_started_at=NOW - timedelta(hours=13)))
    assert code(err) == "SESSION_START_EXPIRED" and repo.session("s1") is None
    with pytest.raises(LockConflictError) as err:
        run(repo.touch_session("s1", DEST_A, destination_epoch=1, session_started_at=NOW + timedelta(hours=1)))
    assert code(err) == "SESSION_START_INVALID"
    start(repo, "s1")
    with pytest.raises(LockConflictError) as err:
        run(repo.touch_session("s1", DEST_B, destination_epoch=2, session_started_at=NOW - timedelta(hours=1)))
    assert code(err) == "SESSION_ID_REUSED"
    run(repo.touch_session("s9", create=False))  # crowd report path never creates
    assert repo.session("s9") is None


def test_tombstone_survives_and_late_traffic_cannot_recreate():
    repo = mk()
    start(repo, "s1")
    run(repo.release_lock("s1", "CP", reason="USER_ABORTED"))
    with pytest.raises(LockConflictError) as err:
        run(repo.touch_session("s1", DEST_A, destination_epoch=1, session_started_at=NOW, require_open=True))
    assert code(err) == "SESSION_CLOSED"
    assert repo.session("s1")["expire_at"] > NOW + timedelta(hours=23)


def test_settings_require_retention_beyond_create_window():
    with pytest.raises(ValueError):
        Settings(internal_api_key="k", session_retention_hours=12, session_create_window_hours=12)


def test_current_vacancies_keeps_last_known_when_stale():
    import types
    from app.models import CarparkSnapshot
    from app.recommend import RecommendationService

    region = types.SimpleNamespace(
        source_status="degraded", source_snapshot_ts="2020-01-01T00:00:00+00:00",
        carparks=[CarparkSnapshot(carpark_id="CP", name="CP", type="INDOOR", lat=22.3, lng=114.1, vacancies=4),
                  CarparkSnapshot(carpark_id="NK", name="NK", type="INDOOR", lat=22.3, lng=114.1, vacancies=-1),
                  # typed NO_COUNT never yields a commit capacity
                  CarparkSnapshot(carpark_id="NC", name="NC", type="INDOOR", lat=22.3, lng=114.1, vacancies=-1,
                                  vacancy_status="NO_COUNT"),
                  # review t1235: stale meter count is UNCONFIRMED on the card -> server must refuse too
                  CarparkSnapshot(carpark_id="MT", name="MT", type="METER", lat=22.3, lng=114.1, vacancies=3)],
    )

    class Snaps:
        async def load_regions(self):
            return [region]

    svc = RecommendationService.__new__(RecommendationService)
    svc._snapshots, svc._settings = Snaps(), Settings(internal_api_key="k")
    from datetime import UTC as _UTC, datetime as _dt
    svc._clock = lambda: _dt.now(_UTC)
    assert run(svc.current_vacancies("CP")) == CapacityReading(4, stale=True)
    assert run(svc.current_vacancies("NK")).value is None
    assert run(svc.current_vacancies("ZZ")).value is None
    assert run(svc.current_vacancies("NC")).value is None
    assert run(svc.current_vacancies("MT")).value is None


def test_contention_exhaustion_maps_to_retryable_503():
    from app.firestore import ContentionError
    from test_destination_release import H, _ApiRepo, _client

    with _client(_ApiRepo(ContentionError("busy"))) as client:
        res = client.post("/v1/session/commit", headers=H, json={"session_id": "s1", "target_carpark_id": "CP", "destination_epoch": 1})
    assert res.status_code == 503 and res.json()["status"] == "CONTENTION"


def test_contention_retry_wrapper_gives_up_with_contention_error():
    from app.firestore import ContentionError, FirestoreRepository

    class Tx:
        pass

    class Client:
        def transaction(self, max_attempts):
            raise ValueError("Failed to commit transaction in 10 attempts.")

    repo = FirestoreRepository.__new__(FirestoreRepository)
    repo._settings, repo._client = Settings(internal_api_key="k", firestore_txn_outer_rounds=2), Client()
    with pytest.raises(ContentionError):
        run(repo._run_transaction(lambda tx: None))
