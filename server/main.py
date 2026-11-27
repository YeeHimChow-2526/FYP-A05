import ipaddress
import secrets
from contextlib import asynccontextmanager
from typing import Any

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse

from app.config import Settings
from app.firestore import ContentionError, FirestoreRepository, LockFullError
from app.lock_policy import LockConflictError
from app.models import (
    CommitRequest,
    CrowdReportRequest,
    RecommendRequest,
    ReleaseRequest,
    SyncOutboxRequest,
    TripReportRequest,
)
from app.poi import PoiCatalog, PoiCatalogUnavailable
from app.recommend import RecommendationService
from app.snapshots import GcsSnapshotSource, SnapshotSource, SnapshotUnavailable


def create_app(
    settings: Settings | None = None,
    *,
    repository: Any | None = None,
    snapshots: SnapshotSource | None = None,
    poi_catalog: PoiCatalog | None = None,
) -> FastAPI:
    config = settings if settings is not None else Settings()
    catalog = (
        poi_catalog
        if poi_catalog is not None
        else PoiCatalog(config.poi_catalog_path)
    )

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        firestore_repository = (
            repository if repository is not None else FirestoreRepository(config)
        )
        snapshot_source = snapshots
        if snapshot_source is None:
            snapshot_source = GcsSnapshotSource(
                config.gcs_state_bucket,
                config.snapshot_memory_cache_ttl_seconds,
                settings=config,
            )
        app.state.repository = firestore_repository
        app.state.snapshots = snapshot_source
        app.state.recommendations = RecommendationService(
            config,
            catalog,
            snapshot_source,
            firestore_repository,
        )
        yield
        close = getattr(firestore_repository, "close", None)
        if close is not None:
            await close()

    app = FastAPI(title="Hong Kong Parking API", version="2.11.0", lifespan=lifespan, redirect_slashes=False)

    @app.exception_handler(ContentionError)
    async def contention(_request: Request, _error: ContentionError):
        return JSONResponse(status_code=503, content={"status": "CONTENTION"}, headers={"Retry-After": "1"})
    app.state.settings = config

    @app.middleware("http")
    async def authenticate_client(request: Request, call_next):
        # /health only — Cloud Run GFE intercepts paths ending in "z" (e.g. /healthz).
        if request.url.path.rstrip("/") == "/health":
            return await call_next(request)
        if not config.internal_api_key:
            return JSONResponse(
                status_code=503,
                content={"detail": "INTERNAL_API_KEY is not configured"},
            )
        supplied = request.headers.get("X-Internal-API-Key", "")
        if not secrets.compare_digest(supplied, config.internal_api_key):
            return JSONResponse(status_code=401, content={"detail": "Invalid API key"})
        return await call_next(request)

    @app.get("/health")
    async def health():
        return {"status": "ok"}

    def conflict(error: LockConflictError) -> JSONResponse:
        return JSONResponse(
            status_code=error.http_status,
            content={"status": error.code, "detail": str(error)},
        )

    @app.post("/v1/recommend-and-route")
    async def recommend(body: RecommendRequest, request: Request):
        try:
            return await request.app.state.recommendations.recommend(body)
        except LockConflictError as error:
            return conflict(error)
        except SnapshotUnavailable as error:
            raise HTTPException(status_code=503, detail=str(error)) from error
        except PoiCatalogUnavailable as error:
            raise HTTPException(status_code=503, detail=str(error)) from error

    @app.post("/v1/session/commit")
    async def commit(body: CommitRequest, request: Request):
        try:
            # Firestore transactions cannot read the GCS snapshot itself.
            vacancies = await request.app.state.recommendations.current_vacancies(
                body.target_carpark_id
            )
            lock = await request.app.state.repository.commit_lock(
                body.session_id,
                body.target_carpark_id,
                vacancies,
                destination_epoch=body.destination_epoch,
                idempotency_key=body.idempotency_key,
            )
        except LockConflictError as error:
            return conflict(error)
        except LockFullError:
            return JSONResponse(
                status_code=409,
                content={
                    "status": "LOCK_FULL",
                    "spoken_cue": "呢個停車場啱啱滿咗，有冇第二個？",
                },
            )
        except SnapshotUnavailable as error:
            raise HTTPException(status_code=503, detail=str(error)) from error
        response: dict[str, Any] = {"status": "COMMITTED", "session_id": body.session_id}
        if body.destination_epoch is not None and isinstance(lock, dict):
            response.update(
                contract_version=2,
                lock_id=lock["lock_id"],
                lock_revision=lock["lock_revision"],
                destination_epoch=lock["destination_epoch"],
                replayed=lock["replayed"],
            )
        return response

    @app.post("/v1/session/release")
    async def release(body: ReleaseRequest, request: Request):
        try:
            result = await request.app.state.repository.release_lock(
                body.session_id,
                body.target_carpark_id,
                reason=body.reason.value,
                lock_revision=body.lock_revision,
                new_destination_epoch=body.new_destination_epoch,
            )
        except LockConflictError as error:
            return conflict(error)
        response: dict[str, Any] = {
            "status": body.reason.value,
            "session_id": body.session_id,
            "ratable": False,
        }
        if isinstance(result, dict):
            response.update(result)
            response["ratable"] = False
        return response

    @app.post("/v1/sync/outbox")
    async def sync_outbox(body: SyncOutboxRequest, request: Request):
        if body.event_type != "DRIVER_CONFIRMED":
            raise HTTPException(status_code=422, detail="event_type must be DRIVER_CONFIRMED")
        try:
            await request.app.state.repository.confirm_parked(
                body.session_id,
                body.timestamp,
                lock_revision=body.lock_revision,
                contract_version=body.contract_version,
                destination_epoch=body.destination_epoch,
            )
        except LockConflictError as error:
            return conflict(error)
        return {
            "status": "PARKED_CONFIRMED",
            "session_id": body.session_id,
            "ratable": True,
        }

    @app.post("/v1/feedback/crowd-report")
    async def crowd_report(body: CrowdReportRequest, request: Request):
        try:
            client_ip = client_ip_from_request(request)
            result = await request.app.state.repository.add_crowd_report(
                session_id=body.session_id,
                carpark_id=body.carpark_id,
                report_type=body.report_type,
                client_ip=client_ip,
                client_timestamp=body.client_timestamp,
            )
        except ValueError as error:
            raise HTTPException(status_code=400, detail=str(error)) from error
        except RuntimeError as error:
            raise HTTPException(status_code=503, detail=str(error)) from error
        return result

    @app.post("/v1/feedback/trip-report")
    async def trip_report(body: TripReportRequest, request: Request):
        try:
            await request.app.state.repository.submit_trip_report(
                body.session_id,
                body.trip_timestamp,
                body.feedback.model_dump(),
            )
        except LookupError as error:
            raise HTTPException(status_code=404, detail=str(error)) from error
        except PermissionError as error:
            raise HTTPException(status_code=409, detail=str(error)) from error
        return {"status": "ACCEPTED", "session_id": body.session_id}

    return app


def client_ip_from_request(request: Request) -> str:
    forwarded_for = request.headers.get("X-Load-Balancer-Client-IP")
    if not forwarded_for:
        raise ValueError("Missing load-balancer client IP header")
    last_address = forwarded_for.split(",")[-1].strip()
    try:
        return str(ipaddress.ip_address(last_address))
    except ValueError as error:
        raise ValueError("Invalid load-balancer client IP header") from error


app = create_app()
