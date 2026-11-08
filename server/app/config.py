from pydantic import Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="", extra="ignore")

    project_id: str | None = None
    internal_api_key: str = ""
    gcs_state_bucket: str = ""
    poi_catalog_path: str = "assets/hk_poi_master.json"
    search_radius_meters: int = Field(default=1500, gt=0)
    w_dist: int = Field(default=50, ge=0)
    w_vac: int = Field(default=40, ge=0)
    d_max_meters: int = Field(default=1500, gt=0)
    vacancy_score_denominator: int = Field(default=15, gt=0)
    stale_tier1_minutes: int = Field(default=15, ge=0)
    stale_tier1_penalty_points: int = Field(default=5, ge=0)
    stale_tier2_minutes: int = Field(default=25, ge=0)
    stale_tier2_penalty_points: int = Field(default=20, ge=0)
    scarcity_pool_min_count: int = Field(default=3, gt=0)
    scarcity_bonus_points: int = Field(default=30, ge=0)
    scarcity_expand_multiplier: float = Field(default=2.0, gt=1)
    meter_cluster_radius_meters: int = Field(default=150, gt=0)
    meter_hotspot_min_vacancies: int = Field(default=2, ge=0)
    meter_residential_min_vacancies: int = Field(default=2, ge=0)
    lock_count_top_k_candidates: int = Field(default=5, gt=0)
    virtual_lock_timeout_minutes: int = Field(default=30, gt=0)
    active_session_idle_timeout_minutes: int = Field(default=30, gt=0)
    committed_session_idle_timeout_hours: int = Field(default=3, gt=0)
    snapshot_memory_cache_ttl_seconds: int = Field(default=45, gt=0)
    # Shared on-demand refresh (app/shared_cache.py, plan §5 option B). Off until rollout:
    # needs object write on the state bucket for the API service account (see README rollout).
    snapshot_on_demand_refresh_enabled: bool = False
    snapshot_refresh_min_interval_seconds: int = Field(default=60, ge=10)   # s between successful upstream checks, shared
    snapshot_lease_seconds: int = Field(default=45, gt=0)                   # s; > fetches per refresh (up to 4 x 8 s with meters)
    snapshot_fetch_timeout_seconds: float = Field(default=8, gt=0, le=20)   # s per upstream HTTP call, 1 attempt on-demand
    snapshot_static_ttl_seconds: int = Field(default=21600, ge=300)         # s before re-checking static metadata (ETag)
    snapshot_failure_backoff_base_seconds: int = Field(default=30, gt=0)    # s, doubles per consecutive failure
    snapshot_failure_backoff_max_seconds: int = Field(default=600, gt=0)    # s cap
    snapshot_refresh_wait_seconds: float = Field(default=2, ge=0, le=10)    # s a request waits when stale data exists
    meter_refresh_enabled: bool = False  # TD meter CSVs via same CAS; separate source state
    source_stale_threshold_seconds: int = Field(default=600, gt=0)
    in_memory_cache_ttl_seconds: int = Field(default=30, gt=0)
    low_watermark_vacancies: int = Field(default=3, ge=0)
    # §9.2 score terms (points). Pain: chronic replaces general; exempt in scarcity.
    perk_bonus_points: int = Field(default=15, ge=0)
    perks_verified_threshold: float = Field(default=0.8, ge=0, le=1)
    pain_general_penalty_points: int = Field(default=20, ge=0)
    chronic_blocked_penalty_points: int = Field(default=40, ge=0)
    crowd_report_single_penalty: int = Field(default=45, ge=0)
    crowd_report_rank_cap_min_sources: int = Field(default=2, gt=0)
    random_jitter_max_points: int = Field(default=5, ge=0)
    firestore_max_concurrent_lock_counts: int = Field(default=5, gt=0)
    crowd_ip_hash_secret: str = ""
    # Session documents (with monotonic destination_epoch / lock_revision and terminal tombstones)
    # are kept at least this long via the TTL field expire_at. Must cover every accepted
    # retry/late-delivery window, so it may not be shorter than any idle or lock window.
    session_retention_hours: int = Field(default=24, gt=0)
    # New session ids are accepted only if session_started_at is within this window (see
    # lock_policy.decide_touch for the non-resurrection proof). Must be < retention - skew.
    session_create_window_hours: int = Field(default=12, gt=0)
    client_clock_skew_seconds: int = Field(default=300, ge=0, le=3600)
    # Unversioned clients creating sessions without session_started_at (unsafe: TTL resurrection).
    legacy_session_create_enabled: bool = False
    # Per-car-park capacity ledger (carpark_capacity/{id}). Missing ledgers fail closed
    # (CAPACITY_NOT_READY) until legacy (pre-ledger) writers are drained and this is enabled.
    capacity_bootstrap_enabled: bool = False
    firestore_txn_max_attempts: int = Field(default=10, gt=0, le=30)
    firestore_txn_outer_rounds: int = Field(default=3, gt=0, le=10)
    capacity_max_leases: int = Field(default=3000, gt=0, le=5000)

    @model_validator(mode="after")
    def retention_covers_windows(self) -> "Settings":
        minutes = self.session_retention_hours * 60
        floor = max(
            self.virtual_lock_timeout_minutes,
            self.active_session_idle_timeout_minutes,
            self.committed_session_idle_timeout_hours * 60,
        )
        if minutes < floor:
            raise ValueError("session_retention_hours must cover lock and idle windows")
        if self.session_retention_hours * 3600 <= self.session_create_window_hours * 3600 + self.client_clock_skew_seconds:
            raise ValueError("session_retention_hours must exceed session_create_window_hours + clock skew")
        return self
