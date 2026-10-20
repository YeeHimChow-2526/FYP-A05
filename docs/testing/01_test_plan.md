# 泊車副駕 測試清單 v2.9.1

文件版本 v2.9.1｜Google Doc ID：1OaMcyeEU8m0HKuhhCLf2DCJX9iY0h1vu-ar7WGdd148｜來源＝實作版 PART 2 §十（測試策略）及 PART 1 §三（行程語音模式、收音計時、單一 AudioRecord → STT 工程閘門）｜最後更新 2026-10-20｜實作以實作版正文為準。

本清單分兩階段：**Phase A** 為開發前語音鏈驗證（指定裝置／引擎組合）；**Phase B** 為語音鏈跑通後之產品整合測試（29 項）。

> ⚠️ Phase A 通過只代表該指定裝置／引擎組合可行，並不代表所有 Android 13+ 手機均可用。minSdkVersion = 33 只屬安裝下限；其他機型一律以能力檢查判定行程語音模式（全語音／播報＋手動／純手動），並於 Phase B 第 25–29 項抽樣驗證。

**每項紀錄格式（必填）：** 裝置型號｜Android 版本（含安全性修補日期）｜語音服務（Speech Services by Google／STT 引擎名稱及版本）｜TTS 引擎及粵語語音包版本｜行程語音模式｜結果（通過／失敗）｜備註（log／錄影路徑）

---

## Phase A — 開發前語音鏈驗證（指定裝置／引擎組合）

目的：開發前確認目標裝置＋引擎組合可支援全語音模式。未通過即按實作版處理：改用可嵌入離線引擎（例如 SenseVoice／Paraformer／Whisper），或收窄語音支援範圍（不宣稱 Android 13+ 全面支援）。

### 準備

- 裝置：Android 13+（minSdkVersion = 33），已安裝 Google Play 服務及 Speech Services by Google（Google 語音服務）。
- 下載離線粵語（yue／zh-HK）語音辨識包及離線粵語 TTS 語音包；記錄下載前後狀態。
- 記錄裝置型號、Android 版本與安全性修補日期、Speech Services 版本、TTS 引擎版本、離線語言包版本（如可見）。
- 測試 App：debug 建置之語音驗證頁（sim/ 模擬殼），輸出結構化 log。

### A. 能力檢查

- A-1 離線粵語 STT：以裝置端辨識器（createOnDeviceSpeechRecognizer 或同等路徑）查詢 zh-HK／yue 支援，結果分「已安裝可用／支援但未安裝／不支援／未知」。
- A-2 離線粵語 TTS：檢查粵語語音（voice）已安裝並標示可離線使用；setLanguage 成功不足以作準。
- A-3 四類結果逐一覆蓋：刪除語言包 → 支援但未安裝；重新下載 → 已安裝可用；切換至不支援粵語之引擎 → 不支援；查詢逾時 → 未知。
- A-4 按結果判定行程語音模式（全語音／播報＋手動／純手動），與實作版 PART 1 §三對照表一致。
- 通過準則：四類結果均可正確辨別；每程開程靜默重查於 VOICE_CAPABILITY_CHECK_TIMEOUT_MS 內返回或逾時，並且不阻塞 FGS_STARTED。

### B. 裝置端 STT（飛行模式）

- B-1 開啟飛行模式（Wi-Fi、流動數據全關），以裝置端辨識器辨識粵語句子：「泊車助手，去朗豪坊」「詳細啲」「有冇第二個」「好」「取消」。
- B-2 確認全程只走裝置端路徑、沒有雲端 STT 後備；單獨使用 EXTRA_PREFER_OFFLINE 不作準。
- B-3 記錄每句文字結果、信心度（包括缺失或 −1 之情況）及延遲（音訊結束至結果）。
- 通過準則：飛行模式下取得粵語文字結果，無網絡錯誤；延遲按實測記錄（不預設數字）。

### C. 外部 PCM 注入證明

