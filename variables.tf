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

variable "api_domain" {
  type        = string
  description = "DNS name mapped to the external HTTPS load balancer."
}

variable "state_snapshot_bucket_name" {
  type        = string
  description = "Globally unique GCS bucket name for versioned parking snapshots."
}

variable "cloud_armor_rate_limit_per_ip_per_minute" {
  type        = number
  description = "Cloud Armor request threshold per source IP in each 60-second interval."
  default     = 60
}