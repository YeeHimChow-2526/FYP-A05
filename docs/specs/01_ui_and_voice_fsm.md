# 01 — 原生語音 FSM／燈號／到場雙態；Flutter 教學／權限橫額／今日行程

來源：實作版 Doc **v2.9.1** PART 1 §一／§三／§四（偏好對口播）／§五、PART 2 §一。正面向描述當前設計。

## 0. 擁有權

- 膠囊、燈號、語音對話、追問卡片、到場判定：全部由原生 **`TripStateMachine.kt`**（唯一擁有者）驅動，喺 `FloatingBubbleService.kt` 內運行；卡片由原生 **`FloatingCardView.kt`** 以 WindowManager 繪出。
- Flutter 只負責 App 內畫面（§5–§7、§10）；經 MethodChannel 讀取並顯示原生狀態（`getTripState()`、`onTripStateChanged`），**唔持有**任何行車狀態。

## 1. 產品交互哲學（語音優先）

- 行車以**語音為主**：API 回傳已排序 `cards[]`，每卡附 `spoken_cue`。
- 司機要求泊車（「泊車」／「搵位」或拍開膠囊）後，原生 TTS **只口播首選**（位置、空位、價錢）；畫面**不自動彈卡**、不自動輪播、**不設倒數**。
- 1km 預備區內只背景靜默預取；**不主動跳窗、不主動口播**推薦。
- 卡片只於追問「詳細啲」／「有冇第二個」時出現；拍卡屬可選（語音「好／去呢個」同等）。
- 行程語音模式三選一（全語音／播報＋手動／純手動，見 §5.1）；STT 不可用之兩個模式由司機拍膠囊顯示卡片（手動降級介面例外，見 §5.2）。

## 2. 膠囊燈號與 Status Pip

膠囊外圈色**獨立於**空位 Status Pip（綠充足／黃緊湊／紅緊張）：

| 狀態 | 含義 | 備註 |
|------|------|------|
| `GREY_STEADY`（CRUISING） | 巡航待命 | 不呼吸 |
| `GREY_BREATHING` | 網絡有問題 | **優先於** `ALERT_BREATHING`；自動重試最多 3 次後等待下一觸發／司機要求 |
| `AMBER_STEADY` | 數據未齊 | 穩態不呼吸（如 B-2 Trie Miss 背景解析中） |
| `ALERT_BREATHING` | 需要司機回應終點 | 只於 location FGS 已運行且膠囊已顯示 |
| `NEARBY_READY` + 綠／黃／紅 | 空位 pip | 預取成功後 |
| `EXPANDED` | 追問出卡 | 一次一張 |
| `NAV_TO_GATE` | 導航閘口中 | |
| `APPROACH_MUTED` | 到場靜音 | 只靜音主動口播；行程繼續；拍膠囊仍可轉場／「有冇第二個」；不釋放鎖 |
| `PARKED_CONFIRMED` | 泊妥確認 | 熄匙觸發；寫今日行程候選後停止 FGS |

**燈號優先次序**：網絡失敗一律 `GREY_BREATHING`＋網絡口播，即使本程未有終點亦**唔轉** `ALERT_BREATHING`。

`ALERT_BREATHING` 四項觸發（每次只口播一次，維持至設終點或行程結束）：

1. 行車未設終點（>20km/h 連續 30s 或累積 400m）
2. 模式 A 導航通知命中**中**置信度終點（中英雙語指路詞過濾，見 §5.7）
3. 地名需要澄清（如「邊一區？」）
4. 司機主動操作因**非網絡**原因失敗且本程仍無終點

**共用規則**：全語音模式下，凡需司機語音作答之口播，若麥克風 FGS **尚未升級**，句尾必須加 **「請拍一拍膠囊答我」**；STT 不可用（播報＋手動／純手動）時不作此要求，地名輸入留待泊好車後於 App 處理。麥克風初始化失敗（`MIC_READY` 失敗）時**只停用語音輸入**（聆聽與熱詞）：本程以 location 類型繼續、膠囊照常顯示；TTS 可用則照常口播（按播報＋手動模式），TTS 不可用則按純手動模式。

