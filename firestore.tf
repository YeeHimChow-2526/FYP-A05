resource "google_firestore_database" "database" {
  project                           = var.project_id
  name                              = "(default)"
  location_id                       = var.region
  type                              = "FIRESTORE_NATIVE"
  concurrency_mode                  = "PESSIMISTIC" # live: PESSIMISTIC
  app_engine_integration_mode       = "DISABLED"    # live: DISABLED
  point_in_time_recovery_enablement = var.firestore_enable_point_in_time_recovery ? "POINT_IN_TIME_RECOVERY_ENABLED" : "POINT_IN_TIME_RECOVERY_DISABLED"
  delete_protection_state           = var.firestore_enable_delete_protection ? "DELETE_PROTECTION_ENABLED" : "DELETE_PROTECTION_DISABLED"
  deletion_policy                   = "ABANDON" # Terraform-only: destroy removes it from state, never deletes the live database

  # Live (verified 2026-11-11): edition STANDARD (provider 5.45.2 has no database_edition argument), realtime updates enabled.

  depends_on = [google_project_service.required_apis]
}

resource "google_firestore_backup_schedule" "daily" {
  count     = var.firestore_enable_daily_backups ? 1 : 0
  project   = var.project_id
  database  = google_firestore_database.database.name
  retention = "${var.firestore_backup_retention_days * 24}h"

  daily_recurrence {}
}

# TTL policies on expire_at. Live policies are ACTIVE on active_sessions, virtual_locks and reports
# (verified 2026-11-11). Default on, and imports.tf imports them so plan must not create them.
locals {
  firestore_ttl_collections = var.firestore_enable_ttl ? {
    active_sessions = "active_sessions"
    virtual_locks   = "virtual_locks"
    crowd_reports   = "reports"
  } : {}
}

resource "google_firestore_field" "ttl" {
  for_each   = local.firestore_ttl_collections
  project    = var.project_id
  database   = google_firestore_database.database.name
  collection = each.value
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
