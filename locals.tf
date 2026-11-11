# locals.tf
locals {
  # Explicit backend_image wins; otherwise the live digest in the hkparking Artifact Registry repo.
  backend_image = coalesce(
    var.backend_image,
    "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.api.repository_id}/server@${var.backend_image_digest}",
  )
}