## 3. 追問出卡 FSM（原生）

```
要求泊車 → TTS 口播首選（無卡）
  ├─「詳細啲」→ FloatingCardView 顯示當前卡 + 同步讀詳情
  ├─「有冇第二個」→ 口播 + 350ms 切下一張（NativeConfig.CARD_PAGE_TRANSITION_MS）
  ├─「好／去呢個」或拍卡 → commit + 導航閘口
  ├─「唔使／取消／收埋」或滑走 → 收起／待命
  └─ 無回應 → 回到 CRUISING／NEARBY_READY（唔用 ALERT）；原 Maps 路線不變

STT 不可用（播報＋手動／純手動）：
  拍膠囊 → FloatingCardView 顯示推薦卡（手動「下一個」／「確認」／「取消」按鈕；仍不自動彈卡）
```

- 口播期間隨時支援語音取消（「取消／唔好／等等」）與語音確認（「好／去呢個」）；手勢滑動卡片即刻停止口播，靜默待命（實作版 §6.5）。
- 卡片高度 = `min(175dp, 0.4 × 螢幕高)`（`NativeConfig.OVERLAY_MAX_CARD_HEIGHT_DP` / `OVERLAY_MAX_SCREEN_COVERAGE`）。
- **全卡方框盲撳**（唔設獨立按鈕行）；任何車速均可追問；底色約 `#121212`／80% 不透明 + Backdrop blur（低階機實色降級）。
- 已顯示卡片保留至確認／取消／滑走／`NAV_TO_GATE`／`APPROACH_MUTED`；**不設自動收起計時**。

## 4. 到場雙態（W-A.17）

| 狀態 | 進入條件 | 行為 |
|------|----------|------|
| `APPROACH_MUTED` | 80m 核心區＋車速連續 3s <8km/h；或 150m 內 <15km/h 且最後有效 fix 年齡 >5s（地庫斷訊） | 只靜音主動口播；行程繼續；距離持續 >200m 達 10s 退回巡航；無 GPS 維持靜音；**不**送 release |
| `PARKED_CONFIRMED` | 目標場 150m 內（或地庫斷訊狀態）＋熄匙訊號（藍牙 ACL 斷開／車充斷電） | `TodayTripStore` 寫入候選「可能已泊入 X」→ 在線送 release（離線放棄）→ 0.5s 內淡出停止 FGS |

目標範圍外熄匙即結束本程，不寫候選。

## 5. 語音喚醒與句式（原生 SttService／TtsService／VoiceCapabilityChecker）

- 熱詞（「泊車助手」／「泊車副駕」／「Hey 泊車」）：本機離線；只於本程麥克風 FGS 已升級後生效（trip-long）；未命中前音訊只滾動緩衝、唔落地唔上傳。
- 單段：「泊車助手，去朗豪坊」→ 原生粵語 STT 轉出文字（延遲真機實測）→ 本機字典樹由文字查出坐標 <150ms →「收到，為你鎖定…」
- 雙段：「泊車助手」→「喺度，想去邊度？」→ 地名鎖定
- 未啟用熱詞時拍膠囊單次聆聽：按 §5.4「收音計時」聆聽，講完自動停；本句完結即停止 AudioRecord 並降級 location。
- STT 信心度 < 0.6：口播「太嘈聽唔清，稍後再試」即刻閉嘴（信心度缺失或 −1 另行分支，見 §5.6）。
- 行車未設終點口播：「出發緊？去邊度等我睇定車位？」；全語音模式且 mic 已升級 → 按收音計時聆聽；全語音模式 mic 未升級 → 只口播＋維持 ALERT，待拍膠囊；STT 不可用 → 只口播一次（播報＋手動）或只顯示 ALERT（純手動），終點留待泊好車後輸入。
- 入口優先聲明（決定版）：「B-1 路徑 1A：Hey Google／App Actions 冷態入口屬實驗性能力，須按指定 intent、語言及裝置驗證通過後方可啟用，不列入本版必要交付或免手操作保證。正文相關冷態流程均以驗證通過為前提。本限制不適用於 B-1 路徑 1B 已開程後的本機語音互動。」驗證步驟見 `docs/testing/01_test_plan.md`（Google Doc「泊車副駕 測試清單 v2.9.1」，ID `1OaMcyeEU8m0HKuhhCLf2DCJX9iY0h1vu-ar7WGdd148`） Phase B 第 12 項。

