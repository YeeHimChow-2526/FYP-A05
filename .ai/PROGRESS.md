# 開發進度（PROGRESS）— v2.9.1

最後更新：2026-10-25｜任務來源：`.ai/ARCHITECTURE.md` §19 PART 3 路線圖任務表（共 46 條任務項＝44 項工程任務，W-E.2 與 W-F.2 各拆 Part 1／Part 2）｜驗收細節以實作版 PART 3 為準。

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

- [x] W-A.1 懸浮膠囊＋狀態機 — commit: 4ac4036, eaa4fc1, e8f00f7, a4b1944, 752de53
- [ ] W-A.2 Maps 接盤與分享跳板 — 分享跳板 ShareIntentReceiverActivity＋B-2 標題／掉針解析已落地（3.2）；短網址／geocode 未做（SC-015） — commit: 
- [ ] W-A.3 本機字典樹與常數檔 — 常數檔已落地（Step 1.1）；字典樹已落地（3.1）並接駁語音終點（3.1b）；Miss 仍未有 geocode（SC-014）；TRIE_MISS_* sim hook 留 3.2 — commit: 2fbf18b, c0c392c, 0cc3bd9, b3702a6
- [x] W-A.15 session_id 生命週期 — commit: 4ac4036, e8f00f7, a4b1944
- [x] W-A.16 FGS 啟動握手 — commit: a2017f5, 623c382, a03217f
- [ ] W-A.4 軟件距離追蹤 — DistanceTracker＋PlatformLocationSource 已落地（3.3）；真機耗電／GPS 漂移未驗 — SC-016 — commit: 
- [ ] W-A.5 在線即送 release — 熄匙 release 用已 commit 車場（3.3）— commit: 
- [ ] W-A.17 到場雙態與今日確認 — 到場雙態 native 已落地（3.3）；今日確認 UI 待 3.4 — commit: 
- [ ] W-A.18 測試策略與量度 — Step 1 sim 範圍已完成（完整 Gate C：gateC6 @ 2201d2e）；B-2 分享 sim／單元測試已覆蓋（3.2），真機 Maps 分享待測 — commit: dff84d8, 4af4c06, c4f9354, 10575c0, f9e101d, 5c76000, 86e7cd7, 2201d2e
- [ ] W-B.4 sync/outbox 確認 — commit: 

### 19.3 Phase 2 — 行車交互精細化與香港特色場景（第 6–10 步）

- [x] W-A.6 偏好三段開關 — commit: 2fbf18b, adee71a
- [x] W-A.7 語音優先口播出卡 — commit: a5da97e, 72ae56a, ee0f7b3, 73760f4, 756f582, 6aad728, 765a49e
- [ ] W-A.8 倒車雷達避讓 — commit: 
- [ ] W-A.9 熄匙無痕銷毀 — sim 熄匙＋EngineShutdownReceiver（車充／藍牙 ACL，去抖＋行車防護）已落地（3.3）；藍牙真機未驗 — SC-016 — commit: 
- [ ] W-A.10 無終點主動喚起 — commit: 
- [ ] W-A.13 單一 AudioRecord — commit: 
- [ ] W-A.14 麥克風 FGS 與權限 — commit: 
- [x] W-A.19 語音能力檢查 — commit: a5da97e, 623c382, 5922985, 4bff94b
- [ ] W-A.20 STT 開發前驗證 — commit: 
- [x] W-A.21 指令碼與語言模板 — commit: a5da97e, 752de53, 5922985, 04d8879, 4bff94b
- [x] W-A.12 網絡分級重試 — commit: b73fff4, dfadd4f, cc1a27c

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
| Step 1 | 雙層骨架＋FGS 握手＋P 膠囊燈號＋`sim/` 殼與 fixtures＋`ApiClient` 分級重試＋三種行程語音模式與能力檢查＋粵語 TTS 口播首選 | Step 1.1：W-A.6；W-A.3 常數檔。Step 1.2：W-A.15；W-A.1 狀態機。Step 1.3：W-A.19、W-A.21；W-A.7 首選口播。Step 1.4：W-A.12。Step 1.5：W-A.16。Step 1.6：W-A.1 |
| Step 2 | 語音追問閉環＋`FloatingCardView` 追問出卡＋手動模式卡片按鈕＋Intent 喚醒 Google Maps 導航 | W-A.7；W-A.12（含 cc1a27c）。W-A.9 只完成 sim 口播即停 |
| Step 3 | `LocalPoiTrie.kt`＋B-2 分享接盤＋`DistanceTracker`＋`EngineShutdownReceiver`（3.3／SC-016）＋Flutter 今日行程（待 3.4） | 3.1／3.1b／3.2／3.3 |

- [x] Step 1 必做：原生 sim/ 模擬殼＋mock/*.json fixtures＋注入事件（屬 W-A.18；規格 docs/specs/04_mock_and_sim_shell.md），Step 1 完成前交付 — commit: dff84d8, 97bb2e6

**Step 1 遺留（轉 Step 2/3）**：
- Step 2 已做：B-1／現場即泊失敗口播、ALERT／NO_MIC 口播（72ae56a）；`await_voice_reply` 重發（ee0f7b3, 73760f4）
- Step 3：`TRIE_MISS_RESOLVE_OK`（經 LocalPoiTrie）／`TRIE_MISS_NETWORK_FAIL`（GREY＋口播一次）／`TRIE_MISS_AMBIGUOUS`（ALERT）已接；B-2 分享接盤 3.2 — SC-015

未完成：B-2 短網址展開／雲端 Geocoding（待後端 endpoint，見 SC-015）、閘口座標、TodayTripStore（3.4）、車場坐標（SC-016）、單一 AudioRecord、STT 引擎、今日行程 Flutter。
Step 3.1：LocalPoiTrie＋hk_poi_master.json（tools/poi 建置）；Step 3.1b b3702a6：語音 SET_DESTINATION 接 Trie（澄清／回顯／坐標；SC-014）；TRIE_MISS_* 未接（3.2）
- Step 3 開工前裁決：含糊地名口播 02 L110「去邊度？請拍一拍膠囊答我」vs 代碼 `PLACE_CLARIFY`「邊一區？」vs 03 L125「純語音反問」
- 真機：FGS 真實 2s 逾時 `FgsBootstrapActivity` Log.w 記錄（模擬器無法觸發）

## 待真機驗證

模擬器只鎖死行為；以下項目必須真機驗收（步驟見 `docs/testing/01_test_plan.md`），通過後於相關任務標 `[x]` 並記裝置資料：

- [真機] Phase A 語音鏈（單一 AudioRecord → 裝置端 STT） — 裝置： — 結果：
- [真機] 防自我辨識 — 裝置： — 結果：
- [真機] 藍牙熄匙（車載藍牙 ACL 斷開） — 裝置： — 結果：
- [真機] OEM 背景殺程（Xiaomi／Samsung） — 裝置： — 結果：
- [真機] B-1 路徑 1A Hey Google（實驗性入口驗證） — 裝置： — 結果：
