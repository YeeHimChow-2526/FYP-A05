# Terraform 同雲端現況對齊（新確認版 Terraform）

這個分支由 `Terraform`（`276c07e`，DB 設定：PITR／刪除保護／ABANDON／每日備份）出發，
疊上 PR #5 的後端 code（`15aab20`），再補上「雲端已經存在、但 Terraform 未有寫」的設定。
原本的 `Terraform` 分支冇改動。**呢個分支冇可能喺呢部機證明 plan 結果**；目標係全新 state 之下，第一次 `terraform plan` 只顯示 import（0 add／0 change／0 destroy），見第 5 節。

## 1. 呢個分支加咗啲乜

| 檔案 | 內容 |
|---|---|
| `server/**`、`DATABASE.md`、`scripts/` | 同 PR #5（`15aab20`）一致；PR #5 已經 rebase 喺 `276c07e` 之上，所以係 fast-forward，冇衝突 |
| `cloud_run.tf` | Cloud Run 對齊現況：`max_instance_request_concurrency = 160`、`cpu_idle = false`（CPU always-on）、`timeout = 300s`、三個 env flag、image 用 digest；`ignore_changes = [client, client_version]` |
| `variables.tf` / `locals.tf` | `backend_image` 變成可選（預設 `null`）；預設用 `backend_image_digest`（現況 digest）拼出 `…/hkparking/server@sha256:…`；其餘 live 值做成有預設嘅變數 |
| `firestore.tf` / `variables.tf` | Firestore 保護設定預設改為現況（PITR 關、刪除保護關、每日備份關；`concurrency_mode = PESSIMISTIC`、`app_engine_integration_mode = DISABLED`）；備份排程用 `count` 控制 |
| `cloud_run.tf` | 另加 `google_storage_bucket_iam_member.snapshot_runtime_writer_live`（`roles/storage.objectUser`，雲端已存在，on-demand refresh 需要） |
| `firestore.tf` + `imports.tf` | 新增 `google_firestore_index.virtual_locks_carpark_created`，用 `import` block 接入現有 index，**唔會 create** |
| `provider.tf` | `required_version` 改為 `>= 1.7.0`（import 用變數同 `for_each`）；新增 `backend "gcs"`（新 bucket `total-velocity-507606-q2-tfstate`，prefix `hkparking`） |
| `imports.tf` | 每個雲端已存在嘅資源一個 `import` block（共 28 個，另有 3 個 TTL 條件式），全新 state 接管用 |
| `apis.tf` / `variables.tf` | API 清單改為變數 `project_services`（只可列已啟用嘅 API）；`disable_on_destroy = false` |
| `firestore.tf` | TTL 政策改為 `google_firestore_field.ttl`（`for_each`），預設**唔建立**（`firestore_enable_ttl = false`） |
| `cloud_run.tf`（traffic） | 加 `traffic` block，同現況一模一樣：100% 釘住 `backend-ai-service-00008-vef`，加 tag `cand`；env 次序同現況一致；`ignore_changes` 加 template labels／annotations／execution_environment |
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

## 3. 已驗證（2026-11-11 約 22:54 香港時間，由專案負責人喺 Cloud Shell 跑唯讀指令取得）

- Firestore index `CICAgOjXh4EK`：collection group `virtual_locks`、COLLECTION scope、READY、欄位 `carpark_id`、`created_at`（再加隱含 `__name__`）。
  Terraform 內嘅欄位次序同方向（`carpark_id` ASC、`created_at` ASC）同呢個一致，`imports.tf` 保持 import，唔 create。
- Firestore `(default)` database：STANDARD edition、`FIRESTORE_NATIVE`、`asia-east2`、`concurrencyMode = PESSIMISTIC`、
  `appEngineIntegrationMode = DISABLED`、`deleteProtectionState = DELETE_PROTECTION_DISABLED`、
  `pointInTimeRecoveryEnablement = POINT_IN_TIME_RECOVERY_DISABLED`、`versionRetentionPeriod = 3600s`。
  Terraform 預設已改成同呢啲一致。provider 5.45.2 冇 `database_edition` 參數，所以 edition 冇寫（STANDARD 係預設）。
- Bucket `<project>-state-snapshots`（ASIA-EAST2）：legacy project 綁定；`serverless-app-sa` 同時有 `roles/storage.objectViewer` 同 `roles/storage.objectUser`
  （兩個都已寫入 Terraform）；部署身份 `grokbot-deploy` 有 `roles/storage.objectAdmin`——呢個係部署身份嘅授權，**刻意冇寫入 Terraform**。
- Cloud Scheduler（asia-east2）：0 個 job。Terraform 亦冇 scheduler，兩邊一致。