- C-1 以 App 之單一 AudioRecord 擷取 PCM（記錄採樣率、聲道、編碼，例如 16 kHz／單聲道／PCM 16-bit），經管道／檔案描述符注入 STT（Android 13+ RecognizerIntent.EXTRA_AUDIO_SOURCE 或同等機制）。
- C-2 證明結果來自注入音訊：注入預錄 WAV（現場無人講話）仍得正確文字；注入靜音 PCM 同時對住咪講話，結果須為空／無語音。
- C-3 確認引擎沒有自行開咪（系統麥克風使用指示、AudioRecord 佔用狀態、log）。
- C-4 管道生命週期：本句結束 STT 輸入後，AudioRecord 可繼續作熱詞擷取；來電、撤銷權限、行程結束時完全停止 AudioRecord。
- 通過準則：C-2 兩項均符合；格式轉換（如需要）有記錄。

### D. TTS 與防自我辨識

- D-1 離線粵語 TTS 讀出 spoken_cue 範例「前方首選朗豪坊停車場，一百八十米，剩十二個位，首小時三十二蚊。」，確認為粵語發音（並非普通話）。
- D-2 副駕 TTS 播放含「好」字之句子期間，不得觸發確認。
- D-3 Google Maps 導航語音、藍牙車機切換、來電中斷期間講出或播出「好」，均不得觸發確認。
- 通過準則：零誤確認；中斷後狀態正確恢復。

### E. 收音計時觀察

- E-1 LISTEN_OPENING_WAIT_S = 3：提示音／TTS 播完且音訊輸入就緒後先開始計；期間無人聲即關閉聆聽。
- E-2 LISTEN_END_SILENCE_S = 0.8：偵測到人聲後連續靜音約 0.8 秒即結束本句（講完自動停）。
- E-3 LISTEN_MAX_UTTERANCE_S = 8：由首次偵測到人聲起計，持續講話達 8 秒即截斷。
- E-4 STT_RESULT_TIMEOUT_S = 5：音訊已停止但引擎未回傳結果，按「聽唔清」處理。
- E-5 確認計時由本機語音控制層（VAD）擁有，引擎忽略 Android 靜音 extras 時仍正確；記錄實測值供真機調校。

### Phase A 結論紀錄

- 結論：通過（進入全語音模式開發）／未通過（改用可嵌入離線引擎（例如 SenseVoice／Paraformer／Whisper），或收窄語音支援範圍）。
- 紀錄：裝置型號、Android 版本、Speech Services 版本、TTS 引擎及語音包版本、A–E 各項結果。

---

## Phase B — 語音鏈跑通後之產品整合測試（29 項）

建議次序：一 → 六。每項記錄裝置／Android 版本／語音服務版本／行程語音模式／通過或失敗。

### 一、模式與權限（1–5）

1. 全語音模式：STT＋TTS 均可用 → 口播＋聆聽；熱詞只於熱詞能力可用且設定未關閉本程熱詞時啟用；FGS 以 location|microphone 運行。
2. 播報＋手動模式：STT 不可用、TTS 可用 → 照常口播、不聆聽、不設熱詞；FGS 只以 location 類型運行；司機拍膠囊顯示推薦卡片，卡片提供「下一個／確認／取消」手動按鈕；仍然不自動彈卡。
3. 純手動模式：TTS 不可用 → 不口播、不聆聽、不設熱詞；卡片手動操作同第 2 項。
4. RECORD_AUDIO 未授權：播報＋手動／純手動照常開程（只以 location 類型啟動 FGS）；權限狀態橫額提示補授權以啟用語音輸入。
5. 頁面 5 能力檢查及下載提示；每程開程靜默重查不阻塞 FGS_STARTED、開程時不自動彈下載提示；行程中撤銷 RECORD_AUDIO 按模式處理（系統終止進程時本程按既有規則放棄）；MIC_READY 失敗只停用語音輸入、TTS 可用則照常口播。

### 二、語音互動（6–11）

