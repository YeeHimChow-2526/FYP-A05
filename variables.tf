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
  description = "Enable Firestore point-in-time recovery for up to 7 days."
  default     = true
}

variable "firestore_enable_daily_backups" {
  type        = bool
  description = "Create a daily Firestore backup schedule."
  default     = true
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