### 3a. 由專案負責人 2026-11-11 約 22:59 補充（Cloud Shell 檢查）

- **冇任何 Terraform state**：Cloud Shell home 冇；bucket `<project>-state-snapshots` 同 `<project>_cloudbuild` 都冇。決定：state 當全新處理。
- Firestore 備份排程：0 個。
- `serverless-app-sa` 專案層級 role 只有 `roles/aiplatform.user` 同 `roles/datastore.user`（兩個都已寫入 Terraform 並會 import）。

## 3b. 仲未驗證（要用 5b 嘅確認指令由負責人確認）

- 專案已啟用嘅 API 清單（`project_services` 要同實況一致；import 一個未啟用嘅 API 會失敗，亦唔可以靠 Terraform 去啟用新 API）。
- 兩個 Secret（`hkparking-internal-api-key`、`hkparking-crowd-ip-hash-secret`）嘅 replication 設定（Terraform 寫 `auto`；如果雲端係 user-managed 會變成 replace），以及兩個 secret 上 `secretAccessor` 綁定（由「服務正常運行」推斷存在）。
- Bucket 嘅 uniform access、public access prevention、storage class、versioning（今次輸出冇包含呢幾項；Terraform 寫 uniform＝true、PAP＝enforced）。
- Firestore TTL 政策（`active_sessions`／`virtual_locks`／`reports` 嘅 `expire_at`）：未確認存在，所以預設唔管理（見第 6 節）。
- Cloud Run v2 API 視角嘅 template（labels／annotations／ports／probe）：用 `ignore_changes` 避開 metadata 雜訊；其餘要睇真 plan。
- `serverless-app-sa` 嘅 display name／description、Artifact Registry 嘅其他細節（labels、cleanup policy）：只核對咗 description／format／mode。
- 真正嘅 `terraform plan` 結果。
- Secret Manager 內容（Terraform 只管 `latest` 引用，唔管內容）。
- repo 入面有被追蹤嘅 `.terraform/providers/…`（約 99 MB provider binary，雖然 `.gitignore` 寫咗 `.terraform/`）；呢個分支冇處理，建議另開改動 `git rm --cached`。

## 3c. 開 PITR／刪除保護／備份係獨立嘅團隊決定（有成本）

原本 `Terraform` 分支嘅預設係全部開；雲端現況係全部關。為免 plan 無端改動雲端，本分支預設跟現況（全部 `false`）。
要開嘅話，要團隊另外決定、睇過成本，再喺 `terraform.tfvars` 設定：

| 變數 | 現況預設 | 作用 |
|---|---|---|
| `firestore_enable_point_in_time_recovery` | `false` | 7 日 PITR（有額外儲存成本） |
| `firestore_enable_delete_protection` | `false` | 開刪除保護 |
| `firestore_enable_daily_backups` | `false` | 建立每日備份排程（`count`；保留日數 `firestore_backup_retention_days`，1 至 98） |

`deletion_policy = ABANDON` 一直保留（只影響 Terraform destroy，唔會刪除雲端 database）。

## 4. 每個資源同 import ID

| 資源 | import ID（`<P>`＝project id） | 狀態 |
|---|---|---|
| `google_project_service.required_apis["x"]`（13 個） | `<P>/<service>` | 推斷，待確認 |
| `google_service_account.app_sa` | `projects/<P>/serviceAccounts/serverless-app-sa@<P>.iam.gserviceaccount.com` | 已驗證 |
| `google_project_iam_member.firestore_user` | `<P> roles/datastore.user serviceAccount:<SA>` | 已驗證 |
| `google_project_iam_member.vertex_ai_user` | `<P> roles/aiplatform.user serviceAccount:<SA>` | 已驗證 |
| `google_secret_manager_secret.internal_api_key` / `.crowd_ip_hash_secret` | `projects/<P>/secrets/<secret_id>` | 推斷，待確認 |
| `google_secret_manager_secret_iam_member.api_key_accessor` / `.crowd_hash_key_accessor` | `projects/<P>/secrets/<secret_id> roles/secretmanager.secretAccessor serviceAccount:<SA>` | 推斷，待確認 |
| `google_storage_bucket.state_snapshots` | `<P>/<bucket>` | 存在已驗證；屬性待確認 |
| `google_storage_bucket_iam_member.snapshot_reader` | `b/<bucket> roles/storage.objectViewer serviceAccount:<SA>` | 已驗證 |
| `google_storage_bucket_iam_member.snapshot_runtime_writer_live` | `b/<bucket> roles/storage.objectUser serviceAccount:<SA>` | 已驗證 |
| `google_artifact_registry_repository.api` | `projects/<P>/locations/asia-east2/repositories/hkparking` | 已驗證 |
| `google_cloud_run_v2_service.backend` | `projects/<P>/locations/asia-east2/services/backend-ai-service` | 已驗證 |
| `google_cloud_run_v2_service_iam_member.backend_public_invoker` | `projects/<P>/locations/asia-east2/services/backend-ai-service roles/run.invoker allUsers` | 已驗證 |
| `google_firestore_database.database` | `projects/<P>/databases/(default)` | 已驗證 |
| `google_firestore_index.virtual_locks_carpark_created` | `projects/<P>/databases/(default)/collectionGroups/virtual_locks/indexes/CICAgOjXh4EK` | 已驗證 |
| `google_firestore_field.ttl["…"]`（3 個，條件式） | `projects/<P>/databases/(default)/collectionGroups/<collection>/fields/expire_at` | 只喺 `firestore_enable_ttl = true` 時 |

