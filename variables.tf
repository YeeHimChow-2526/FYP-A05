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