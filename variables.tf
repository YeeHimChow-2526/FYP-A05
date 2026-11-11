variable "project_id" {
  type        = string
  description = "Your GCP Project ID"
}

variable "region" {
  type        = string
  description = "GCP Region for resources"
  default     = "asia-east2"
}

variable "backend_image" {
  type        = string
  description = "Full container image URI for server/. Leave null to use the live digest pinned below (backend_image_digest)."
  default     = null
}

variable "backend_image_digest" {
  type        = string
  description = "Image digest of hkparking/server running live on Cloud Run revision backend-ai-service-00008-vef (Artifact Registry tag dest-15aab20)."
  default     = "sha256:578bba8d45a04cef4f8e4825e42815e0ce7eeeb004264728aff6d30b79102292"
}

variable "backend_max_concurrency" {
  type        = number
  description = "Cloud Run max concurrent requests per instance. Live value is 160."
  default     = 160
}

variable "backend_secret_reload_ts" {
  type        = string
  description = "Dummy env value that is bumped to force a new revision after a Secret Manager rotation. Live value as of 2026-11-11."
  default     = "1793859646"
}

variable "backend_snapshot_on_demand_refresh_enabled" {
  type        = string
  description = "Live env SNAPSHOT_ON_DEMAND_REFRESH_ENABLED (string \"true\"/\"false\"). Needs write access on the snapshot bucket - see docs/TERRAFORM_LIVE_SYNC.md."
  default     = "true"
}

variable "backend_capacity_bootstrap_enabled" {
  type        = string
  description = "Live env CAPACITY_BOOTSTRAP_ENABLED (string \"true\"/\"false\")."
  default     = "true"
}

variable "state_snapshot_bucket_name" {
  type        = string
  description = "Globally unique GCS bucket name for versioned parking snapshots."
}

variable "firestore_enable_point_in_time_recovery" {
  type        = bool
  description = "Enable Firestore point-in-time recovery for up to 7 days. Live is DISABLED (verified 2026-11-11), so the default is false; turning it on is a separate team decision with cost."
  default     = false
}

variable "firestore_enable_delete_protection" {
  type        = bool
  description = "Enable Firestore delete protection. Live is DELETE_PROTECTION_DISABLED (verified 2026-11-11), so the default is false; enabling it is a separate team decision."
  default     = false
}

variable "firestore_enable_daily_backups" {
  type        = bool
  description = "Create a daily Firestore backup schedule. No schedule was applied by Terraform (existence live not checked), so the default is false; turning it on is a separate team decision with cost."
  default     = false
}

variable "firestore_backup_retention_days" {
  type        = number
  description = "Number of days to retain scheduled Firestore backups (1-98)."
  default     = 14

  validation {
    condition = (
      var.firestore_backup_retention_days >= 1 &&
      var.firestore_backup_retention_days <= 98 &&
      floor(var.firestore_backup_retention_days) == var.firestore_backup_retention_days
    )
    error_message = "firestore_backup_retention_days must be a whole number from 1 to 98."
  }
}

variable "project_services" {
  type        = list(string)
  description = "Google APIs managed (and imported) by this configuration. Must list ONLY services that are already enabled live, otherwise the import fails or Terraform would enable a new API. Confirm with the read-only command in docs/TERRAFORM_LIVE_SYNC.md."
  default = [
    "serviceusage.googleapis.com",
    "cloudresourcemanager.googleapis.com",
    "iam.googleapis.com",
    "run.googleapis.com",
    "firestore.googleapis.com",
    "firebaserules.googleapis.com",
    "aiplatform.googleapis.com",
    "cloudscheduler.googleapis.com",
    "cloudbuild.googleapis.com",
    "artifactregistry.googleapis.com",
    "compute.googleapis.com",
    "secretmanager.googleapis.com",
    "storage.googleapis.com",
  ]
}

variable "firestore_enable_ttl" {
  type        = bool
  description = "Manage the Firestore TTL policies (field expire_at on active_sessions, virtual_locks, reports). Live policies are ACTIVE (verified 2026-11-11), so the default is true and they are imported."
  default     = true
}

variable "backend_traffic_revision" {
  type        = string
  description = "Cloud Run revision that holds 100% of traffic live (and the tag below). Live: backend-ai-service-00008-vef."
  default     = "backend-ai-service-00008-vef"
}

variable "backend_traffic_tag" {
  type        = string
  description = "Traffic tag that exists live on the same revision."
  default     = "cand"
}
