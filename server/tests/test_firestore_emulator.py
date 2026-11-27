"""Real concurrency against the local Firestore emulator (skipped unless FIRESTORE_EMULATOR_HOST).

Runs the production FirestoreRepository (real async transactions, no fakes) with many concurrent
commits. The emulator is NOT production Firestore: it proves the transaction read/write sets
conflict as designed, not production latency, contention limits or cost.
Run: FIRESTORE_EMULATOR_HOST=127.0.0.1:8085 pytest tests/test_firestore_emulator.py
"""

import asyncio
import os
import uuid
from datetime import UTC, datetime, timedelta

import pytest

pytestmark = pytest.mark.skipif(not os.environ.get("FIRESTORE_EMULATOR_HOST"), reason="needs Firestore emulator")

DEST = {"lat": 22.3, "lng": 114.17, "name": "A"}


def _repo(**kw):
    from app.config import Settings
    from app.firestore import FirestoreRepository

    return FirestoreRepository(Settings(internal_api_key="k", project_id="demo-hkp-emulator", capacity_bootstrap_enabled=True, **kw))


async def _start(repo, sid):
    await repo.touch_session(sid, DEST, destination_epoch=1, session_started_at=datetime.now(UTC))


async def _race(repo, carpark, sessions, capacity):
    from app.firestore import LockConflictError, LockFullError

    async def one(sid):
        try:
            await repo.commit_lock(sid, carpark, capacity, destination_epoch=1, idempotency_key=f"k-{sid}")
            return "OK"
        except LockFullError:
            return "FULL"
        except LockConflictError as error:
            return error.code
        except Exception as error:  # contention exhaustion (Aborted) is reported, never counted as OK
            return f"{type(error).__name__}:{str(error)[:60]}"

    return await asyncio.gather(*(one(s) for s in sessions))


async def _ledger(repo, carpark):
    snap = await repo._capacity_ref(carpark).get()
    return (snap.to_dict() or {}).get("leases", {})


async def _locks_at(repo, carpark):
    q = repo._client.collection("virtual_locks").where("carpark_id", "==", carpark)
    return [s.id async for s in q.stream()]


@pytest.mark.parametrize("capacity,n", [(1, 12), (3, 15)])
def test_concurrent_last_spots_never_oversell(capacity, n):
    async def main():
        repo = _repo()
        tag = uuid.uuid4().hex[:8]
        carpark = f"CP_{tag}"
        sessions = [f"s_{tag}_{i}" for i in range(n)]
        await asyncio.gather(*(_start(repo, s) for s in sessions))
        results = await _race(repo, carpark, sessions, capacity)
        ledger = await _ledger(repo, carpark)
        locks = await _locks_at(repo, carpark)
        await repo.close()
        print(f"capacity={capacity} n={n} results={sorted(results)}")
        assert results.count("OK") == capacity
        assert len(ledger) == capacity and sorted(locks) == sorted(ledger)

    asyncio.run(main())


def test_concurrent_release_and_commit_on_last_spot_is_consistent():
    async def main():
        repo = _repo()
        tag = uuid.uuid4().hex[:8]
        carpark = f"CP_{tag}"
        holder, others = f"h_{tag}", [f"o_{tag}_{i}" for i in range(6)]
        await asyncio.gather(*(_start(repo, s) for s in [holder, *others]))
        await repo.commit_lock(holder, carpark, 1, destination_epoch=1, idempotency_key=f"k-{holder}")
        release = repo.release_lock(holder, carpark, reason="DESTINATION_CHANGED", lock_revision=1, new_destination_epoch=2)
        results, _ = await asyncio.gather(_race(repo, carpark, others, 1), release)
        ledger = await _ledger(repo, carpark)
        locks = await _locks_at(repo, carpark)
        await repo.close()
        assert results.count("OK") <= 1 and len(ledger) <= 1 and sorted(locks) == sorted(ledger)

    asyncio.run(main())


