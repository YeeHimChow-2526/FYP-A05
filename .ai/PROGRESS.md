# 開發進度（PROGRESS）— v2.9.1

最後更新：2026-10-20｜任務來源：`.ai/ARCHITECTURE.md` §19 PART 3 路線圖任務表（共 46 條任務項＝44 項工程任務，W-E.2 與 W-F.2 各拆 Part 1／Part 2）｜驗收細節以實作版 PART 3 為準。

寫入者：Pi 完成任務並 commit 後勾選並填 commit；Gemini 審查 `.ai/LAST_RUN.md` 後確認；Kenny 可隨時人手修正。

## 圖例

- `[ ]` 未做
- `[x]` 完成（填 commit 短 hash）
- `[真機]` 模擬器已鎖死行為，待真機驗證

格式：`- [x] W-A.1 標題 — commit: abc1234`（步數見各節標題）

## 客戶端範圍說明

本專案包屬客戶端（Flutter＋原生 Android，模擬器＋mock 數據）。W-A 系列為客戶端任務；W-B／W-C／W-D／W-E／W-F 屬雲端 API、數據管道、Firestore、冷資料與 IaC，列出供全局對照，客戶端 MVP 以 mock fixtures（`docs/specs/04_mock_and_sim_shell.md`）代替。

## 路線圖任務（46 條）

### 19.1 Phase 1 — 垂直骨幹與端雲閉環（第 1–3 步）

- [ ] W-D.1 四大集合＋Seed 腳本 — commit: 
- [ ] W-D.2 會話／鎖 TTL 政策 — commit: 
- [ ] W-D.3 鎖計數複合索引 — commit: 
- [ ] W-D.4 安全規則禁前端直連 — commit: 
- [ ] W-C.1 政府數據抓取快照 — commit: 
- [ ] W-C.2 髒數據洗滌與完整性 — commit: 
- [ ] W-C.3 HK1980 轉 WGS84 — commit: 
- [ ] W-B.1 recommend 算分核心 — commit: 
- [ ] W-B.12 speech_locale — commit: 
- [ ] W-B.2 commit 虛擬鎖 — commit: 
- [ ] W-B.3 release 釋放鎖 — commit: 

### 19.2 Phase 1 — 垂直骨幹與端雲閉環（第 4–5 步）

- [ ] W-A.1 懸浮膠囊＋狀態機 — commit: 
- [ ] W-A.2 Maps 接盤與分享跳板 — commit: 
- [ ] W-A.3 本機字典樹與常數檔 — 常數檔已落地（Step 1.1）；字典樹未做 — commit: 32e2a48
- [ ] W-A.15 session_id 生命週期 — commit: 
- [ ] W-A.16 FGS 啟動握手 — commit: 
- [ ] W-A.4 軟件距離追蹤 — commit: 
- [ ] W-A.5 在線即送 release — commit: 
- [ ] W-A.17 到場雙態與今日確認 — commit: 
- [ ] W-A.18 測試策略與量度 — commit: 
- [ ] W-B.4 sync/outbox 確認 — commit: 

### 19.3 Phase 2 — 行車交互精細化與香港特色場景（第 6–10 步）

- [x] W-A.6 偏好三段開關 — commit: 32e2a48
- [ ] W-A.7 語音優先口播出卡 — commit: 
- [ ] W-A.8 倒車雷達避讓 — commit: 
- [ ] W-A.9 熄匙無痕銷毀 — commit: 
- [ ] W-A.10 無終點主動喚起 — commit: 
- [ ] W-A.13 單一 AudioRecord — commit: 
- [ ] W-A.14 麥克風 FGS 與權限 — commit: 
- [ ] W-A.19 語音能力檢查 — commit: 
- [ ] W-A.20 STT 開發前驗證 — commit: 
- [ ] W-A.21 指令碼與語言模板 — commit: 
- [ ] W-A.12 網絡分級重試 — commit: 

### 19.4 Phase 3 — 商業激戰區強化、安全防禦與眾包反饋（第 11–13 步）

- [ ] W-B.6 LB＋Cloud Armor — commit: 
- [ ] W-B.7 A+C 混合防刷 — commit: 
- [ ] W-B.8 稀缺區保底 — commit: 
- [ ] W-B.9 慣性霸位黑點 — commit: 
- [ ] W-B.11 低水位提示 — commit: 
- [ ] W-A.11 今日行程與反饋 — commit: 
- [ ] W-B.5 crowd-report — commit: 
- [ ] W-B.10 trip-report 補評 — commit: 

### 19.5 Phase 4 — 冷資料分析、離線仲裁與 IaC 自動維運（第 14–19 步）

- [ ] W-E.1 歷史 NDJSON→BQ — commit: 
- [ ] W-E.2 (Part 1) 排隊痛苦指數分析 — commit: 
- [ ] W-E.2 (Part 2) 優惠雙軌仲裁 — commit: 
- [ ] W-F.1 Terraform IaC — commit: 
- [ ] W-F.2 (Part 1) 監控與 SLA 告警 — commit: 
- [ ] W-F.2 (Part 2) 預算熔斷與節流驗收 — commit: 

## 模擬器 MVP 三部曲 ↔ 任務編號

`.ai/ARCHITECTURE.md` 及 `AGENTS.md` 並無一對一列明 MVP Step 1／2／3 對應邊啲 W-A 任務編號，所以呢度唔預先綁定；由 Gemini 出每份 TASK.md 時指定任務編號，Pi 勾選時照填。參考：`AGENTS.md`「客戶端路線圖」Stage 1–5 表及「W-A 任務錨點（客戶端）」。

| MVP 步驟 | 範圍（摘自 `.ai/HANDOFF.md`） | 任務編號 |
|---|---|---|
| Step 1 | 雙層骨架＋FGS 握手＋P 膠囊燈號＋`sim/` 殼與 fixtures＋`ApiClient` 分級重試＋三種行程語音模式與能力檢查＋粵語 TTS 口播首選 | Step 1.1：W-A.6；W-A.3 常數檔 |
| Step 2 | 語音追問閉環＋`FloatingCardView` 追問出卡＋手動模式卡片按鈕＋Intent 喚醒 Google Maps 導航 | 由 Gemini 指定 |
| Step 3 | `LocalPoiTrie.kt` 本地字典樹＋Google Maps 分享接盤＋`DistanceTracker.kt` 到場雙態＋Flutter 今日行程 | 由 Gemini 指定 |

## 待真機驗證

模擬器只鎖死行為；以下項目必須真機驗收（步驟見 `docs/testing/01_test_plan.md`），通過後於相關任務標 `[x]` 並記裝置資料：

- [真機] Phase A 語音鏈（單一 AudioRecord → 裝置端 STT） — 裝置： — 結果：
- [真機] 防自我辨識 — 裝置： — 結果：
- [真機] 藍牙熄匙（車載藍牙 ACL 斷開） — 裝置： — 結果：
- [真機] OEM 背景殺程（Xiaomi／Samsung） — 裝置： — 結果：
- [真機] B-1 路徑 1A Hey Google（實驗性入口驗證） — 裝置： — 結果：
