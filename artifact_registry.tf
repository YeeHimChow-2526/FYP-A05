resource "google_artifact_registry_repository" "api" {
  project       = var.project_id
  location      = var.region
  repository_id = "hkparking"
  description   = "Container images for the Hong Kong parking API."
  format        = "DOCKER"

  depends_on = [google_project_service.required_apis]
}