### 5.1 行程語音模式（決定版，三種互斥）

能力檢查（`VoiceCapabilityChecker.kt`，W-A.19）判定離線粵語 STT 及離線粵語 TTS 語音（以裝置端實際能力為準，TTS `setLanguage` 成功不足以作準），結果分「已安裝可用／支援但未安裝／不支援／未知」四類。首次使用於教學頁面 5 檢查並提示下載；每程開程靜默重查（`NativeConfig.VOICE_CAPABILITY_CHECK_TIMEOUT_MS`），與 FGS 啟動握手並行、**絕不阻塞** `FGS_STARTED`，開程時**絕不**自動彈下載提示；逾時沿用最近一次已確認結果，從未確認者按不可用處理。

| 模式 | 條件 | 行為 | FGS 類型 |
|------|------|------|----------|
| `FULL_VOICE` 全語音 | STT＋TTS 均可用，並已授 `RECORD_AUDIO` | 口播＋聆聽；熱詞只於熱詞能力可用且設定未關閉本程熱詞時啟用 | 按入口規則 `location\|microphone` |
| `ANNOUNCE_MANUAL` 播報＋手動 | STT 不可用（或未授 `RECORD_AUDIO`）、TTS 可用 | 照常口播；不聆聽、不設熱詞 | 只 `location` |
| `MANUAL_ONLY` 純手動 | TTS 不可用（不論 STT） | 不口播、不聆聽、不設熱詞 | 只 `location` |

`RECORD_AUDIO` 只屬全語音模式（語音輸入）之必要權限；行程中撤銷處理按模式（見 `02` §5）。`minSdkVersion = 33` 只屬安裝下限，並不保證裝置具備離線粵語 STT／TTS。

### 5.2 手動降級介面例外（決定版）

只適用於 STT 不可用之播報＋手動／純手動模式；於此情況下優先於「卡片只於語音追問時出現」及「全卡盲撳、不設按鈕行」：司機可拍膠囊顯示推薦卡片；卡片提供手動「下一個」／「確認」／「取消」按鈕；仍然不自動彈卡。手動模式並非免手操作之替代方案，行車中以 Google Maps 既有導航為主。

### 5.3 單一 AudioRecord → 裝置端 STT 工程閘門（決定版，開發前驗證）

熱詞命中後同一 AudioRecord 連續錄音並把 PCM 交 STT，前提為目標裝置／引擎組合通過開發前驗證（W-A.20；步驟見 `docs/testing/01_test_plan.md`（Google Doc「泊車副駕 測試清單 v2.9.1」，ID `1OaMcyeEU8m0HKuhhCLf2DCJX9iY0h1vu-ar7WGdd148`） Phase A）：

1. 只用裝置端路徑（`createOnDeviceSpeechRecognizer` 或同等），不設雲端 STT 後備；單靠 `EXTRA_PREFER_OFFLINE` 不足以作準。
2. 驗收須證明結果來自注入之外部音訊，並非引擎自行開咪。
3. PCM 契約寫明採樣率、聲道、編碼、管道／檔案描述符生命週期及格式轉換（例如 16 kHz／單聲道／PCM 16-bit，經 Android 13+ `RecognizerIntent.EXTRA_AUDIO_SOURCE` 或同等機制注入）。
4. 區分「結束本句 STT」與「停止 AudioRecord」：每句完結即結束 STT 輸入，本程熱詞開啟時熱詞擷取照常持續；來電、撤銷權限或行程結束時完全停止 AudioRecord。
5. 系統引擎未通過時採用可嵌入離線引擎（例如 SenseVoice／Paraformer／Whisper），或收窄語音支援範圍；不宣稱 Android 13+ 全面支援。

### 5.4 收音計時（決定版，起始值，真機調校）

