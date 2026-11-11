# Terraform 同雲端現況對齊（新確認版 Terraform）

這個分支由 `Terraform`（`276c07e`，DB 設定：PITR／刪除保護／ABANDON／每日備份）出發，
疊上 PR #5 的後端 code（`15aab20`），再補上「雲端已經存在、但 Terraform 未有寫」的設定。
原本的 `Terraform` 分支冇改動。**仍然冇人證明 `terraform plan` 係「No changes」**——見下面「未驗證」。

## 1. 呢個分支加咗啲乜

| 檔案 | 內容 |
|---|---|
| `server/**`、`DATABASE.md`、`scripts/` | 同 PR #5（`15aab20`）一致；PR #5 已經 rebase 喺 `276c07e` 之上，所以係 fast-forward，冇衝突 |
| `cloud_run.tf` | Cloud Run 對齊現況：`max_instance_request_concurrency = 160`、`cpu_idle = false`（CPU always-on）、`timeout = 300s`、三個 env flag、image 用 digest；`ignore_changes = [client, client_version]` |
| `variables.tf` / `locals.tf` | `backend_image` 變成可選（預設 `null`）；預設用 `backend_image_digest`（現況 digest）拼出 `…/hkparking/server@sha256:…`；其餘 live 值做成有預設嘅變數 |
| `firestore.tf` + `imports.tf` | 新增 `google_firestore_index.virtual_locks_carpark_created`，用 `import` block 接入現有 index，**唔會 create** |
| `provider.tf` | `required_version` 由 `>= 1.5.0` 改為 `>= 1.6.0`（import block 嘅 `id` 要用變數） |
| `terraform.tfvars.example` | `backend_image` 改為可選註解 |

## 2. 用咗嘅現況值（2026-11-11 唯讀讀取；冇任何 secret 值）

Cloud Run 服務 `backend-ai-service`（`asia-east2`）：

- 流量：revision `backend-ai-service-00008-vef` 100%（另有一個指向同一 revision 嘅 tag `cand`）
- Image：Artifact Registry `hkparking/server@sha256:578bba8d45a04cef4f8e4825e42815e0ce7eeeb004264728aff6d30b79102292`（tag `dest-15aab20`，即 PR #5 嘅 `15aab20`）
- `containerConcurrency` = 160；`cpu-throttling` = false（CPU always-on）；`timeoutSeconds` = 300；`sessionAffinity` = false
- 實例：min 0／max 10；資源 CPU 2／memory 2Gi
- Service account：`serverless-app-sa`；ingress：`all`；IAM：`allUsers` 有 `roles/run.invoker`
- Env（只記名同非機密值）：
  - 明文：`PROJECT_ID`、`GCS_STATE_BUCKET`（`gs://<project>-state-snapshots`）、`SECRET_RELOAD_TS=1793859646`、`SNAPSHOT_ON_DEMAND_REFRESH_ENABLED=true`、`CAPACITY_BOOTSTRAP_ENABLED=true`
  - Secret 引用（只記名同 version）：`INTERNAL_API_KEY` → `hkparking-internal-api-key:latest`、`CROWD_IP_HASH_SECRET` → `hkparking-crowd-ip-hash-secret:latest`
- Artifact Registry repo：`hkparking`（asia-east2，DOCKER）

Firestore index `virtual_locks(carpark_id ASC, created_at ASC)`，COLLECTION scope，id `CICAgOjXh4EK`，狀態 READY——
呢個係團隊較早前讀到嘅記錄，今次重讀被拒（見下）。

## 3. 仲未驗證／同現況可能有差異

- Firestore index 列表、Firestore database 設定（PITR、刪除保護、備份）：今次讀取被拒，index id 同 DB 設定都係舊記錄。
- Cloud Scheduler job：讀取被拒，不知道有冇；Terraform 亦冇寫。
- Cloud Storage bucket 設定同 IAM：讀取被拒。DATABASE.md 指出 `SNAPSHOT_ON_DEMAND_REFRESH_ENABLED=true` 需要 bucket 寫入權，
  而 `cloud_run.tf` 只有 `roles/storage.objectViewer`；雲端實際有冇另外授權不明。呢個分支**冇**加寫入權，避免亂估；
  如果 plan／實況顯示需要，用 DATABASE.md 入面 `snapshot_writer` 建議另開一次改動。
- 專案層級 IAM（`serverless-app-sa` 嘅 role）、Secret Manager 內容同 secret version 列表：讀取被拒；Terraform 只管 `latest` 引用。
- Cloud Run 流量：live 固定喺 `…-00008-vef` 並有 `cand` tag；呢個分支冇寫 `traffic` block（用 provider 預設）。下次 apply 會點處理 pinned revision／tag，要睇 plan 輸出。
- Startup probe（TCP 240s）、`SECRET_RELOAD_TS` 同 service annotation `hkparking.align/secret-reload`：只當預設／現值抄入或忽略，冇逐項對 provider 行為驗證。
- 冇對真 state 跑過 `terraform plan`，所以「No changes」未證實。
- repo 入面有被追蹤嘅 `.terraform/providers/…`（約 99 MB provider binary，雖然 `.gitignore` 寫咗 `.terraform/`）；呢個分支冇處理，建議之後另開改動 `git rm --cached`。

## 4. 安全嘅下一步（團隊規則）

1. 準備真 `terraform.tfvars`（唔好 commit）同現有 state（backend），然後 `terraform init`。
2. 先確認 index id：`gcloud firestore indexes composite list --project <PROJECT_ID>`，同 `imports.tf` 入面嘅 `CICAgOjXh4EK` 對返；唔同就改 `imports.tf`。
3. 跑 `terraform plan`，預期**冇變動**（import 會顯示 1 項 import）。有任何差異，逐項改返 code 或者確認係雲端要跟，**唔好為咗收平 plan 而 apply**。
4. 如果冇現成 state：唔好 `apply`。先決定 state 位置，再逐個 resource `terraform import`，否則 Terraform 會試圖建立已存在嘅資源。
5. 手動 import 嘅等價指令（唔用 `imports.tf` 時）：
   ```bash
   terraform import google_firestore_index.virtual_locks_carpark_created \
     'projects/<PROJECT_ID>/databases/(default)/collectionGroups/virtual_locks/indexes/CICAgOjXh4EK'
   ```
6. 任何 `apply`、`import` 寫入 state、或者改雲端設定，一律要由負責 state 同雲端專案嘅人執行；其他人只出 PR 同貼 plan 結果。
7. Import 成功並 apply 完之後，刪走 `imports.tf`；Scheduler、History／BigQuery 等仍未部署嘅部分另開 PR。