def test_concurrent_switch_keeps_both_ledgers_consistent():
    async def main():
        repo = _repo()
        tag = uuid.uuid4().hex[:8]
        a, b = f"CPA_{tag}", f"CPB_{tag}"
        mover, rivals = f"m_{tag}", [f"r_{tag}_{i}" for i in range(5)]
        await asyncio.gather(*(_start(repo, s) for s in [mover, *rivals]))
        await repo.commit_lock(mover, a, 1, destination_epoch=1, idempotency_key=f"k-{mover}-a")
        switch = repo.commit_lock(mover, b, 1, destination_epoch=1, idempotency_key=f"k-{mover}-b")
        results_a, _ = await asyncio.gather(_race(repo, a, rivals, 1), switch)
        la, lb = await _ledger(repo, a), await _ledger(repo, b)
        await repo.close()
        assert set(lb) == {mover} and mover not in la
        assert len(la) <= 1 and results_a.count("OK") == len(la)

    asyncio.run(main())


def test_concurrent_bootstrap_counts_legacy_lock_once():
    async def main():
        repo = _repo()
        tag = uuid.uuid4().hex[:8]
        carpark = f"CP_{tag}"
        await repo._client.collection("virtual_locks").document(f"legacy_{tag}").set(
            {"carpark_id": carpark, "created_at": datetime.now(UTC) - timedelta(minutes=1)}
        )
        sessions = [f"s_{tag}_{i}" for i in range(8)]
        await asyncio.gather(*(_start(repo, s) for s in sessions))
        results = await _race(repo, carpark, sessions, 2)
        ledger = await _ledger(repo, carpark)
        await repo.close()
        assert results.count("OK") == 1 and len(ledger) == 2 and f"legacy_{tag}" in ledger

    asyncio.run(main())


def test_tombstone_and_creation_proof_on_emulator():
    async def main():
        from app.firestore import LockConflictError

        repo = _repo()
        tag = uuid.uuid4().hex[:8]
        sid = f"t_{tag}"
        await _start(repo, sid)
        await repo.release_lock(sid, "CP", reason="USER_ABORTED")
        with pytest.raises(LockConflictError):
            await repo.touch_session(sid, DEST, destination_epoch=1, require_open=True, session_started_at=datetime.now(UTC))
        with pytest.raises(LockConflictError):
            await repo.touch_session(f"old_{tag}", DEST, destination_epoch=1, require_open=True, session_started_at=datetime.now(UTC) - timedelta(hours=13))
        await repo.close()

    asyncio.run(main())


def test_queued_parked_event_replayed_after_switch_never_deletes_new_lock():
    async def main():
        from app.firestore import LockConflictError

        repo = _repo()
        tag = uuid.uuid4().hex[:8]
        sid, a, b = f"p_{tag}", f"CPA_{tag}", f"CPB_{tag}"
        await _start(repo, sid)
        await repo.commit_lock(sid, a, 5, destination_epoch=1, idempotency_key=f"k-{sid}-a")
        await repo.release_lock(sid, a, reason="DESTINATION_CHANGED", lock_revision=1, new_destination_epoch=2)
        await repo.commit_lock(sid, b, 5, destination_epoch=2, idempotency_key=f"k-{sid}-b")
        ts = int(datetime.now(UTC).timestamp())
        replays = [repo.confirm_parked(sid, ts, lock_revision=1, contract_version=2, destination_epoch=1) for _ in range(3)]
        results = await asyncio.gather(*replays, return_exceptions=True)
        legacy = await asyncio.gather(repo.confirm_parked(sid, ts + 3600), return_exceptions=True)
        lock = (await repo._client.collection("virtual_locks").document(sid).get()).to_dict()
        ledger = await _ledger(repo, b)
        await repo.close()
        assert all(isinstance(r, LockConflictError) for r in results + legacy)
        assert lock["carpark_id"] == b and set(ledger) == {sid}

    asyncio.run(main())
