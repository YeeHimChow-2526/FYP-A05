# imports.tf
# One-off import of Firestore objects that already exist live. Remove this file after the first
# successful apply by the team that owns the state (the resource then stays in state).
#
# Index id CICAgOjXh4EK = virtual_locks(carpark_id ASC, created_at ASC), COLLECTION scope, READY
# (read earlier by the team; a fresh read was denied on 2026-11-11, so re-check the id with
#  `gcloud firestore indexes composite list` using an account that has datastore.indexes.list).
import {
  to = google_firestore_index.virtual_locks_carpark_created
  id = "projects/${var.project_id}/databases/(default)/collectionGroups/virtual_locks/indexes/CICAgOjXh4EK"
}
