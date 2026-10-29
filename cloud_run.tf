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
    containers {
      image = "us-docker.pkg.dev/cloudrun/container/hello" # Replace with your Backend/AI Agent image URL
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
        name  = "VERTEX_AI_LOCATION"
        value = output.vertex_ai_location # Pulls from vertexai.tf or variables
      }
      # If using a custom endpoint from vertexai.tf, you can pass it like this:
      # env {
      #   name  = "VERTEX_AI_ENDPOINT_ID"
      #   value = google_vertex_ai_endpoint.agent_endpoint.id
      # }
    }
  }
}

# 3. Auto-Sync Program (Syncs Open Data API to Firestore)
resource "google_cloud_run_v2_service" "sync_job" {
  name     = "open-data-sync-service"
  location = var.region
  ingress  = "INGRESS_TRAFFIC_INTERNAL"

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