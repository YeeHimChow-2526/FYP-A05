# 資料庫建立規劃

## 1. 目的與範圍

本文件規劃香港泊車語音副駕的資料庫建立與驗收工作。現有基礎設施採用
**Cloud Firestore Native mode**，因此以下所稱「資料表」是 Firestore
collection／document，而非 SQL table／row。Firestore 是 schema-on-write：
Terraform 建立資料庫及 TTL，API 第一次寫入時才會建立 collection 和文件。

本規劃只包含應用程式目前需要的 Firestore 集合。即時車位與 POI 主資料不
應為了「建表」而複製進 Firestore：即時車位來自 GCS 區域快照，POI 由本機
主檔提供。資料匯入前須有正式來源及欄位規格；不可使用虛構資料填充生產環境。

## 2. 資料庫範圍及邊界

| 資料 | 儲存位置 | 建立方式 |
|---|---|---|
| Firestore Native database `(default)` | GCP 專案 | Terraform |
| 會話 | `active_sessions` | API 按 `session_id` 寫入 |
| 暫時虛擬鎖 | `virtual_locks` | API commit 交易寫入 |
| 停車場痛點及眾包摘要 | `carpark_pain_points` | API 按 `carpark_id` 寫入 |
| 眾包回報明細 | `carpark_pain_points/{carpark_id}/reports` | API 按會話及時間桶寫入 |
| 停車場優惠主資料 | `carpark_perks_master` | 須待正式資料來源與欄位契約確認後匯入 |
| 即時車位及區域快照 | 私有 GCS bucket | 資料抓取／快照流程；不在本 Firestore 建表範圍 |
| POI 主檔 | API 本機資產 | 提供正式 POI 主檔；不在本 Firestore 建表範圍 |

手機 App 不可直接讀寫 Firestore。Firestore Security Rules 維持 deny-all；
Cloud Run 後端以執行身分及 IAM 存取資料。

## 3. Collection／文件欄位規劃

所有時間欄位使用 Firestore `Timestamp`，以 UTC 寫入。除非列為必填，否則
欄位只在有對應資料時寫入；不得以空字串或假資料代替未知值。

### 3.1 `active_sessions/{session_id}`

**文件 ID：** API request 的 `session_id`。一份文件代表一個 App 行程。

| 欄位 | Firestore 型別 | 必填時機 | 說明 |
|---|---|---|---|
| `status` | string | 每次建立／更新 | `ACTIVE`、`COMMITTED`、`PARKED_CONFIRMED` |
| `ratable` | boolean | 每次建立 | 僅司機確認泊妥後可為 `true` |
| `destination` | map | 有推薦目的地時 | 本次目的地資料 |
| `target_carpark_id` | string | commit／release 後 | 選定停車場 ID |
| `last_seen_at` | timestamp | 每次活動 | 最近一次 API 請求時間 |
| `updated_at` | timestamp | 每次更新 | 文件最後更新時間 |
| `expire_at` | timestamp | 每次建立／更新 | 未完成會話閒置期限或完成行程到期時間 |
| `completed_at` | timestamp | 泊妥確認後 | 完成時間 |
| `trip_report` | map | 提交評分後 | `trip_timestamp`、`feedback`、`submitted_at` |

**生命週期：**

- `ACTIVE` 閒置 30 分鐘到期。
- `COMMITTED` 閒置 3 小時到期。
- `PARKED_CONFIRMED` 保留至完成當日香港時間午夜。
- 到期時間由 API 讀取邏輯檢查；Firestore TTL 只負責延後的實體清理。
- 評分只接受未到期、`PARKED_CONFIRMED` 且 `ratable=true` 的會話。

### 3.2 `virtual_locks/{session_id}`

**文件 ID：** `session_id`，確保每個行程最多只有一份虛擬鎖。

| 欄位 | Firestore 型別 | 必填 | 說明 |
|---|---|---|---|
| `carpark_id` | string | 是 | 暫時佔用的停車場 ID |
| `created_at` | timestamp | 是 | 鎖建立時間；用於計算有效鎖 |
| `expire_at` | timestamp | 是 | TTL 清理時間，預設建立後 30 分鐘 |

推薦查詢以 `carpark_id` 相等及 `created_at` 時間範圍計算鎖數，不快取此數值。
commit 必須在 Firestore transaction 中檢查有效鎖數並寫入鎖與會話；泊妥
確認／release 時刪除鎖。不可依賴 TTL 及時刪除來判斷鎖是否有效。

### 3.3 `carpark_pain_points/{carpark_id}`

**文件 ID：** `carpark_id`。此文件儲存停車場目前的痛點／眾包摘要。

| 欄位 | Firestore 型別 | 必填時機 | 說明 |
|---|---|---|---|
| `recent_independent_report_sources` | integer | 眾包摘要更新時 | 最近窗口的獨立回報來源數 |
| `crowd_rank_capped` | boolean | 眾包摘要更新時 | 是否達到排序封頂門檻 |
| `crowd_warning` | boolean | 眾包摘要更新時 | 是否顯示眾包警告 |
| `crowd_penalty_points` | integer | 眾包摘要更新時 | 排序扣分 |
| `crowd_window_expires_at` | timestamp | 眾包摘要更新時 | 一小時眾包窗口的到期時間 |
| `updated_at` | timestamp | 每次更新 | 摘要最後更新時間 |

