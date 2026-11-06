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
  description = "Published container image URI for server/."
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