期望 import 數量：13 + 1 + 2 + 2 + 2 + 1 + 2 + 1 + 1 + 1 + 1 + 1 = **28**。

**唔會預設建立／已閘住嘅資源：**
- `google_firestore_backup_schedule.daily`：`count = 0`（`firestore_enable_daily_backups = false`；雲端 0 個排程）。
- `google_firestore_field.ttl`：`firestore_enable_ttl = false` 時冇資源、冇 import（建立 TTL 會令雲端開始刪除過期文件）。
- 唔喺 Terraform 度：部署身份 `grokbot-deploy` 嘅 `objectAdmin`。
- 唯一刻意嘅新建：remote state bucket（見 5c），喺 Terraform 之外用 gcloud 建立。

**Cloud Run traffic 嘅選擇：** 照抄現況——兩個 `traffic` block，100% 釘住 `backend-ai-service-00008-vef`，加 tag `cand` 喺同一 revision。原因：用 `LATEST` 會令 plan 顯示 traffic 改變；而且釘住嘅話，即使 template 有細微差異，apply 都只會多一個 0% 嘅新 revision，唔會搶走流量。代價：之後要用 Terraform 部署新 image 時，要先將 `backend_traffic_revision` 改成新 revision 名（或者改為 `LATEST`），否則新 revision 唔會收到流量。

## 5. 全新 state 接管流程

重點：**呢個流程嘅 apply 只係寫入 state。**前提係 plan 顯示 `0 to add, 0 to change, 0 to destroy`；一有 add／change／destroy 就停，唔好 apply。

### 5a. 流程
1. 5b：用唯讀指令確認未驗證嘅項目；如有出入，先改 code 或 `terraform.tfvars`（例如 `project_services` 只列已啟用嘅 API）。
2. 5c：建立 state bucket（唯一一個刻意嘅新建，喺 Terraform 外面做）。
3. 5c：clone 呢個分支（push 之後）、寫 `terraform.tfvars`、`terraform init`、`terraform plan`。
4. 讀 plan（5d）。目標：`Plan: 28 to import, 0 to add, 0 to change, 0 to destroy.`
5. 達標先可以 `terraform apply`（只會寫 state，唔會改雲端）。
6. Apply 成功之後，開 follow-up PR 刪除 `imports.tf`（資源已留喺 state）。
7. 之後先用 `terraform plan` 做正常變更；開 PITR／刪除保護／備份、TTL、Scheduler 等都係獨立嘅團隊決定。

### 5b. 確認指令（只讀）

```bash
export PROJECT_ID=total-velocity-507606-q2
export REGION=asia-east2
export BUCKET=total-velocity-507606-q2-state-snapshots
gcloud config set project "$PROJECT_ID"

echo "== 1. 已啟用嘅 API：下面列出 Terraform 想管但未啟用嘅（空白＝全部已啟用）=="
for s in serviceusage cloudresourcemanager iam run firestore firebaserules aiplatform cloudscheduler cloudbuild artifactregistry compute secretmanager storage; do echo "$s.googleapis.com"; done | sort > /tmp/want.txt
gcloud services list --enabled --format="value(config.name)" | sort > /tmp/have.txt
comm -23 /tmp/want.txt /tmp/have.txt

echo "== 2. Firestore TTL 政策 =="
gcloud firestore fields ttls list --database="(default)"

echo "== 3. Secret（只顯示設定，唔顯示內容）=="
for s in hkparking-internal-api-key hkparking-crowd-ip-hash-secret; do
  gcloud secrets describe "$s" --format="yaml(name,replication,labels,annotations)"
  gcloud secrets get-iam-policy "$s"
done

echo "== 4. Service account =="
gcloud iam service-accounts list --format="table(email,displayName,description,disabled)"

echo "== 5. Artifact Registry =="
gcloud artifacts repositories describe hkparking --location="$REGION" --format=yaml

echo "== 6. Bucket 全部設定 =="
gcloud storage buckets describe "gs://$BUCKET" --format=json

echo "== 7. Cloud Run IAM =="
gcloud run services get-iam-policy backend-ai-service --region="$REGION"

echo "== 8.（可選）Cloud Run v2 API 視角，睇 template 同 traffic；只有 secret 名，冇 secret 值 =="
curl -s -H "Authorization: Bearer $(gcloud auth print-access-token)" \
  "https://run.googleapis.com/v2/projects/$PROJECT_ID/locations/$REGION/services/backend-ai-service"
```

