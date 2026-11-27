雖然呢個系統用嘅係 NoSQL（Cloud Firestore）加埋雲端儲存（GCS）同大數據庫（BigQuery），無傳統 SQL 嗰種嚴格嘅 Foreign Key（外鍵），但你可以將每一個 Collection（集合）或檔案當成一張 Table，佢哋之間係靠 session_id（用家行程 ID） 同埋 carpark_id（停車場 ID） 呢兩個核心欄位連繫埋一齊。
以下幫你整理出系統入面所有 Table 清單，以及佢哋之間嘅關聯（Relationships）：
1. 所有「Table」總覽（按儲存位置分類）
| Table 名稱 (Collection / 路徑) | 儲存位置 | 狀態 | 角色定位 (相當於 SQL 嘅咩表) | 主鍵 (Primary Key) |
|---|---|---|---|---|
| active_sessions | Firestore | 運作中 | 用家行程主表 | session_id |
| virtual_locks | Firestore | 運作中 | 車位預留／鎖位表 | session_id |
| carpark_pain_points | Firestore | 運作中 | 停車場伏位／扣分總表 | carpark_id |
| .../reports (子集合) | Firestore | 運作中 | 用家即時報料明細表 | {session_id}_{time_bucket} |
| carpark_perks_master | Firestore | 空表 (未有數據) | 停車場優惠主表 | carpark_id |
| carpark_capacity | Firestore | 未啟用 (Phase 3c) | 停車場容量與租約總帳 | carpark_id |
| regions/*/snapshot.json | GCS Bucket | 運作中 | 運輸署即時空位快照 (唯讀來源) | carpark_id (JSON 內部) |
| manifest.json | GCS Bucket | 未啟用 (Phase 4a) | 快照更新鎖與版本目錄 | 單一檔案 |
| grid_occupancy_history | BigQuery | 未啟用 (Phase 4b) | 歷史車位大數據分析表 | (region, grid_id, slot_start, record_type) |
2. Table 之間嘅關聯圖（Entity-Relationship 概念圖）
你可以透過以下嘅架構圖，睇清楚 session_id 同 carpark_id 點樣將所有表串連埋一齊：
                       [ GCS: snapshot.json ] (提供即時總空位數)
                                  │
                                  ▼ (透過 carpark_id 對照)
┌────────────────────┐ 1:1  ┌────────────────────┐ N:1  ┌────────────────────────┐
│  active_sessions   │◄────►│   virtual_locks    │─────►│   carpark_capacity     │
│  (PK: session_id)  │      │  (PK: session_id)  │      │   (PK: carpark_id)     │
└────────────────────┘      └────────────────────┘      │ *內含 leases.{session} │
  │                │                                    └────────────────────────┘
  │                │ (target_carpark_id)                            ▲
  │ 1:N            └──────────────────────┐                         │ (共享 carpark_id)
  ▼                                       ▼                         ▼
┌────────────────────────────┐ N:1  ┌────────────────────────────────────┐
│carpark_pain_points/reports │─────►│        carpark_pain_points         │
│(PK: session_id_timebucket) │      │          (PK: carpark_id)          │
└────────────────────────────┘      └────────────────────────────────────┘
                                                    ▲
                                                    │ (1:1 共享 carpark_id)
                                                    ▼
                                    ┌────────────────────────────────────┐
                                    │        carpark_perks_master        │
                                    │          (PK: carpark_id)          │
                                    └────────────────────────────────────┘

3. 詳細關聯解釋（佢哋實際點樣互動？）
 * active_sessions ⟷ virtual_locks（一對一 或 一對零 關係）
   * 關聯鍵： 兩張表都係用同一個 session_id 做 Document ID（主鍵）。
   * 運作邏輯： 一個用家開一個 Session，最多只可以同時鎖住一個車位。當用家喺 App 揀定去某個停車場（Commit），API 會用 Firestore Transaction 同時更新 active_sessions（將狀態改做 COMMITTED 並填寫 target_carpark_id）同埋喺 virtual_locks 新增一筆記錄（填入 carpark_id）。用家取消或者確認泊好車，就會一齊刪走 virtual_locks 入面嗰筆記錄。
 * virtual_locks ⟷ 停車場 carpark_id（多對一 關係 — 現時做法）
   * 關聯鍵： virtual_locks.carpark_id 對應停車場 ID。
   * 運作邏輯： 一個停車場可以同時俾好多個 Session 鎖位。當系統要計某個車場仲有無位嗰陣，會先去 GCS 嘅 snapshot.json 睇下運輸署話有幾多個位，再去 virtual_locks 用 WHERE carpark_id == 某車場 數下依家有幾多人鎖緊位，兩者相減就係真正剩低嘅車位。
 * active_sessions / virtual_locks ⟷ carpark_capacity（多對一 內嵌關係 — Phase 3c 新設計）
   * 關聯鍵： 主鍵係 carpark_id，而入面有一個 Map 欄位叫 leases.{session_id}，直接將 session_id 同埋鎖位版本號 lock_revision 塞入去。
   * 運作邏輯： 為咗唔使每次都去 virtual_locks 數手指，新版改由 carpark_capacity 做「總帳簿」。每次有人鎖位或放位，都會喺一個 Transaction 入面同時改 active_sessions、virtual_locks 同埋 carpark_capacity 呢三張表，確保數據 100% 同步。
 * carpark_pain_points ⟷ reports 子集合（一對多 父子關係）
   * 關聯鍵： reports 直接掛喺 carpark_pain_points/{carpark_id} 下面（即係 carpark_pain_points/{carpark_id}/reports/{session_id}_{time_bucket}）。
   * 運作邏輯： 用家（session_id）去報料話某個停車場排長龍，明細會寫入下層嘅 reports 子表（一個 Session 每 5 分鐘最多產生一條記錄）。API 同時會統計最近 1 個鐘內有幾多個唔同嘅報料來源，將統計結果（例如 crowd_penalty_points 扣分）寫返上老豆張表 carpark_pain_points 度。
 * 推薦引擎讀取嘅「虛擬大表」關係（以 carpark_id 橫向串連）
   * 當用家叫語音助手推薦停車場時，後端 API 其實係喺記憶體入面用 carpark_id 將以下四個地方嘅數據 LEFT JOIN 埋一齊嚟計分：
     * GCS snapshot.json：攞官方車位空缺數。
     * virtual_locks（或新版 carpark_capacity）：扣走已經俾其他用家預留咗嘅位。
     * carpark_pain_points：睇下有無人報料話塞車要扣分（C_i），或者有無長期黑點扣分（P_i）。
     * carpark_perks_master：睇下有無商場泊車優惠可以加分（M_i）。
 * GCS snapshot.json ➔ GCS history/*.ndjson ➔ BigQuery grid_occupancy_history（單向數據流）
   * 運作邏輯： 呢條線同 Firestore 完全無關，純粹係「冷數據（Cold Path）」備份。系統定期將 GCS 入面嘅即時快照（snapshot.json）抄一份去 history/ 資料夾做存檔，之後每日再由 history_bq.py 批次匯入去 BigQuery 嘅 stg_grid_occupancy_history_...（暫存表），最後合併（MERGE）入主表 grid_occupancy_history。
(註：你份文件開頭有提過個 Repo 入面其實仲有兩份檔案叫 DATABASE_TABLE_PLAN.md 同埋 database_table.txt，如果你手頭上有呢兩個檔案，擺上嚟我可以幫你對照埋入面有無補充其他欄位細節！)