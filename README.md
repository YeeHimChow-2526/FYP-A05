# hk_parking_client_context

客戶端（**Android 原生 Kotlin + Flutter App 畫面**）Context 包 — 對應香港「泊車副駕」實作版 **v2.9.1**。

## 呢個包係咩

畀 coding agent／工程師嘅**精簡客戶端記憶**：產品不變量、目錄地圖、原生語音／狀態機 FSM、FGS 啟動握手與 MethodChannel、REST＋Kotlin 字典樹、原生 `sim/` 殼與模擬器／真機測試分工，以及測試清單（`docs/testing/01_test_plan.md`）。  
行程期間全部車內邏輯由原生 Kotlin 執行（`TripStateMachine.kt` 為狀態機唯一擁有者）；Flutter 只負責設定、新手教學與今日行程畫面。  
**真理源仍係** Google Doc 實作版 v2.9.1（文件 ID `1wzfVsSobPfCQW-TNcLnRmy9LsEB5KdUSbaUJwOfeEPo`）。本包係閱讀／開工切片，衝突時以 Doc 正文為準。

## 點樣抄入專案

1. 將本目錄內容複製到你嘅專案（或 submodule／`docs/client_context/`）。
2. 至少保留：`AGENTS.md`、`docs/specs/*.md`、`docs/testing/01_test_plan.md`、`docs/reference/android_manifest_notes.md`。
3. 將 `android/app/src/main/kotlin/com/hkparking/copilot/NativeConfig.kt` 合併入原生模組（行車常數單一事實源）；`lib/core/constants/app_config.dart` 合併入 Flutter（App 畫面參數）。
4. 按 `AGENTS.md` 目錄地圖建立原生 `sim/`（只限 debug 建置），按 Stage 逐步加。
5. Manifest 以 `docs/reference/android_manifest_notes.md` 為**參考**手寫入 `android/`，唔好假設已存在。

入口閱讀：先 `AGENTS.md`，再按任務打開 `docs/specs/01`–`04`；測試按 `docs/testing/01_test_plan.md`（Phase A 開發前語音鏈驗證；Phase B 產品整合 29 項）。

語音：行程語音模式三選一（全語音／播報＋手動／純手動），由能力檢查判定；`minSdk 33` 只係安裝下限。

入口優先聲明（決定版）：「B-1 路徑 1A：Hey Google／App Actions 冷態入口屬實驗性能力，須按指定 intent、語言及裝置驗證通過後方可啟用，不列入本版必要交付或免手操作保證。正文相關冷態流程均以驗證通過為前提。本限制不適用於 B-1 路徑 1B 已開程後的本機語音互動。」

## 專案包用法

本專案包係**客戶端**（Flutter＋原生 Android，用 Android 模擬器＋mock 數據開發）嘅專案根目錄；解壓後直接當專案用。雲端 API、數據管道、Firestore、IaC 等部分寫喺 `.ai/ARCHITECTURE.md`，只供對照參考。

### 包內 `.ai/` 檔案

| 檔案 | 作用 | 邊個寫 |
|---|---|---|
| `.ai/ARCHITECTURE.md` | v2.9.1 AI 全局知識總綱（與 `docs/AI_GLOBAL_KNOWLEDGE_v291.md` 逐字相同） | 規格守護者（隨 Doc 升版更新） |
| `.ai/HANDOFF.md` | Gemini 開場提示：角色分工、技術路線、模擬器 MVP 三部曲、Pi 派工鐵律 | Kenny（填實作版網址） |
| `.ai/PROGRESS.md` | 全部 46 條路線圖任務項清單＋待真機驗證清單 | Pi 勾選並填 commit；Gemini 確認 |
| `.ai/SPEC_CHANGES.md` | 需要改規格嘅發現（模板＋條目） | Gemini 為主；Kenny 代記真機結果 |
| `.ai/report.sh` | 跑測試指令並生成 `.ai/LAST_RUN.md` | 現成腳本，Pi 執行 |
| `.ai/TASK.md` | 當前任務（每回合覆寫，唔入 git） | Gemini 出，Kenny 貼入 |
| `.ai/LAST_RUN.md` | 回合報告：測試結果、git status、差異（唔入 git） | `report.sh` 生成 |

另附 `.gitignore`（忽略建置產物、`.ai/TASK.md`、`.ai/LAST_RUN.md`、repomix 輸出）同 `.repomixignore`（打包上下文時略過腳手架雜訊）。

### 第一次設定

1. 解壓：`tar -xzf hk_parking_project_kit_v291.tar.gz`
2. 入目錄：`cd hk_parking_project_kit_v291`
3. 建 repo：`git init`
4. 首個 commit：`git add -A && git commit -m "chore: 專案包 v2.9.1"`
5. 安裝 Pi（本地 CLI Agent）：`npm install -g @mariozechner/pi-coding-agent`，喺專案根目錄執行 `pi`
6. 喺 `.ai/HANDOFF.md` 將「（實作版網址）」換成你嘅實作版分享連結
7. 開 Gemini 新對話，上載 `.ai/HANDOFF.md`、`.ai/ARCHITECTURE.md`、`AGENTS.md`、`.ai/PROGRESS.md`；Gemini 回覆確認句後開工

### 日常循環

1. 叫 Gemini 出任務 → 將輸出貼入 `.ai/TASK.md`
2. 叫 Pi 執行 `.ai/TASK.md`：改檔 → 跑單一測試 → `git add -A && git commit` → `.ai/report.sh "<同一測試指令>"`
3. 將 `.ai/LAST_RUN.md` 交返 Gemini，講「已執行，請讀 LAST_RUN」
4. Gemini 答「通過，下一題」或出修正 TASK
5. 通過後確認 `.ai/PROGRESS.md` 已勾選該任務並填 commit

`report.sh` 嘅退出碼跟測試指令一致（未提供測試指令時為 0）；差異超過 150 行時只列 `git diff --stat`。

### 規格改動路徑

Gemini 唔改規格文件。發現規格矛盾、缺漏，或真機結果需要改規格時，記入 `.ai/SPEC_CHANGES.md`；Kenny 定期帶畀規格守護者，Google Doc 改好並重新出包後清空條目。

**必記規則：** 新增或升級入 App 嘅依賴（`pubspec.yaml`、`build.gradle(.kts)`），以及真機調整任何常數（`NativeConfig.kt`／`app_config.dart`），一律先記入 `.ai/SPEC_CHANGES.md`。
