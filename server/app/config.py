from pydantic import Field
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
    source_stale_threshold_seconds: int = Field(default=600, gt=0)
    in_memory_cache_ttl_seconds: int = Field(default=30, gt=0)
    low_watermark_vacancies: int = Field(default=3, ge=0)
    firestore_max_concurrent_lock_counts: int = Field(default=5, gt=0)
    crowd_ip_hash_secret: str = ""