| 常數（`NativeConfig`） | 值 | 意思 |
|------|----|------|
| `LISTEN_OPENING_WAIT_S` | 3 | 提示音／TTS 播完且音訊輸入就緒後開始計；期間未偵測到人聲即關閉聆聽 |
| `LISTEN_END_SILENCE_S` | 0.8 | 偵測到人聲後連續靜音達此值即結束本句 |
| `LISTEN_MAX_UTTERANCE_S` | 8 | 由首次偵測到人聲起計之單句上限 |
| `STT_RESULT_TIMEOUT_S` | 5 | 音訊已停止輸入但引擎未回傳結果之等候上限；逾時按「聽唔清」處理 |

計時由本機語音控制層（VAD）擁有，不只依賴 Android 靜音 extras（部分引擎會忽略）。司機體驗及介面文案：「**講完自動停**」。

### 5.5 指令碼與語言模板（決定版，三語介面預備；本版只上架粵語）

- 狀態機只處理內部指令碼（`CommandCodes.kt`：`FIND_PARKING`、`SHOW_DETAILS`、`NEXT_OPTION`、`CONFIRM_TARGET`、`CANCEL`、`SET_DESTINATION`、`ON_SITE` 等），STT 文字先經語言層對應為指令碼。
- 「好／好的／Yes」只綁定當前有效之上一條問題；逾時、取消、終點／候選變更或對話中斷即失效。
- 所有 `spoken_cue`、本機錯誤、權限提示、「拍膠囊」提示，以及車場名稱、距離、貨幣、收費格式均經語言模板輸出，本版只填 `zh-HK`。
- `speech_locale` 預設 `zh-HK`（本產品 zh-HK＝粵語；`NativeConfig.SPEECH_LOCALE_DEFAULT`）；語音轉接層對應引擎 locale／voice，絕不靜默回退普通話；App 本版只送 `zh-HK`，不設語言設定介面。
- 地標中英別名共用同一 POI ID（一套地標庫）。

### 5.6 STT 信心度與防自我辨識（決定版）

- `STT_CONFIDENCE_THRESHOLD = 0.6` 只適用於引擎提供有效信心度之情況；信心度缺失或 −1 **不當作 0**，另行分支（只按指令碼匹配及當前問題處理）；不同引擎分數不可直接比較，按目標引擎真機校準。
- 副駕 TTS 播放、Google Maps 語音、藍牙切換及來電中斷期間不作確認判定；TTS 播放期間出現之「好」不得視作確認。

### 5.7 導航通知命中置信度（中英雙語過濾，決定版）

- 指路詞 Regex 排除中文「往／朝／靠左往／靠右往／出口」及英文「toward／towards／onto／exit／keep left／keep right／turn left／turn right／slight left／slight right／continue onto／then」等轉向與路牌雜訊（英文不分大小寫）。
- 三級分流：高置信度 → 靜默鎖定；含糊候選 → 澄清（膠囊已顯示時以 `ALERT_BREATHING`；Pre-FGS 沿用 Heads-up 通知）；只有轉向或無可用終點 → 忽略並保留現有終點。解析未中不清除已鎖定終點。
- Google Maps 介面語言與副駕語言互相獨立（Maps 設中文或英文均須解析）。

## 6. 六頁教學（Flutter Story Cards）

| 頁 | 內容 |
|----|------|
| 1 | 只認 Google Maps；其餘通知 Native 第 1ms drop |
| 2 | Android 紅色通用警告預防針；可選模式 B |
| 3 | 麥克風本地熱詞／「正在聽」知情同意；講咗熱詞或單擊膠囊後開始聆聽，講完自動停 |
| 4 | 語音優先示範動畫（口播→「詳細啲」→出卡） |
| 5 | **授權步驟**：說明後連續系統對話框 位置→麥克風→通知；再跳 SYSTEM_ALERT_WINDOW 設定頁；頂部權限橫額。**語音能力檢查**：經 `getVoiceCapability()` 顯示離線粵語 STT／TTS 四類結果及本機可用之行程語音模式；支援但未安裝時提示下載語音包（建議連 Wi-Fi、出發前完成） |
| 6 | 模式抉擇：右側「前往授權模式 A」（通知存取權）；左側「保持純淨模式 B」 |

