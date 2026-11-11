# imports.tf
# Fresh-state takeover: EVERY live object is adopted with an import block, so the first
# `terraform plan` must show only imports (add 0, change 0, destroy 0).
# Remove this file in a follow-up PR after the first successful apply (the resources stay in state).
#
# Verification status (2026-11-11):
#   [V]  verified live (owner's Cloud Shell run or read-only gcloud/describe)
#   [I]  inferred live (the running service could not work otherwise) - confirm with the owner command block
#   [C]  conditional: only imported when its enabling variable is true

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

# [V] Project roles of the runtime SA (owner: only aiplatform.user and datastore.user). Id format: "project role member".
import {
  to = google_project_iam_member.firestore_user
  id = "${var.project_id} roles/datastore.user serviceAccount:${local.runtime_sa_email}"
}
import {
  to = google_project_iam_member.vertex_ai_user
  id = "${var.project_id} roles/aiplatform.user serviceAccount:${local.runtime_sa_email}"
}

# [V] Secrets (automatic replication) and the runtime SA's secretAccessor binding on each (verified 2026-11-11).
# The deploy identity's secretVersionManager on hkparking-internal-api-key is deliberately NOT in Terraform.
import {
  to = google_secret_manager_secret.internal_api_key
  id = "projects/${var.project_id}/secrets/hkparking-internal-api-key"
}
import {
  to = google_secret_manager_secret.crowd_ip_hash_secret
  id = "projects/${var.project_id}/secrets/hkparking-crowd-ip-hash-secret"
}
import {
  to = google_secret_manager_secret_iam_member.api_key_accessor
  id = "projects/${var.project_id}/secrets/hkparking-internal-api-key roles/secretmanager.secretAccessor serviceAccount:${local.runtime_sa_email}"
}
import {
  to = google_secret_manager_secret_iam_member.crowd_hash_key_accessor
  id = "projects/${var.project_id}/secrets/hkparking-crowd-ip-hash-secret roles/secretmanager.secretAccessor serviceAccount:${local.runtime_sa_email}"
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

# [V] Artifact Registry repo (described read-only: DOCKER, STANDARD_REPOSITORY, asia-east2)
import {
  to = google_artifact_registry_repository.api
  id = "projects/${var.project_id}/locations/${var.region}/repositories/hkparking"
}

# [V] Cloud Run service and its public invoker binding (read-only describe + get-iam-policy)
import {
  to = google_cloud_run_v2_service.backend
  id = "projects/${var.project_id}/locations/${var.region}/services/backend-ai-service"
}
import {
  to = google_cloud_run_v2_service_iam_member.backend_public_invoker
  id = "projects/${var.project_id}/locations/${var.region}/services/backend-ai-service roles/run.invoker allUsers"
}

# [V] Firestore (default) database
import {
  to = google_firestore_database.database
  id = "projects/${var.project_id}/databases/(default)"
}

# [V] Composite index virtual_locks(carpark_id ASC, created_at ASC), READY. Imported, never created.
import {
  to = google_firestore_index.virtual_locks_carpark_created
  id = "projects/${var.project_id}/databases/(default)/collectionGroups/virtual_locks/indexes/CICAgOjXh4EK"
}

# [V] Firestore TTL policies (ACTIVE on active_sessions, virtual_locks, reports). Imported when firestore_enable_ttl is true (default).
import {
  for_each = local.firestore_ttl_collections
  to       = google_firestore_field.ttl[each.key]
  id       = "projects/${var.project_id}/databases/(default)/collectionGroups/${each.value}/fields/expire_at"
}

# Not imported on purpose: google_firestore_backup_schedule.daily (count = 0 by default; live has 0 schedules).
