resource "google_firestore_database" "database" {
  project                           = var.project_id
  name                              = "(default)"
  location_id                       = var.region
  type                              = "FIRESTORE_NATIVE"
  point_in_time_recovery_enablement = var.firestore_enable_point_in_time_recovery ? "POINT_IN_TIME_RECOVERY_ENABLED" : "POINT_IN_TIME_RECOVERY_DISABLED"
  delete_protection_state           = "DELETE_PROTECTION_ENABLED"
  deletion_policy                   = "ABANDON"

  depends_on = [google_project_service.required_apis]
}

resource "google_firestore_backup_schedule" "daily" {
  count     = var.firestore_enable_daily_backups ? 1 : 0
  project   = var.project_id
  database  = google_firestore_database.database.name
  retention = "${var.firestore_backup_retention_days * 24}h"

  daily_recurrence {}
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

# Composite index used by the destination-release lock queries.
# Already exists live (READY, created from firestore.indexes.json). It MUST be imported, never created:
# see imports.tf and docs/TERRAFORM_LIVE_SYNC.md.
resource "google_firestore_index" "virtual_locks_carpark_created" {
  project     = var.project_id
  database    = google_firestore_database.database.name
  collection  = "virtual_locks"
  query_scope = "COLLECTION"

  fields {
    field_path = "carpark_id"
    order      = "ASCENDING"
  }

  fields {
    field_path = "created_at"
    order      = "ASCENDING"
  }
}
