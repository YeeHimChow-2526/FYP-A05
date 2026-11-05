output "api_base_url" {
  value       = google_cloud_run_v2_service.backend.uri
  description = "Generated HTTPS URL for the Cloud Run API service."
}

output "state_snapshot_bucket" {
  value       = google_storage_bucket.state_snapshots.name
  description = "GCS bucket for versioned region snapshots and manifest.json."
}

output "api_image_repository" {
  value       = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.api.repository_id}"
  description = "Artifact Registry repository for the API container image."
}