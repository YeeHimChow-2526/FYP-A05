# Service account for Cloud Scheduler to invoke Cloud Run
resource "google_service_account" "scheduler_sa" {
  account_id   = "cloud-scheduler-sa"
  display_name = "Scheduler Invoker Service Account"
}

resource "google_cloud_run_v2_service_iam_member" "sync_invoker" {
  name     = google_cloud_run_v2_service.sync_job.name
  location = var.region
  role     = "roles/run.invoker"
  member   = "serviceAccount:${google_service_account.scheduler_sa.email}"
}

resource "google_cloud_scheduler_job" "sync_trigger" {
  name             = "trigger-open-data-sync"
  region           = var.region
  schedule         = "0 0 * * *" # Daily at midnight
  time_zone        = "UTC"
  attempt_deadline = "320s"

  http_target {
    http_method = "POST"
    uri         = google_cloud_run_v2_service.sync_job.uri

    oidc_token {
      service_account_email = google_service_account.scheduler_sa.email
    }
  }
}