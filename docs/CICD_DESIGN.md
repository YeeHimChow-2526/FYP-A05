# CI/CD 第一版設計

呢份文件係官方統一分支 `main` 之上，第一版持續整合（CI）同持續交付（CD）嘅可開工設計。目標係：同學由 `main` 開分支改嘢時，合併前先自動檢查；真正改雲端要再經人手確認。把程式推上 GitHub **唔會**自動 `terraform apply`。

倉庫：https://github.com/YeeHimChow-2526/FYP-A05

示範用預覽路徑、同完整模擬器／真機驗收，唔納入第一版必跑項目，之後再加。

## 1. 工作方式

- 所有新工作由 `main` 開出新分支，再用 pull request 合併返 `main`。
- 舊分支 `完整app` 同 `feature/scheduler` 只作歷史參考，唔好再用舊副本直接改或者 apply。
- `main` 要開啟分支保護：必須經 pull request、下列必跑檢查要通過，先至可以合併。呢項要倉庫擁有者設定；只有推送權限唔夠改保護規則。
- 密鑰同 `local.properties` 唔入 Git，亦唔放入 GitHub Actions 密文去跑 App 單元測試。應用測試用模擬資料；Secret Manager 仍然係密鑰嘅唯一正式來源。

## 2. 改到邊度就檢查邊度

唔需要每次跑晒所有檢查，但官方倉庫要有能力覆蓋 App 同雲端。

| 改動範圍 | 必跑 |
|---|---|
| App：`lib/`、`test/`、`android/`、`pubspec.yaml`、`pubspec.lock`、`analysis_options.yaml`、以及 App 資源／工具 | `flutter analyze`、`flutter test` |
| 伺服器：`server/`、`server/requirements.txt`、`server/requirements-dev.txt` | `pytest`（見第 3 節） |
| 雲端設定：`*.tf`、`.terraform.lock.hcl`、`ci/terraform-ci.tfvars`、`terraform.tfvars.example`、`variables.tf` 等 Terraform 檔 | `terraform fmt -check`、`terraform validate`、只讀 `terraform plan` |
| 同時改多個範圍，或者改到 `.github/workflows/` | 上述受影響嘅檢查一齊跑 |
| 只改說明文件（例如 `docs/`、根目錄 Markdown），而且冇改到上面嘅路徑 | 唔跑應用測試，亦唔向 Google Cloud 要計劃 |

第一版唔跑模擬器、真機驗收，亦唔喺 CI 編譯發佈用 APK。Android `./gradlew test` 留待跑手環境穩定之後再加，唔阻擋第一版落地。

## 3. Pull request 上嘅檢查

### 應用同伺服器

- Flutter：`flutter analyze` 同倉庫現有嘅 `flutter test`。任一失敗，拉請求唔好合併。
- 伺服器：用 `server/requirements-dev.txt` 跑 `pytest`。第一版略過要 Firestore 模擬器嘅測試（例如 `server/tests/test_firestore_emulator.py`），避免 CI 因未啟動模擬器而誤紅。其餘現有單元／整合測試要通過。

### Terraform 只讀計劃

計劃必須用同而家雲端一致嘅**非密鑰**參數，檔案為 `ci/terraform-ci.tfvars`（入庫，唔放密鑰）。唔好用倉庫預設值跑計劃：預設 `ingestion_enabled = false`，計劃會提出拆掉現況嘅資料擷取工作、排程同相關權限，檢查會長期失敗，亦容易誤導。

2026-11-28 對現況核對過、可以用作起始內容嘅參數係：

```hcl
project_id                 = "total-velocity-507606-q2"
region                     = "asia-east2"
state_snapshot_bucket_name = "total-velocity-507606-q2-state-snapshots"
ingestion_enabled          = true
ingestion_image            = "asia-east2-docker.pkg.dev/total-velocity-507606-q2/hkparking/server@sha256:17358f8dea2553a08c21551240fac40da0351d61b2e54ac253a61bb375c03a42"
backend_secret_reload_ts  = "1795798559"
```

有人**故意**改咗現況（例如換咗擷取映像、或者改咗 Cloud Run 範本上嘅 `SECRET_RELOAD_TS`），要同一張 pull request 或者隨即更新 `ci/terraform-ci.tfvars`，並喺說明寫明日期同原因。呢份檔唔係可以盲目前 apply 嘅許可；每次正式 apply 前都要再跑一次計劃確認。

後端服務映像仍然用設定入面已釘嘅現況 digest。未將該映像重新放入 Artifact Registry 之前，唔好喺計劃入面改服務映像、流量修訂，或者為咗換密鑰而改 `backend_secret_reload_ts`，否則計劃會嘗試開新修訂並失敗。