6. 熱詞＋單一 AudioRecord：「泊車助手，去朗豪坊」句首完整；PCM 交裝置端 STT。
7. 收音計時：開口等待 3 秒、講完 0.8 秒自動停、單句上限 8 秒、STT 結果逾時 5 秒；教學頁面 3 文案為「講完自動停」。
8. 結束本句 STT 與停止 AudioRecord 分開：每句後結束 STT 輸入；本程熱詞開啟時熱詞擷取持續；來電／撤銷／行程結束完全停止。
9. 「好／好的／Yes」只綁定當前有效之上一條問題；逾時、取消、終點／候選變更、對話中斷後即失效。
10. 防自我辨識：副駕 TTS、Google Maps 語音、藍牙切換、來電中斷期間之「好」均不會確認。
11. STT 信心度：低於 STT_CONFIDENCE_THRESHOLD = 0.6 即口播「太嘈聽唔清，稍後再試」；信心度缺失或 −1 走獨立分支（不當作 0）；分數只按目標引擎校準。

### 三、入口與 Maps（12–16）

12. B-1 路徑 1A Hey Google／App Actions（實驗性能力）：按指定 intent、語言及裝置驗證；驗證通過後方可啟用，不列入本版必要交付。
13. B-1 路徑 1B 已開程後之本機語音互動（熱詞／拍膠囊）：屬必要交付，不受 1A 實驗性限制。
14. Maps 通知中英雙語過濾：中文「往／朝／靠左往／出口」及英文 toward／towards／onto／exit／keep left 等不被誤當終點；Maps 介面語言設中文或英文均正確（與副駕語言互相獨立）。
15. 三級分流：高置信度靜默鎖定；含糊候選澄清（膠囊已顯示時 ALERT_BREATHING；Pre-FGS 走 Heads-up，不顯示琥珀膠囊）；只有轉向即忽略並保留現有終點；解析失敗不清除已鎖定終點。
16. 本程未有終點：只由「行車未設終點」路徑提示一次；STT 不可用時不要求「請拍一拍膠囊答我」，地名留待泊好車後輸入。

### 四、行程與到場（17–20）

17. 六種起步方法＋現場即泊之 FGS 啟動握手（FGS_STARTED、FGS_BOOTSTRAP_TIMEOUT_MS = 2000）；語音能力重查與握手並行、不阻塞。
18. APPROACH_MUTED：80m＋<8km/h 連續 3 秒，或 150m 內 GPS 斷訊 >5 秒；只靜音主動口播、行程繼續；距離持續 >200m 達 10 秒退回巡航。
19. PARKED_CONFIRMED：目標 150m 內（或地庫斷訊）＋熄匙 → 今日行程候選「可能已泊入 X」→ 司機一撳確認先 ratable = true；鎖只於 PARKED_CONFIRMED 或 30 分鐘到期釋放。
20. 行車全程原生 Kotlin（無背景 Flutter 引擎）；OEM 殺 FGS 即 abandon trip；今日行程待確認離線保留、網絡恢復自動送出、香港時間 00:00 清除。

### 五、推薦與資料（21–24）

21. speech_locale：Request 省略或送 zh-HK → Response 回 zh-HK；不支援之值明確回退 zh-HK 並以 speech_locale_fallback = true 註明；語音轉接層絕不靜默回退普通話；App 本版只送 zh-HK。
22. 語言模板：spoken_cue、本機錯誤、權限提示、「拍膠囊」提示、車場名稱／距離／貨幣／收費格式均經 zh-HK 模板輸出；中英別名指向同一 POI ID。
23. 低水位 LOW_WATERMARK_VACANCIES = 3 口播並只點名一個後備；眾包永不排除（單人 −45、≥2 來源排序封頂＋黃旗口播）。
24. source_status ok／degraded 與 is_stale：咪錶過期或 degraded 不讀實數；L_i 每次請求即時 count()、不入快取。

### 六、OEM／真機必測（25–29）

25. Xiaomi／HyperOS：背景殺程、麥克風佔用提示、語音能力檢查結果及對應模式。
26. Samsung／One UI：同上，另測 Samsung TTS 為預設引擎時之 TTS 能力判定。
27. Google Pixel（參考機）：Phase A 指定組合之回歸測試。
28. 車載藍牙 ACL 連線／斷開；藍牙 SCO／HFP 音訊路由切換及 NS／AEC 掛載條件。
29. 市區高樓 GPS 漂移、地庫斷訊、實際記憶體上限（dumpsys meminfo PSS）及單程耗電。
