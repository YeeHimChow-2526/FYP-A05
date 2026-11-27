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
  storage_class               = "STANDARD"
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"
  force_destroy               = false

  # Live (verified 2026-11-11): 7-day soft delete is on; versioning is off (not present in bucket describe).
  soft_delete_policy {
    retention_duration_seconds = 604800
  }

  # Cold-path history retention only. Live snapshots and static objects are unaffected.
  lifecycle_rule {
    action {
      type          = "SetStorageClass"
      storage_class = "NEARLINE"
    }

    condition {
      age            = 30
      matches_prefix = ["history/"]
    }
  }

  lifecycle_rule {
    action {
      type          = "SetStorageClass"
      storage_class = "COLDLINE"
    }

    condition {
      age            = 90
      matches_prefix = ["history/"]
    }
  }

  lifecycle_rule {
    action {
      type          = "SetStorageClass"
      storage_class = "ARCHIVE"
    }

    condition {
      age            = 365
      matches_prefix = ["history/"]
    }
  }

  lifecycle_rule {
    action {
      type = "Delete"
    }

    condition {
      age            = 730
      matches_prefix = ["history/"]
    }
  }

  depends_on = [google_project_service.required_apis]
}

resource "google_storage_bucket_iam_member" "snapshot_reader" {
  bucket = google_storage_bucket.state_snapshots.name
  role   = "roles/storage.objectViewer"
  member = "serviceAccount:${google_service_account.app_sa.email}"
}

# Runtime SA write access on the snapshot bucket. This binding EXISTS LIVE (roles/storage.objectUser,
# verified 2026-11-11) and is needed for SNAPSHOT_ON_DEMAND_REFRESH_ENABLED=true (API creates/overwrites snapshot
# objects). It sits next to snapshot_reader because the live bucket has both bindings. If it is not in state,
# import it before apply instead of letting Terraform create it.
# The deploy identity's roles/storage.objectAdmin on this bucket is deliberately NOT in Terraform.
resource "google_storage_bucket_iam_member" "snapshot_runtime_writer_live" {
  bucket = google_storage_bucket.state_snapshots.name
  role   = "roles/storage.objectUser"
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
    }
  }

  # Mirrors live exactly (verified 2026-11-11): 100% pinned to revision 00008, plus tag "cand" on the same revision.
  # Pinned (not LATEST) so a fresh plan does not change traffic. When this module deploys a new image,
  # change var.backend_traffic_revision to the new revision name first (or switch to LATEST) - see docs.
  traffic {
    type     = "TRAFFIC_TARGET_ALLOCATION_TYPE_REVISION"
    revision = var.backend_traffic_revision
    percent  = 100
  }
  traffic {
    type     = "TRAFFIC_TARGET_ALLOCATION_TYPE_REVISION"
    revision = var.backend_traffic_revision
    tag      = var.backend_traffic_tag
    percent  = 0
  }

  # Not part of the desired state: client fields are stamped by gcloud/console on every deploy, and the template
  # labels/annotations/execution_environment are metadata that the live revision carries but this config never set
  # (e.g. run.googleapis.com/startupProbeType). Ignoring them keeps the post-import plan free of metadata-only noise.
  lifecycle {
    ignore_changes = [
      client,
      client_version,
      template[0].labels,
      template[0].annotations,
      template[0].execution_environment,
      # Live template carries the auto-generated revision name (00008-vef); TF has none.
      # Ignoring it avoids a no-op redeploy at import. New images still roll new revisions.
      template[0].revision,
    ]
  }

  depends_on = [
    google_project_service.required_apis,
    google_project_iam_member.firestore_user,
    google_storage_bucket_iam_member.snapshot_reader,
    google_storage_bucket_iam_member.snapshot_runtime_writer_live,
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