### 計劃要直接失敗嘅情況

出現以下任一項，呢張 pull request 嘅雲端檢查就應該失敗：

- 計劃要銷毀或重建兩條 Secret Manager 密鑰外殼、或者 `hkparking` 映像庫
- 計劃要銷毀資料擷取工作、兩個排程、擷取／排程服務帳戶，或擷取寫入儲存桶嘅權限（通常代表漏咗 `ingestion_enabled = true` 或者擷取映像釘錯）
- 出現未喺拉請求說明清楚嘅刪除
- 計劃會改動 Cloud Run 正式服務嘅映像、流量修訂，或會強迫開新修訂（喺正式映像未回到映像庫之前）
- 今次改咗 Terraform／雲端設定，但計劃因為權限或設定問題跑唔成（失敗關閉，唔可以當跳過）

計劃結果係「冇任何改動」，或者只有已說明、而且唔屬於上面禁項嘅改動，先至可以算通過。計劃全文要留喺拉請求嘅檢查紀錄，方便審查。

CI **唔會**執行 `terraform apply`。

## 4. 正式 apply（CD）

- 只可以由已經合併進 `main` 嘅版本觸發，例如 GitHub Environment（建議名稱 `gcp-apply`）配 `workflow_dispatch`。
- 要由團隊事先指定嘅少數確認人喺 GitHub 批准。建議至少兩名確認人，而且最好唔好由發起呢次更新嘅同一個人單獨批准自己嘅 apply。
- 同一時間只可以有一個 apply。批准前要再跑一次 `terraform plan`，用同一份 `ci/terraform-ci.tfvars`；得同先前審查過嘅計劃一致（仍然係「冇任何改動」，或者只係今次已同意嘅改動），先至可以 apply。
- 呢條路徑起好之後，正式改雲要走呢個關口，唔好再用個人電腦作為預設 apply 方式。緊急情況先至例外，而且要保留當次計劃同事後紀錄。
- 已知、有意保留嘅差距（擷取排程時段、`raw/` 未有生命週期、原文欄位精簡）見 [`UNIFIED_BASELINE.md`](UNIFIED_BASELINE.md)。第一版 CD 唔會順便改呢啲項目。

## 5. AI 審查（第一版稍後加，規則先定）

AI 喺 Terraform 計劃出嚟之後、合併進 `main` 之前閱讀拉請求同計劃。第一版以拉請求上嘅審查意見為主：

- 要拆資料擷取、郁到密鑰外殼或映像庫、或者會強迫開新嘅正式服務修訂：可以阻擋合併
- 用字、風格、同非安全嘅建議：只出意見，唔卡住合併

Apply 之後如果再做一次檢查，只係事後預警，唔能自動撤回已經套用嘅改動。示範用、暫時接受但未完成嘅紀錄，以拉請求為準。

## 6. 一次性設定（實作前要備好）

1. 倉庫擁有者開啟 `main` 保護：要 pull request、要指定 CI 檢查通過、唔好直接推上 `main`。
2. 喺 GitHub 建立 `gcp-apply` Environment，寫入指定確認人。
3. 用 Workload Identity Federation（GitHub OIDC 連到 Google Cloud），唔好把服務帳戶金鑰存進 GitHub。CI 計劃用只讀／可以跑 plan 嘅權限；apply 用另外一組更窄、只畀呢個 Environment 用嘅權限。
4. 落地 `ci/terraform-ci.tfvars`（內容見第 3 節）。`.gitignore` 仍然忽略一般嘅 `*.tfvars`，但會用例外規則把呢份非密鑰檔入庫。本地真正嘅 `terraform.tfvars` 同密鑰檔繼續忽略。

## 7. 建議實作次序

1. 把呢份設計同 `ci/terraform-ci.tfvars` 併入 `main`。
2. 完成第 6 節嘅保護、Environment 同雲端身份聯合。
3. 新增 `.github/workflows/ci.yml`：按第 2、3 節喺 pull request 跑測試同只讀計劃，唔 apply。
4. 新增 `.github/workflows/apply.yml`：只喺 `main`、經 `gcp-apply` 確認後，先再計劃、確認一致，先至 apply。
5. 再加 AI 審查。
6. 之後先設計示範用預覽路徑；正式 Cloud Run、密鑰同映像庫唔好被示範路徑改到。

實作時如果同一張 pull request 既要改正式雲端設定，又只係想試嘢，應該拆開，唔好混進一次正式 apply。
