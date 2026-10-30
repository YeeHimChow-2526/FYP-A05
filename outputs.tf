output "api_ip_address" {
  value       = google_compute_global_address.api.address
  description = "Point the configured API DNS name at this HTTPS load-balancer IP."
}

output "api_base_url" {
  value       = "https://${var.api_domain}"
  description = "External HTTPS base URL for the FastAPI service."
}

output "state_snapshot_bucket" {
  value       = google_storage_bucket.state_snapshots.name
  description = "GCS bucket for versioned region snapshots and manifest.json."
}

output "api_image_repository" {
  value       = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.api.repository_id}"
  description = "Artifact Registry repository for the API container image."
}