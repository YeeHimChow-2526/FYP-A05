# cloud_run.tf

# Shared Service Account for Backend/Sync to access Firestore & Vertex AI
resource "google_service_account" "app_sa" {
  account_id   = "serverless-app-sa"
  display_name = "Service Account for Serverless RAG App"

  depends_on = [google_project_service.required_apis]
}

resource "google_project_iam_member" "firestore_user" {
  project = var.project_id
  role    = "roles/datastore.user"
  member  = "serviceAccount:${google_service_account.app_sa.email}"
}

resource "google_project_iam_member" "vertex_ai_user" {
  project = var.project_id
  role    = "roles/aiplatform.user"
  member  = "serviceAccount:${google_service_account.app_sa.email}"
}

resource "google_secret_manager_secret_iam_member" "api_key_accessor" {
  project   = var.project_id
  secret_id = google_secret_manager_secret.internal_api_key.secret_id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.app_sa.email}"
}

resource "google_secret_manager_secret_iam_member" "crowd_hash_key_accessor" {
  project   = var.project_id
  secret_id = google_secret_manager_secret.crowd_ip_hash_secret.secret_id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.app_sa.email}"
}

resource "google_secret_manager_secret" "internal_api_key" {
  project   = var.project_id
  secret_id = "hkparking-internal-api-key"

  replication {
    auto {}
  }

  depends_on = [google_project_service.required_apis]
}

resource "google_secret_manager_secret" "crowd_ip_hash_secret" {
  project   = var.project_id
  secret_id = "hkparking-crowd-ip-hash-secret"

  replication {
    auto {}
  }

  depends_on = [google_project_service.required_apis]
}

resource "google_storage_bucket" "state_snapshots" {
  name                        = var.state_snapshot_bucket_name
  location                    = var.region
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"
  force_destroy               = false

  depends_on = [google_project_service.required_apis]
}

resource "google_storage_bucket_iam_member" "snapshot_reader" {
  bucket = google_storage_bucket.state_snapshots.name
  role   = "roles/storage.objectViewer"
  member = "serviceAccount:${google_service_account.app_sa.email}"
}

# Backend API Service
resource "google_cloud_run_v2_service" "backend" {
  name     = "backend-ai-service"
  location = var.region
  ingress  = "INGRESS_TRAFFIC_ALL"

  template {
    service_account                  = google_service_account.app_sa.email
    max_instance_request_concurrency = var.backend_max_concurrency # live: 160 (Cloud Run default is 80)
    timeout                          = "300s"                      # live: 300s

    scaling {
      min_instance_count = 0
      max_instance_count = 10
    }

    containers {
      image = local.backend_image
      resources {
        cpu_idle = false # live: CPU always allocated (run.googleapis.com/cpu-throttling = false)
        limits = {
          cpu    = "2"
          memory = "2Gi"
        }
      }
      env {
        name  = "PROJECT_ID"
        value = var.project_id
      }
      env {
        name  = "GCS_STATE_BUCKET"
        value = "gs://${google_storage_bucket.state_snapshots.name}"
      }
      env {
        name  = "SECRET_RELOAD_TS"
        value = var.backend_secret_reload_ts
      }
      env {
        name  = "SNAPSHOT_ON_DEMAND_REFRESH_ENABLED"
        value = var.backend_snapshot_on_demand_refresh_enabled
      }
      env {
        name  = "CAPACITY_BOOTSTRAP_ENABLED"
        value = var.backend_capacity_bootstrap_enabled
      }
      env {
        name = "INTERNAL_API_KEY"

        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.internal_api_key.secret_id
            version = "latest"
          }
        }
      }
      env {
        name = "CROWD_IP_HASH_SECRET"

        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.crowd_ip_hash_secret.secret_id
            version = "latest"
          }
        }
      }
    }
  }

  # The console / gcloud stamp these client fields on every deploy; they are not part of the desired state.
  lifecycle {
    ignore_changes = [client, client_version]
  }

  depends_on = [
    google_project_service.required_apis,
    google_project_iam_member.firestore_user,
    google_storage_bucket_iam_member.snapshot_reader,
    google_secret_manager_secret_iam_member.api_key_accessor,
    google_secret_manager_secret_iam_member.crowd_hash_key_accessor,
  ]
}

resource "google_cloud_run_v2_service_iam_member" "backend_public_invoker" {
  project  = var.project_id
  name     = google_cloud_run_v2_service.backend.name
  location = var.region
  role     = "roles/run.invoker"
  member   = "allUsers"
}

moved {
  from = google_cloud_run_v2_service_iam_member.backend_load_balancer_invoker
  to   = google_cloud_run_v2_service_iam_member.backend_public_invoker
}