頁數引用 `AppConfig.permissionTutorialPages`。頁面 5：**選「大概位置」＝位置未授權**（橫額顯示「需要精確位置」）。請選「精確位置」＋「使用 App 時」。

## 7. 權限狀態橫額（Flutter）

- 主畫面頂部與頁面 5 頂部；**只於缺必要權限時顯示**（讀 `getPermissionStatus()`）。
- 按鈕：「允許」（仍可彈系統對話框）或「前往設定」（永久拒絕／懸浮窗／通知存取權）。
- 文案結尾：「請泊好車後再處理」。
- **起步入口只檢查、不彈對話框**；系統對話框只喺頁面 5 或橫額主動撳「允許」時出現。

## 8. 偏好與口播

三段偏好（Flutter 設定頁經 `setPreferences()` 寫入 SharedPreferences，原生於下次請求讀取；STT 關鍵字可臨時覆蓋為 `METERS_FOCUS`）：

- `INDOOR_ONLY`／`SMART_HYBRID`／`METERS_FOCUS`
- 請求帶 `preference`；口播用後端回傳之 `spoken_cue`（客戶端唔自拼打分句）。
- 稀缺已滿：`await_voice_reply=true` → 按 §5.4「收音計時」聆聽（STT 不可用時不聆聽，按無回應處理）；「好」→ 同 session 重發 `expand_scarcity=true`；「唔使／等等」或超時 → 維持已滿狀態；畫面唔自動彈卡。
- 低水位：私家車有效空位 ≤3（`LOW_WATERMARK_VACANCIES`，伺服器判定）時照播 `spoken_cue`，例如「APM 只剩兩個位，隨時會滿，後備可以去 XX 停車場。」——只點名一個後備。
- 眾包黃旗：伺服器於 ≥2 個獨立來源時排序封頂，並於 `spoken_cue` 加「近期有司機回報疑似霸位，請留意現場」；單人回報只見黃色標籤「⚠️ 近期有司機回報疑似霸位」。客戶端照播，唔自行排除選項。

## 9. Pre-FGS 與膠囊出現邊界

- Pre-FGS：**僅** Heads-up 通知；無膠囊、無 GREY／AMBER overlay；停用 1km 預取／150m 追蹤／到場判定／ALERT 出發判定。
- 膠囊只於 Service 回報 `FGS_STARTED`（`startForeground` 成功）後繪出；冷態單擊膠囊**不**構成 FGS 啟動。

## 10. 今日行程專頁（Flutter，W-A.11）

- 經 bridge `getTodayTrips()` 讀原生 `TodayTripStore`；`onTodayTripsChanged()` 時刷新。
- 候選「可能已泊入 X」→ 司機一撳確認（`confirmParkedCandidate(sessionId)`）→ 原生送 `/v1/sync/outbox`（`DRIVER_CONFIRMED`），伺服器設 `ratable = true`，先開放 3 格反饋（`submitTripReport`）。
- 確認時離線：原生本機保留，網絡恢復自動送出；香港時間 00:00（`AppConfig.todayTripsResetTimeHkt`）與今日行程一併清除。

## 11. 資料新鮮度（stale／degraded）

- 新鮮度由伺服器計算：`server_now`、`source_snapshot_ts`、`processed_at`、`data_age_seconds`、`source_status`、`cards[].is_stale`；客戶端唔用本機時鐘推算。
- 咪錶卡 `is_stale=true`（`data_age_seconds > 600`）或 `source_status=degraded`：卡片顯示「咪錶資料暫時未更新」，**唔顯示**空位數字（`vacancies=-1`）。
- 口播只用保守講法：「附近可能有咪錶，資料暫時未更新」；絕不讀出咪錶具體空位數字。
- 膠囊燈號照常（資料過期唔等於網絡失敗，唔轉 GREY／ALERT）；室內場卡照常顯示。
- 虛擬鎖只作分流，commit 回 409 `LOCK_FULL` 時照播 spoken_cue；絕不講成已預約車位。