結果點用：
- 1 有輸出 → 喺 `terraform.tfvars` 設 `project_services = [...]`，唔好包括未啟用嘅 API。
- 2 有 TTL 政策 → 之後先考慮 `firestore_enable_ttl = true`（預設 false，plan 唔會管佢）。
- 3 replication 唔係 automatic → 要改 `google_secret_manager_secret` 同 live 一致；secret IAM 冇 `secretAccessor` → 暫時移除該 import／資源，先決定。
- 6 嘅 `uniformBucketLevelAccess`、`publicAccessPrevention`、`defaultStorageClass`、`versioning` 同 code 唔同 → 改 `cloud_run.tf` 內 bucket 資源嘅值去跟現況。

### 5c. 建立 state bucket → clone → init → plan

**呢個係唯一一個刻意嘅新建（雲端新增一個 bucket），其餘全部係唯讀。**

```bash
export PROJECT_ID=total-velocity-507606-q2
export REGION=asia-east2
export TFSTATE_BUCKET=total-velocity-507606-q2-tfstate

# ===== 一次性：建立 Terraform state bucket（唯一刻意嘅新建）=====
gcloud storage buckets create "gs://$TFSTATE_BUCKET" \
  --project="$PROJECT_ID" --location="$REGION" \
  --uniform-bucket-level-access --public-access-prevention
gcloud storage buckets update "gs://$TFSTATE_BUCKET" --versioning

# ===== clone（分支 push 之後）、tfvars、init、plan =====
terraform version     # 要 >= 1.7.0
git clone --branch '新確認版Terraform' https://github.com/YeeHimChow-2526/FYP-A05.git tf-confirmed
cd tf-confirmed

cat > terraform.tfvars <<'EOF'
project_id                 = "total-velocity-507606-q2"
region                     = "asia-east2"
state_snapshot_bucket_name = "total-velocity-507606-q2-state-snapshots"
# 如果 5b 第 1 項顯示有 API 未啟用，加：project_services = ["...只列已啟用嘅..."]
EOF

terraform init        # 使用 GCS backend（bucket 要先建好）
terraform plan        # 只 plan，唔好 apply
```

### 5d. 點睇 plan

- 目標：`Plan: 28 to import, 0 to add, 0 to change, 0 to destroy.`
- **任何 add／destroy／replace（`must be replaced`／`forces replacement`）→ 停低，唔好 apply。**「add」代表 Terraform 以為雲端冇呢個資源（import ID 對唔上或資源其實唔存在），apply 會真係建立。貼 plan 輸出出嚟睇。
- 如果 import 本身報錯（`Cannot import non-existent remote object`）→ 該資源未必存在或 ID 有錯，唔好硬做；用 5b 確認，再修改。
- **有 change**：先睇係邊個欄位。已知唯一可以接受嘅係 `google_project_service` 上 `disable_on_destroy` 呢類 state-only 欄位（provider 嘅 Update 係 no-op，唔會呼叫任何 API）。其他（例如 Cloud Run `template`、bucket 設定、secret replication）要逐項處理：改 code 去跟雲端現況，再 plan，直到 0 change。
- 如果 plan 顯示幾乎全部 `create`、冇 import，通常係 `imports.tf` 冇被載入或者 backend 指錯 prefix／bucket，唔好 apply。
- 達標之後 `terraform apply` 只會做 import（寫 state），唔會改雲端；之後開 follow-up PR 刪除 `imports.tf`。

## 6. 之後嘅獨立決定（唔喺呢個流程入面做）

- 開 TTL：確認 5b 第 2 項之後，設 `firestore_enable_ttl = true`；plan 要係 3 個 import、冇 add。如果雲端冇 TTL，就唔好開（開咗會 create，即係改動雲端）。
- 開 PITR／刪除保護／備份：見 3c。
- Cloud Scheduler／Cloud Run Job（ingest）、History／BigQuery：雲端未有，另開 PR。
- 用 Terraform 部署新 image：先處理 `backend_traffic_revision`（見第 4 節）。
