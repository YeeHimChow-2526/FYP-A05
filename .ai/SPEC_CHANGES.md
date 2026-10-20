# 規格改動待辦（SPEC_CHANGES）— v2.9.1

用途：記低需要改規格嘅發現；人類定期帶給規格守護者，改好 Doc 後清空。

最後更新：2026-10-20

## 記錄規則

- 寫入者：Gemini（首席架構師）為主；真機測試結果或 Pi 執行時發現嘅規格問題，由 Kenny 或 Gemini 代記。
- 必記：規格矛盾或缺漏、真機調整任何常數、新增或升級入 App 嘅套件（凡會改 `pubspec.yaml`、`build.gradle(.kts)` 嘅依賴）。
- 開發工具（IDE 插件、模擬器設定、repomix）唔使記。
- 每條用下面模板，新條目加喺「條目」最底；規格守護者改好 Google Doc 後，Kenny 清空「條目」一節。

## 條目模板

```markdown
### SC-001
- 日期：YYYY-MM-DD
- 來源：Gemini／真機測試／Pi
- 裝置（如適用）：型號｜Android 版本｜語音服務版本
- 發現：
- 建議改動：
- 影響任務編號／常數名／檔案：
- 類別：規格矛盾／常數調整／新增依賴／其他
- 狀態：待處理
```

## 條目

### SC-001
- 日期：2026-10-20
- 來源：Pi
- 裝置（如適用）：不適用
- 發現：Step 1.2 需跑 JVM 單元測試，`android/app/build.gradle.kts` 原本沒有 `testImplementation`。
- 建議改動：只加測試依賴 `kotlin("test")` 與 `junit:junit:4.13.2`，不加入任何 App 執行期套件。
- 影響任務編號／常數名／檔案：W-A.1、W-A.15；`android/app/build.gradle.kts`
- 類別：新增依賴
- 狀態：待處理

### SC-002
- 日期：2026-10-20
- 來源：Pi
- 裝置（如適用）：不適用
- 發現：§4.4「現場即泊 GPS 水平精度 ≤100m」未寫入 §4.1 `NativeConfig` 程式碼區塊。業務碼禁止硬編碼距離，故於 `NativeConfig.kt` 增補同值常數供 `TripStateMachine` 引用。
- 建議改動：實作版 PART 2 §二 `NativeConfig` 區塊補上 `ON_SITE_GPS_ACCURACY_MAX_M = 100`（數值不變，只係把 §4.4 門檻納入常數檔）。
- 影響任務編號／常數名／檔案：W-A.15；`ON_SITE_GPS_ACCURACY_MAX_M`；`NativeConfig.kt`
- 類別：規格缺漏
- 狀態：待處理
