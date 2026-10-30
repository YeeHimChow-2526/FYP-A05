# cloud_run.tf

# Shared Service Account for Backend/Sync to access Firestore & Vertex AI
resource "google_service_account" "app_sa" {
  account_id   = "serverless-app-sa"
  display_name = "Service Account for Serverless RAG App"
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
}

resource "google_storage_bucket_iam_member" "snapshot_reader" {
  bucket = google_storage_bucket.state_snapshots.name
  role   = "roles/storage.objectViewer"
  member = "serviceAccount:${google_service_account.app_sa.email}"
}

# 1. Frontend Service
resource "google_cloud_run_v2_service" "frontend" {
  name     = "frontend-service"
  location = var.region
  ingress  = "INGRESS_TRAFFIC_ALL"

  template {
    containers {
      image = "us-docker.pkg.dev/cloudrun/container/hello" # Replace with your Frontend image URL
      resources {
        limits = {
          cpu    = "1"
          memory = "512Mi"
        }
      }
    }
  }
}

# Make Frontend publicly accessible
resource "google_cloud_run_v2_service_iam_member" "frontend_public" {
  name     = google_cloud_run_v2_service.frontend.name
  location = var.region
  role     = "roles/run.invoker"
  member   = "allUsers"
}

# 2. Backend Logic & AI Agent Service
resource "google_cloud_run_v2_service" "backend" {
  name     = "backend-ai-service"
  location = var.region
  ingress  = "INGRESS_TRAFFIC_INTERNAL_LOAD_BALANCER"

  template {
    service_account = google_service_account.app_sa.email
    scaling {
      min_instance_count = 0
      max_instance_count = 10
    }

    containers {
      image = var.backend_image
      resources {
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

  depends_on = [
    google_project_iam_member.firestore_user,
    google_storage_bucket_iam_member.snapshot_reader,
    google_secret_manager_secret_iam_member.api_key_accessor,
    google_secret_manager_secret_iam_member.crowd_hash_key_accessor,
  ]
}

resource "google_cloud_run_v2_service_iam_member" "backend_load_balancer_invoker" {
  project  = var.project_id
  name     = google_cloud_run_v2_service.backend.name
  location = var.region
  role     = "roles/run.invoker"
  member   = "allUsers"
}

# 3. Auto-Sync Program (Syncs Open Data API to Firestore)
resource "google_cloud_run_v2_service" "sync_job" {
  name     = "open-data-sync-service"
  location = var.region
  ingress  = "INGRESS_TRAFFIC_INTERNAL_ONLY"

  template {
    service_account = google_service_account.app_sa.email
    containers {
      image = "us-docker.pkg.dev/cloudrun/container/hello" # Replace with your Sync script image URL
      resources {
        limits = {
          cpu    = "1"
          memory = "512Mi"
        }
      }
    }
  }
}