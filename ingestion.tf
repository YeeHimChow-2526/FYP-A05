resource "google_service_account" "ingestion" {
  count        = var.ingestion_enabled ? 1 : 0
  account_id   = "parking-data-ingest"
  display_name = "Parking open-data ingestion"

  depends_on = [google_project_service.required_apis]
}

resource "google_storage_bucket_iam_member" "ingestion_writer" {
  count  = var.ingestion_enabled ? 1 : 0
  bucket = google_storage_bucket.state_snapshots.name
  role   = "roles/storage.objectUser"
  member = "serviceAccount:${google_service_account.ingestion[0].email}"
}

resource "google_cloud_run_v2_job" "ingestion" {
  count    = var.ingestion_enabled ? 1 : 0
  name     = "parking-data-ingest"
  location = var.region

  template {
    task_count  = 1
    parallelism = 1
    template {
      service_account = google_service_account.ingestion[0].email
      timeout         = "240s"
      max_retries     = 0

      containers {
        image   = var.ingestion_image
        command = ["python"]
        args    = ["-m", "app.ingest.td_carpark", "--archive-raw"]

        resources {
          limits = {
            cpu    = "1"
            memory = "1Gi"
          }
        }

        env {
          name  = "GCS_STATE_BUCKET"
          value = "gs://${google_storage_bucket.state_snapshots.name}"
        }
        env {
          name  = "METER_REFRESH_ENABLED"
          value = "true"
        }
      }
    }
  }

  lifecycle {
    precondition {
      condition     = length(trimspace(var.ingestion_image)) > 0
      error_message = "Set ingestion_image to a newly built server image containing --archive-raw before enabling ingestion."
    }
  }

  depends_on = [google_storage_bucket_iam_member.ingestion_writer]
}

resource "google_service_account" "ingestion_scheduler" {
  count        = var.ingestion_enabled ? 1 : 0
  account_id   = "parking-ingest-scheduler"
  display_name = "Scheduler for parking ingestion"

  depends_on = [google_project_service.required_apis]
}

resource "google_cloud_run_v2_job_iam_member" "ingestion_scheduler" {
  count    = var.ingestion_enabled ? 1 : 0
  project  = var.project_id
  location = var.region
  name     = google_cloud_run_v2_job.ingestion[0].name
  role     = "roles/run.invoker"
  member   = "serviceAccount:${google_service_account.ingestion_scheduler[0].email}"
}

resource "google_cloud_scheduler_job" "ingestion" {
  for_each = var.ingestion_enabled ? {
    daytime   = "*/5 6-23 * * *"
    overnight = "*/15 0-5 * * *"
  } : {}

  name             = each.key == "daytime" ? "parking-data-ingest" : "parking-data-ingest-overnight"
  description      = "Archive TD car park and meter feeds and refresh processed snapshots (${each.key} schedule)."
  region           = var.region
  schedule         = each.value
  time_zone        = "Asia/Hong_Kong"
  attempt_deadline = "30s"

  retry_config {
    retry_count = 0
  }

  http_target {
    http_method = "POST"
    uri         = "https://run.googleapis.com/v2/projects/${var.project_id}/locations/${var.region}/jobs/${google_cloud_run_v2_job.ingestion[0].name}:run"

    oauth_token {
      service_account_email = google_service_account.ingestion_scheduler[0].email
      scope                 = "https://www.googleapis.com/auth/cloud-platform"
    }
  }

  depends_on = [google_cloud_run_v2_job_iam_member.ingestion_scheduler]
}

moved {
  from = google_cloud_scheduler_job.ingestion[0]
  to   = google_cloud_scheduler_job.ingestion["daytime"]
}