此集合未來可容納已確認的長期痛點欄位；長期黑點累計、降級及升級流程尚未
實作，需先定義資料來源、去重方式和保留期限，再另行批准 schema 變更。

#### 子集合 `carpark_pain_points/{carpark_id}/reports/{report_id}`

**文件 ID：** `{session_id}_{server_time_bucket}`；時間桶為伺服器時間的
五分鐘區間。

| 欄位 | Firestore 型別 | 必填 | 說明 |
|---|---|---|---|
| `session_id` | string | 是 | 回報所屬會話 |
| `report_type` | string | 是 | 回報種類 |
| `source_ip_hash` | string | 是 | 使用 HMAC 產生的 IP 摘要，不儲存原始 IP |
| `ip_bucket` | integer | 是 | 伺服器端五分鐘時間桶 |
| `created_at` | timestamp | 是 | 伺服器接收時間 |
| `expire_at` | timestamp | 是 | `created_at` 後七日 |
| `client_timestamp` | integer | 是 | 客戶端提供的事件時間戳 |

回報摘要依伺服器接收時間計算最近一小時的獨立來源數。眾包回報只作提示與
排序調整，不用作排除停車場。

### 3.4 `carpark_perks_master/{carpark_id}`

優惠主資料以停車場 ID 作文件 ID，由推薦 API 讀取。官方資料及離線來源的
schema、欄位優先次序、衝突仲裁和匯入程序尚未在 repository 定義。**建立
匯入流程前必須先取得並確認來源契約；本階段不猜測欄位、不建立假文件。**

## 4. Index、TTL 及安全設定

| 設定 | 目標 | 建立方式 |
|---|---|---|
| Composite index | `virtual_locks`: `carpark_id ASC`, `created_at ASC` | `firestore.indexes.json`，Firebase CLI 部署 |
| TTL | `active_sessions.expire_at` | Terraform `google_firestore_field` |
| TTL | `virtual_locks.expire_at` | Terraform `google_firestore_field` |
| TTL | `reports.expire_at`（collection group） | Terraform `google_firestore_field` |
| Security Rules | 所有 client read/write deny | `firestore.rules`，Firebase CLI 部署 |
| Server IAM | Cloud Run 執行身分 `roles/datastore.user` | Terraform |

上線前應確認 index 已完成建立、TTL policy 已啟用、Firebase Rules 已部署。
TTL 清理有延遲，API 必須始終自行驗證到期時間。

## 5. 建立與上線步驟

1. **確認環境：** 確認 GCP project、帳單、區域、Firebase 註冊狀態及部署權限。
2. **檢視變更：** 執行 `terraform init` 和 `terraform plan`，核對 Firestore
   database、三項 TTL、API 及 IAM 設定；先估算費用。
3. **建立底層資料庫：** 依 [deploy.txt](./deploy.txt) 的 foundation 步驟執行
   Terraform。Terraform 建立 `(default)` Native mode database 和 TTL；這不會
   自動建立 collections 或加入業務資料。
4. **部署索引及規則：** 將 GCP project 註冊為 Firebase 專案（如尚未註冊），
   再執行：

   ```sh
   firebase deploy --only firestore:rules,firestore:indexes \
     --project YOUR_GCP_PROJECT_ID
   ```

5. **確認 API 權限：** Cloud Run 使用 Terraform 建立的 service account；
   開發環境使用 Application Default Credentials。不得把 service-account
   key 或其他憑證提交至 repository。
6. **建立首批文件：** 用受控的 API 測試流程觸發 session、lock 和 crowd report
   寫入，讓 Firestore 按 schema-on-write 建立 collection。不要手動匯入虛構
   停車、POI 或優惠資料。
7. **匯入非 MVP 主資料：** 在優惠資料來源 schema 確認後另訂匯入／更新工作；
   在快照與 POI 主檔就緒前，不宣稱推薦資料已完整。

## 6. 驗收清單

- [ ] GCP 中存在 `(default)` Firestore Native mode database，位置符合設定。
- [ ] `virtual_locks` composite index 狀態為 `Enabled`。
- [ ] 三項 `expire_at` TTL policy 已啟用。
- [ ] Firebase Rules 部署後，未授權 client 無法讀寫任何文件。
- [ ] Cloud Run service account 可透過後端 API 使用 Firestore，App 不直連。
- [ ] 建議／回報 API 可建立正確欄位型別的 session 與 pain-point 文件。
- [ ] commit 可建立 lock，並以 transaction 檢查剩餘空位；release／確認可刪除 lock。
- [ ] 過期 session 不可評分；評分只接受司機已確認的有效行程。
- [ ] crowd report 不含原始 IP，七日 TTL 及一小時摘要窗口符合規格。
- [ ] 無正式資料來源時，不建立假車位、假 POI 或假優惠文件。

## 7. 不在本次建置範圍

- DATA.GOV.HK 資料抓取、清洗及 GCS snapshot ingestion worker。
- Firestore 優惠主資料的來源 schema、官方／離線仲裁及匯入工作。
- POI 主檔供應與名稱解析資料。
- 長期痛點／慣性霸位黑點的累計、降級和排程流程。
- BigQuery 冷分析資料管線。
