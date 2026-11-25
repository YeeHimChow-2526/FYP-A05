# imports.tf
# Fresh-state takeover: EVERY live object is adopted with an import block, so the first
# `terraform plan` must show only imports (add 0, change 0, destroy 0).
# Remove this file in a follow-up PR after the first successful apply (the resources stay in state).
#
# Verification status (2026-11-11):
#   [V]  verified live (owner's Cloud Shell run or read-only gcloud/describe)
#   [I]  inferred live (the running service could not work otherwise) - confirm with the owner command block
#   [C]  conditional: only imported when its enabling variable is true
#
# Re-checked 2026-11-25 (owner, read-only gcloud): these were NOT found live and are no longer
# imported, so Terraform creates them: SA project roles (datastore.user, aiplatform.user), both
# secrets + their accessor bindings, Artifact Registry repo "hkparking", Cloud Run allUsers invoker,
# and the virtual_locks composite index.

# [V] Project APIs. Owner confirmed 2026-11-11: all 13 in var.project_services are already enabled.
import {
  for_each = toset(var.project_services)
  to       = google_project_service.required_apis[each.key]
  id       = "${var.project_id}/${each.key}"
}

# [V] Runtime service account (also confirmed by `gcloud iam service-accounts describe`)
import {
  to = google_service_account.app_sa
  id = "projects/${var.project_id}/serviceAccounts/${local.runtime_sa_email}"
}

# [V] Snapshot bucket (ASIA-EAST2, STANDARD, uniform access, PAP enforced, 7-day soft delete, no versioning).
import {
  to = google_storage_bucket.state_snapshots
  id = "${var.project_id}/${var.state_snapshot_bucket_name}"
}
# [V] Bucket IAM (owner: serverless-app-sa has objectViewer AND objectUser). Id format: "b/<bucket> <role> <member>".
import {
  to = google_storage_bucket_iam_member.snapshot_reader
  id = "b/${var.state_snapshot_bucket_name} roles/storage.objectViewer serviceAccount:${local.runtime_sa_email}"
}
import {
  to = google_storage_bucket_iam_member.snapshot_runtime_writer_live
  id = "b/${var.state_snapshot_bucket_name} roles/storage.objectUser serviceAccount:${local.runtime_sa_email}"
}

# [V] Cloud Run service (read-only describe)
import {
  to = google_cloud_run_v2_service.backend
  id = "projects/${var.project_id}/locations/${var.region}/services/backend-ai-service"
}

# [V] Firestore (default) database
import {
  to = google_firestore_database.database
  id = "projects/${var.project_id}/databases/(default)"
}

# Firestore field configs on expire_at (exist live; TTL itself is not enabled yet, so plan adds ttl_config).
import {
  for_each = local.firestore_ttl_collections
  to       = google_firestore_field.ttl[each.key]
  id       = "projects/${var.project_id}/databases/(default)/collectionGroups/${each.value}/fields/expire_at"
}

# Not imported on purpose: google_firestore_backup_schedule.daily (count = 0 by default; live has 0 schedules).
