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
| `firestore.tf` / `variables.tf` | Firestore 保護設定預設改為現況（PITR 關、刪除保護關、每日備份關；`concurrency_mode = PESSIMISTIC`、`app_engine_integration_mode = DISABLED`）；備份排程用 `count` 控制 |
| `cloud_run.tf` | 另加 `google_storage_bucket_iam_member.snapshot_runtime_writer_live`（`roles/storage.objectUser`，雲端已存在，on-demand refresh 需要） |
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

## 3b. 仲未驗證

- Terraform state 存唔存在、入面有啲乜（repo 冇 backend block，state 如有就係某部機嘅本地 `terraform.tfstate`）。
- 真正嘅 `terraform plan` 結果（「No changes」未證實）。
- Cloud Run 流量：live 固定喺 `…-00008-vef` 並有 `cand` tag；Terraform 冇 `traffic` block，apply 後點處理要睇 plan。
- `serverless-app-sa` 嘅專案層級 IAM role（讀取被拒）。
- 有冇 Firestore 備份排程（`gcloud firestore backups schedules list` 未跑）；Terraform 預設唔建立。
- Startup probe（TCP 240s）同 service annotation `hkparking.align/secret-reload`：只當預設／現值處理，冇逐項對 provider 行為驗證。
- Secret Manager 內容同 version 列表；Terraform 只管 `latest` 引用。
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

## 4. 安全嘅下一步（團隊規則）

1. 準備真 `terraform.tfvars`（唔好 commit）同現有 state（backend），然後 `terraform init`。
2. Index id `CICAgOjXh4EK` 已於 2026-11-11 驗證；`imports.tf` 保持 import。另外 `snapshot_runtime_writer_live` 亦係雲端已存在嘅綁定，若 state 冇就要 import 而唔係 create。
3. 跑 `terraform plan`，預期**冇變動**（import 會顯示 1 項 import）。有任何差異，逐項改返 code 或者確認係雲端要跟，**唔好為咗收平 plan 而 apply**。
4. 如果冇現成 state：唔好 `apply`。先決定 state 位置，再逐個 resource `terraform import`，否則 Terraform 會試圖建立已存在嘅資源。
5. 手動 import 嘅等價指令（唔用 `imports.tf` 時）：
   ```bash
   terraform import google_firestore_index.virtual_locks_carpark_created \
     'projects/<PROJECT_ID>/databases/(default)/collectionGroups/virtual_locks/indexes/CICAgOjXh4EK'
   ```
6. 任何 `apply`、`import` 寫入 state、或者改雲端設定，一律要由負責 state 同雲端專案嘅人執行；其他人只出 PR 同貼 plan 結果。
7. Import 成功並 apply 完之後，刪走 `imports.tf`；Scheduler、History／BigQuery 等仍未部署嘅部分另開 PR。

## 5. 下一步：喺 Cloud Shell 跑嘅唯讀指令（只讀，唔會改雲端；冇 secret）

### 5a. 先搵有冇 Terraform state

```bash
export PROJECT_ID=total-velocity-507606-q2
export REGION=asia-east2
export BUCKET=total-velocity-507606-q2-state-snapshots
gcloud config set project "$PROJECT_ID"

echo "== A. 本機有冇 state（Cloud Shell home；其他電腦要自己搵）=="
find ~ -maxdepth 6 \( -name 'terraform.tfstate' -o -name 'terraform.tfstate.backup' -o -name '*.tfstate' \) 2>/dev/null

echo "== B. 專案有啲乜 bucket =="
gcloud storage buckets list --project="$PROJECT_ID" --format="table(name,location)"

echo "== C. 有冇 tfstate 檔喺 bucket（逐個 bucket 搵，只列名）=="
for b in $(gcloud storage buckets list --project="$PROJECT_ID" --format="value(name)"); do
  echo "-- gs://$b"; gcloud storage ls -r "gs://$b/**" 2>/dev/null | grep -i -E 'tfstate|terraform' | head
done

echo "== D. Firestore 備份排程 =="
gcloud firestore backups schedules list --database="(default)" --project="$PROJECT_ID"

echo "== E. 運行 SA 嘅專案層級 role =="
gcloud projects get-iam-policy "$PROJECT_ID" \
  --flatten="bindings[].members" \
  --filter="bindings.members:serverless-app-sa@$PROJECT_ID.iam.gserviceaccount.com" \
  --format="value(bindings.role)"
```

### 5b. 之後（分支 push 之後）跑 plan，唔好 apply

```bash
git clone --branch '新確認版Terraform' https://github.com/YeeHimChow-2526/FYP-A05.git tf-confirmed
cd tf-confirmed

cat > terraform.tfvars <<'EOF'
project_id                 = "total-velocity-507606-q2"
region                     = "asia-east2"
state_snapshot_bucket_name = "total-velocity-507606-q2-state-snapshots"
EOF

# 如果 5a 搵到現有 state：複製（唔好搬）呢個檔到呢個資料夾先，例如
# cp /path/to/old/terraform.tfstate .

terraform init
terraform plan        # 只睇，唔好加 -out 之後 apply；唔好跑 terraform apply
```

點睇結果：

- **Plan 顯示差不多所有資源都係 `create`**（API、service account、secret、bucket、Cloud Run、Artifact Registry 等）＝ **冇 state**。呢個情況**絕對唔好 apply**（會嘗試建立已存在嘅資源，失敗或者建立重複）。要先決定 state 擺邊，再逐個 `terraform import`。
- **有 state**：預期只有 `1 to import`（Firestore index）同 `snapshot_runtime_writer_live` 如 state 未有就係 create/import；其餘應該 `No changes`。Cloud Run 如果顯示 in-place 更新，睇清楚係邊個欄位（traffic、template、annotations）先決定，唔好為咗收平 plan 而 apply。
- 任何 `destroy` 或 `replace` 都係警號，先停低、貼出 plan 輸出俾團隊睇。
