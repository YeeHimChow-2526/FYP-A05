resource "google_firestore_database" "database" {
  project     = var.project_id
  name        = "(default)"
  location_id = var.region
  type        = "FIRESTORE_NATIVE"

  depends_on = [google_project_service.required_apis]
}

resource "google_firestore_field" "active_sessions_ttl" {
  project    = var.project_id
  database   = google_firestore_database.database.name
  collection = "active_sessions"
  field      = "expire_at"

  ttl_config {}
}

resource "google_firestore_field" "virtual_locks_ttl" {
  project    = var.project_id
  database   = google_firestore_database.database.name
  collection = "virtual_locks"
  field      = "expire_at"

  ttl_config {}
}

resource "google_firestore_field" "crowd_reports_ttl" {
  project    = var.project_id
  database   = google_firestore_database.database.name
  collection = "reports"
  field      = "expire_at"

  ttl_config {}
}