# 規格改動待辦（SPEC_CHANGES）— v2.9.1

用途：記低需要改規格嘅發現；人類定期帶給規格守護者，改好 Doc 後清空。

最後更新：2026-10-25

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

### SC-003
- 日期：2026-10-21
- 來源：真機測試（模擬器閘口 A）
- 裝置（如適用）：Pixel 8 模擬器｜Android 15（API 35）｜不適用
- 發現：P 膠囊冇底色，同桌面背景溶埋一齊，睇唔清楚。實作版只定義咗追問卡底色（`#121212`，約 80% 不透明 `0xCC121212`），原生 P 膠囊嘅尺寸、底色、外圈線寬、各狀態外圈色碼，同 Status Pip 嘅直徑同綠／黃／紅色碼都未定義。`app_colors.dart` 喺規格入面只有檔名，冇色碼，而且原生膠囊讀唔到 Dart 檔。
- 建議改動：喺實作版 `NativeConfig` 區塊加入膠囊視覺常數：底色 `0xCC121212`（唔支援半透明時用實色 `#121212`）、膠囊 48dp × 48dp、外圈線寬 3dp、Status Pip 直徑 10dp；灰、琥珀、綠、黃、紅五個色碼交規格守護者定。`app_colors.dart` 用同一組色碼。
- 影響任務編號／常數名／檔案：W-A.17；`NativeConfig.kt`、`FloatingBubbleService.kt`、`lib/core/constants/app_colors.dart`
- 類別：規格缺漏
- 狀態：待處理

### SC-004
- 日期：2026-10-21
- 來源：Pi
- 裝置（如適用）：不適用
- 發現：`NEARBY_READY` 之 `StatusPip` 未定義應讀 `/v1/recommend-and-route` 邊個欄位（例如首選卡 `cards[0].vacancies`）、以咩空位數字門檻判定 `GREEN`／`YELLOW`／`RED`，以及 `vacancies == -1`（未知／咪錶過期或 `source_status=degraded`）時應顯示邊種 Pip。現行程式維持 `pip = StatusPip.NONE`，未自行發明門檻。
- 建議改動：於實作版真理表／`NativeConfig` 定義 `NEARBY_READY` StatusPip 之來源欄位、`GREEN`／`YELLOW`／`RED` 空位門檻，以及 `vacancies == -1` 之 Pip 狀態。
- 影響任務編號／常數名／檔案：W-A.1、W-A.17；`StatusPip`；`TripStateMachine.kt`、`ApiClient.kt`、`FloatingBubbleService.kt`
- 類別：規格缺漏
- 狀態：待定

### SC-005
- 日期：2026-10-22
- 來源：Pi
- 裝置（如適用）：不適用
- 發現：審計 E1、E2、E3、E4、E6 屬 Step 2／Step 3 範圍，本步不改對應生產碼。包括 B-2 分享解析、LocalPoiTrie、DistanceTracker、熄匙 receiver 註冊、TodayTripStore、release 的 Fused／STT 實作、HTTP header 與逾時、真實座標傳遞、activeService。
- 建議改動：待後續 Step（Step 2／Step 3）處理。
- 影響任務編號／常數名／檔案：W-A.2、W-A.4、W-A.9、W-A.11、W-A.16；`ShareIntentReceiverActivity`、`LocalPoiTrie.kt`、`DistanceTracker.kt`、`EngineShutdownReceiver.kt`、`TodayTripStore.kt`、`CorePorts.kt`、`ApiClient.kt`、`MainActivity.kt`
- 類別：其他
- 狀態：待後續 Step（Step 2／Step 3）處理

### SC-006
- 日期：2026-10-22
- 來源：Pi
- 裝置（如適用）：不適用
- 發現：行程中更改目的地（Destination 變更）時，應否重置 `jitTriggered` 以允許新目的地觸發預備區 JIT？目前規格未明文定義，暫維持每程唯一一次之單向鎖。
- 建議改動：待決規格項。規格守護者需明文定義改目的地是否重設 1km JIT 鎖。
- 影響任務編號／常數名／檔案：W-A.1；`jitTriggered`；`TripStateMachine.kt`；`NativeConfig.TRIGGER_DISTANCE_M`
- 類別：規格缺漏
- 狀態：待決

### SC-007
- 日期：2026-10-25
- 來源：Pi
- 裝置（如適用）：不適用
- 發現：
  1. `sim` 事件表缺 `FORCE_SCARCITY` 同擴大後有位 fixture `recommend_scarcity_expanded.json`。
  2. 規格冇定義「擴大後仍無位」點讀，現行實作用 `ZhHkTemplates.NO_MORE_OPTIONS`。
- 建議改動：補 `FORCE_SCARCITY` 同 `recommend_scarcity_expanded.json`；明文定義擴大後仍無位嘅口播句。
- 影響任務編號／常數名／檔案：`FixtureApiTransport.kt`、`SimEventReceiver.kt`、`recommend_scarcity_expanded.json`、`ZhHkTemplates.NO_MORE_OPTIONS`、`docs/specs/04_mock_and_sim_shell.md`
- 類別：規格缺漏
- 狀態：待處理

### SC-008
- 日期：2026-10-25
- 來源：Pi
- 裝置（如適用）：不適用
- 發現：熱詞待命與單次聆聽窗未分離。`stopListening()` 應只收本次聆聽窗；`startListening(hotword=true)` 開啟的待命保持至行程拆除。`setUtteranceListener(null)` 與 `onDestroy` 要清 ports。pending 答完即收窗。原因：Step 2.2 smoke 收音卡死。SimShellTest 一個測試開頭補 `Bindings.clearPorts()`，隔離全局 voiceInput 待命狀態。
- 建議改動：規格明文寫低熱詞待命與聆聽窗分離、答完收窗，以及拆除時清除待命。
- 影響任務編號／常數名／檔案：`SimVoiceInput.kt`、`SttVoiceInput`、`FloatingBubbleService.kt`、`docs/specs/04_mock_and_sim_shell.md`
- 類別：規格缺漏
- 狀態：待處理

### SC-009
- 日期：2026-10-25
- 來源：Pi
- 裝置（如適用）：不適用
- 發現：客戶端解析 recommend／409／429 未有共用完整 JSON 解析。待同步 impl Doc §10.1／§10.3。
- 建議改動：客戶端解析（SC-009）：
  1. 回應用完整 JSON 解析（共用 net/ApiResponses.kt，debug fixture 同 release 用同一套），未知欄位一律忽略。
  2. recommend 回 2xx，但 body 唔係合法 JSON 物件、缺非空 `status`、`cards` 唔係陣列或者有非物件元素 → 當網絡失敗處理（分級重試再 GREY），禁止當 SUCCESS。
  3. 缺省值：`await_voice_reply` false、`source_status` "ok"、`vacancies` -1（禁止當 0）、`is_stale` false、`distance_meters` 0；卡嘅 `spoken_cue` 係空就用頂層 `spoken_cue`。
  4. 429 嘅等候秒數先用 `Retry-After` header，冇先用 body 嘅 `retry_after_seconds`；409 只讀頂層 `spoken_cue`。
  遺留項：
  - R9：release baseUrl 同 API key 未注入
  - R10：sim 預設 recommend 用 hardcode
- 影響任務編號／常數名／檔案：Step 2.2b；`net/ApiResponses.kt`、`HttpApiTransport`、`FixtureApiTransport.kt`、`docs/specs/03_api_and_local_trie.md`
- 類別：規格缺漏
- 狀態：待處理

### SC-010
- 日期：2026-10-25
- 來源：Pi
- 裝置（如適用）：不適用
- 發現：首選確認後 commit／409／Google Maps 導航未有客戶端契約。待同步 impl Doc。
- 建議改動：客戶端導航（SC-010）：
  1. 「好／去呢個」只喺 `RECOMMENDATION_CONFIRM` 窗有效；等首選 TTS `onFinished(true)` 且 `isMicActive` 先開窗；開窗前 `speechHeard=false`。
  2. commit 200 → `NAV_TO_GATE`＋`NavigationPort`；URI `google.navigation:q=<URLEncoder UTF-8>&mode=d`，package `com.google.android.apps.maps`，`FLAG_ACTIVITY_NEW_TASK`。
  3. 409 → 口播 spoken_cue；唔 NAV／唔 Maps；記錄已滿 id；其後「有冇第二個」重查並過濾；空則「暫時冇其他選擇」。
  4. FloatingCardView／手動掣／本地切卡 → 2.4；R11 唔屬本步。
  遺留項：
  - R11：仍遺留（FloatingCardView／手動掣／本地切卡，不屬 Step 2.3）
- 影響任務編號／常數名／檔案：Step 2.3；`TripStateMachine.kt`、`NavigationPort`、`FloatingBubbleService.kt`、`docs/specs/03_api_and_local_trie.md`
- 類別：規格缺漏
- 狀態：待處理

### SC-011
- 日期：2026-10-25
- 來源：Pi
- 裝置（如適用）：不適用
- 發現：追問卡出卡、本地切卡、確認窗逾時後仍可確認、409 不收卡，未有客戶端契約。待同步 impl Doc。
- 建議改動：客戶端追問卡（SC-011）：
  1. `FloatingCardView` 只喺追問或手動降級拍膠囊時出；高度跟 `OVERLAY_MAX_CARD_HEIGHT_DP`／`OVERLAY_MAX_SCREEN_COVERAGE`；底色 `#121212` 約 80%；不設自動收起。背景模糊用 window blur-behind（API 31+ 兼且裝置支援），否則用實色；唔准對卡本身用 RenderEffect。
  2. `EXPANDED` 燈 `GREY_STEADY`。到場取樣唔強制踢走 `EXPANDED`；非 EXPANDED 進入 `APPROACH_MUTED` 先收卡。收卡還原：仍符合到場 → `APPROACH_MUTED`；有推薦 → `NEARBY_READY`；否則 `CRUISING`。
  3. 卡可見或 `EXPANDED` 時，「好」經 parse 綁 `RECOMMENDATION_CONFIRM`（自聽／低信心唔確認）。逾時唔收卡。取消無論 pending／是否播緊都停口播並收卡。窗內「詳細啲／有冇第二個」先打斷再出卡／切卡。
  4. 已有滿鎖 id 時「下一個」立即過濾重查，唔本地翻頁、唔等 350ms。否則本地切卡，`CARD_PAGE_TRANSITION_MS` 前後對 epoch；收卡／取消／進 `APPROACH_MUTED`／commit 成功／熄匙拆除令 epoch 失效。
  5. 409 不收卡。`GREY_BREATHING` 拍膠囊唔自動出卡。詳情：stale 或咪錶 degraded 讀 `METER_STALE_SPOKEN`；否則 `cardDetails`。
  6. Sim：`CARD_TAP`／`CARD_SWIPE`／`CARD_NEXT`／`CARD_CONFIRM`／`CARD_CANCEL` 經 debug `Bindings.dispatchSim`；`FORCE_TWO_INDOOR` → `recommend_success_two_indoor.json`。預設 langham 不變。
  遺留項：
  - R11：熄匙 `tts.stop` 仍唔屬本步
  - `GREY_BREATHING` 拍膠囊仍只設 `manualRetryRequested`，冇新增重試迴圈
- 影響任務編號／常數名／檔案：Step 2.4；`FloatingCardView.kt`、`FloatingBubbleService.kt`、`TripStateMachine.kt`、`CommandCodes.kt`、`docs/specs/01_ui_and_voice_fsm.md`、`docs/specs/04_mock_and_sim_shell.md`
- 類別：規格缺漏
- 狀態：待處理

### SC-012
- 日期：2026-10-25
- 來源：Pi
- 裝置（如適用）：不適用
- 發現：熄匙後口播會讀完先停；GREY 拍膠囊只設旗、冇發請求。待同步 impl Doc。
- 建議改動：
  1. 熄匙即停口播次序：`shutdownInProgress` → 清 grey 重試旗 → 收卡 → `invalidate(DIALOGUE_INTERRUPTED)` → 停咪 → `tts.interrupt()`。`finishTeardown` 喺 invalidate 之後再 interrupt 做保底。淡出 `TEARDOWN_FADE_MS` 期間遲到 recommend／commit 丟棄、唔出聲、唔出卡、唔開聆聽窗、唔再叫 TSM。
  2. GREY 拍膠囊：每次最多一個 `DRIVER_ACTIVE` 請求；喺途唔重發；`launchRecommend` 嘅 `finally` 清旗；唔彈卡。失敗留喺 GREY，唔自動再試。
  3. PLACE_CLARIFY：保留 `ZhHkTemplates.PLACE_CLARIFY = "邊一區？"`，配 `withVoiceReplyPrompt`。spec 02「去邊度？請拍一拍膠囊答我」待同步 impl Doc 統一，本步唔改字串。
- 影響任務編號／常數名／檔案：Step 2.5；`FloatingBubbleService.kt`、`ZhHkTemplates.PLACE_CLARIFY`
- 類別：規格缺漏
- 狀態：待處理

### SC-013
- 日期：2026-10-26
- 來源：評估（Step 3.1 資料同程式預建，Pi 落地）
- 裝置（如適用）：不適用
- 發現：本機字典樹資料範圍、正規化、命中優先次序未有規格。待同步 impl Doc。
- 建議改動：本機字典樹（SC-013）：
  1. 範圍：3.1 只交資料＋`LocalPoiTrie.kt`（純 Kotlin，冇 android.*），**未接駁**；接 `TRIE_MISS_*`／行程流程屬 3.1b。
  2. 資料 `assets/hk_poi_master.json`，由 `tools/poi/build_poi_master.py` 產生，四個來源：地政總署地名（CSDI，只取 Official 且 Area／Town）、地政總署 iGeoCom 商場（TYPE=MAL）、運輸署停車場（全部）、民政事務總署 18 區（另作 t=D）。唔收：村（Village）、屋邨資料集、非運輸署停車場。來源、sha256 見 `tools/poi/SOURCES.md`。
  3. 大小上限 ≤153,600 bytes，依據 ARCHITECTURE L697「＜150KB」。超出只裁商場：先裁 150m 內冇運輸署停車場嘅商場，再裁近停車場嘅，`aliases_overlay.json` 指向嘅商場最後先裁；同層由 id 大到細裁。（朗豪坊商場附近冇運輸署停車場，所以要保護。）
  4. 正規化（Python／Kotlin 一致）：NFKC → 小寫 → 刪 Unicode P*／Z* → 最多剝一個後綴（最長優先：停車場、停車塲、商場、購物中心、carpark、carparks、parking、shoppingmall、shoppingcentre、mall）→ 剝完係空就用剝之前嘅值。唔做簡繁轉換，唔剝「第X期」。normalize(zh) 少過 2 字唔收。
  5. 查詢：先完整 key；冇就搵 query 最長而長度 ≥2 嘅完整 key 前綴；都冇就 Miss。多個 POI 時類型優先 A＞M＞C＞D；最高類型只得一區 → Hit（id 最細）；多區 → Ambiguous，每區一個，按區名排，最多 3 個。
  6. 「朗豪坊」靠剝「商場」後綴命中 iGeoCom 朗豪坊商場（油尖旺）。
  7. 18 區 POI：zh 用全名（如「南區」），d 用無「區」短名；短名 ≥2 字先加做別名（南／東／北冇別名）。
  8. 「數據來源」畫面延後到 3.4；JSON `attribution` 已備好字串。
  遺留項：
  - 3.1b：接駁 `TRIE_MISS_RESOLVE_OK`／`TRIE_MISS_NETWORK_FAIL`／`TRIE_MISS_AMBIGUOUS`，asset 載入
  - 3.4：數據來源畫面
- 影響任務編號／常數名／檔案：Step 3.1；`LocalPoiTrie.kt`、`NativeConfig.TRIE_LOOKUP_BUDGET_MS`、`assets/hk_poi_master.json`、`tools/poi/*`、`docs/specs/03_api_and_local_trie.md`
- 類別：規格缺漏
- 狀態：待處理

### SC-014
- 日期：2026-10-26
- 來源：評估（Step 3.1b 代碼預建，Pi 落地）；Gemini 紅隊 RT-1…RT-4
- 裝置（如適用）：不適用
- 發現：
  1. 03 L123「Miss → 雲端 Geocoding（語音路徑即時）」——後端未有 geocode endpoint。
  2. 03 L124 隱式回顯【】[] 係標記，唔應讀出。
  3. 01 L36「每次只口播一次」對地名澄清唔合用：中途改終點再含糊要再問。
- 建議改動：
  1. 語音 SET_DESTINATION 經 LocalPoiTrie：Hit 鎖定＋坐標入 dest_lat/dest_lng；Ambiguous →「邊一區？」＋ALERT＋CLARIFY_DESTINATION（答案只按區短名，剝尾「區」；或答案 Hit 落候選區且該區候選唯一，RT-2）；「好」唔鎖；新 SET_DESTINATION 重新解析；其他忽略至逾時（LISTEN_OPENING_WAIT_S）。
  2. Miss：用講嘅名鎖定、冇坐標（dest 0.0）、log TRIE_MISS、唔入 ALERT；全語音講 directLock(name)。待 geocode endpoint。
  3. 回顯：完整 key → directLock(講嘅名)；prefix／澄清 →「收到，為你鎖定<區>區<全稱>，搜尋緊周邊車位。」（18 區只講全名）；只限全語音。
  4. destinationName：Hit／Miss = 講嘅名；澄清解決 = 選中 POI 嘅 poi.zh；鎖定後唔即時 recommend。
  5. **ALERT 每程一次嘅例外**：成功鎖定終點時移除 PLACE_CLARIFICATION（及 GREY 期間 deferred 嘅同一 cause，DP10b），之後再含糊會再問；未解決期間唔重複；其他 AlertCause 照舊每程一次。依據 01 L36「維持至設終點」。`destLat`／`destLng`／`destinationPoiId` 與 spokenAlerts／deferred 清理須在 NAV_TO_GATE early return 之前（RT-3）。
  6. Trie provider 可注入；production 行程開始背景 pre-warm（thread `poi-trie-prewarm`），未好就同步載入；載入失敗 = 舊行為。
  7. 資料：aliases_overlay 加 海港城／又一城／太古城；asset 153,525 bytes；擠走 北角匯一期、欣榮商場、喜盈商場。
  8. clarify 期間：明確 FIND／CANCEL 會清 pending 再行今日路徑；其餘配唔到嘅回覆忽略至逾時且**唔**設 speechHeard（RT-4）；「好」唔鎖；澄清解決後 destinationName = 選中 POI 全稱（poi.zh）＋該 POI 坐標，Hit／Miss 仍用講嘅名。
  9. **RT-1**：Ambiguous 後若非 ALERT_BREATHING 或因靜音 speakIfActive 返 null → 清 pending；若 ALERT 下僅因 PLACE_CLARIFICATION 已播過而抑制口播 → 保留 pending＋新候選並重開聆聽窗（T15）。
  已接受、唔改代碼嘅遺留項（文件化）：
  - 被大小上限擠走 3 個商場：北角匯一期（變 Ambiguous 東／離島）、欣榮商場、喜盈商場（Miss）
  - 「太古城道」prefix-hit 太古城中心（之前 Miss）
  - 又一城按區界多邊形落深水埗
  - 最長前綴陷阱：「旺角朗豪坊」→ 旺角
  - sim `TRIE_MISS_RESOLVE_OK`／`lockOrStartTrip` 鎖定都會重置 PLACE_CLARIFICATION
  - APPROACH_MUTED 時唔播反問、唔開聆聽
  遺留項：
  - geocode endpoint（Miss 即時路徑）
  - 動態澄清選項（「邊一區？灣仔定大埔？」）—— 延後到之後一個細 TASK
  - B-2／TRIE_MISS_* sim hook 接真 trie（3.2）
  - origin 坐標（originLat/Lng 仍 0.0）
  - spec 02 L110「去邊度？請拍一拍膠囊答我」同 PLACE_CLARIFY 統一（承 SC-012 #3）
- 影響任務編號／常數名／檔案：Step 3.1b；FloatingBubbleService.kt、TripStateMachine.kt、CommandCodes.kt（ZhHkTemplates.implicitLockEcho）、poi/PoiTrieProvider.kt、assets/hk_poi_master.json、tools/poi/aliases_overlay.json、tools/poi/SOURCES.md
- 類別：規格缺漏
- 狀態：待處理

### SC-015
- 日期：2026-10-26
- 來源：評估（Step 3.2 代碼預建，Pi 落地）
- 裝置（如適用）：不適用
- 發現：
  1. ARCH §5.4／03 L123 要 B-2 Miss 背景 Geocoding＋短網址展開，後端冇 endpoint。
  2. FBS onStartCommand 一律重開程，行程中再分享會斷 session。
  3. sim TRIE_MISS_NETWORK_FAIL 未口播「網絡唔順，遲啲再試」（PROGRESS L96）。
- 建議改動：
  1. ShareIntentReceiverActivity：SEND text/plain；權限閘（B_2 外部入口）；同 FgsBootstrapActivity 握手；空文字唔開 FGS（SHARE_EMPTY）。
  2. 標題（第一行，去 URL／坐標；後備 EXTRA_SUBJECT）經 LocalPoiTrie；掉針 Regex（≥3 位小數、合法經緯度）有坐標即鎖，唔理 trie。
  3. 分享 Hit 於 FULL_VOICE 口播 `directLock`／`implicitLockEcho`；掉針與 Miss 名鎖靜默；Ambiguous 重用 3.1b 澄清；純 URL／冇標題 → ALERT（PLACE_CLARIFY）。
  4. Miss：AMBER＋B2MissResolver——production 即刻名鎖（坐標 null，唔 ALERT）；debug 等 TRIE_MISS_*。
  5. TRIE_MISS_RESOLVE_OK 經 trie（stringValue → 待解析標題 → destinationName → 朗豪坊）；NETWORK_FAIL → GREY＋口播一次（守 APPROACH_MUTED／MANUAL_ONLY）、拍膠囊重試解析；AMBIGUOUS → ALERT、唔設澄清 pending。
  6. 行程中分享：同 session 改終點，唔重開程。
  已接受、唔改代碼嘅遺留項：
  - 唔展開短網址、唔 geocode（DP4）
  - 掉針唔限香港範圍
  遺留項：
  - geocode／短網址 endpoint 同 ApiClient B2_RESOLUTION 實際 caller（3×2.5s＋2×1s）
  - spec 02 L110「去邊度？請拍一拍膠囊答我」同 PLACE_CLARIFY 統一（承 SC-012 #3／SC-014）
  - 真機 Maps 分享文字格式實測（標題抽取、掉針格式）
- 影響任務編號／常數名／檔案：Step 3.2；ShareIntentReceiverActivity.kt（新）、share/ShareIntake.kt（新）、FloatingBubbleService.kt、TripStateMachine.kt、debug/di/Bindings.kt、release/di/Bindings.kt、AndroidManifest.xml
- 類別：規格缺漏
- 狀態：待處理

### SC-016
- 日期：2026-10-26
- 來源：評估（Step 3.3 代碼預建，Pi 落地）
- 裝置（如適用）：不適用（藍牙 ACL 待真機）
- 發現：
  1. FusedLocationSource 係 stub，release 冇距離；DistanceTracker／EngineShutdownReceiver 未落地（SC-005 延後項）。
  2. FBS onMotionSample `exitHoldS > 0` 即 return，真 GPS 會餓死 JIT。
  3. 熄匙 release／候選用第一張卡，唔係已 commit 車場。
  4. 熄匙冇去抖、冇行車防護、冇重入防護；NAV_TO_GATE 期間距離唔更新。
  5. 01 L74「目標場」冇坐標可量；「地庫斷訊狀態」、DISTANCE_SANITY_TOLERANCE_MULTIPLIER 冇定義；04 L120 Flow port 同代碼唔一致；AG L411 同熄匙藍牙描述有出入。
- 建議改動：
  1. DistanceTracker（純 Kotlin）：haversine 量終點；三級採樣（150 入、200 出）；低速／退出持續秒數由 fix timestamp 計；精度 >VALID_FIX_MAX_ACCURACY_M 忽略；斷訊每段一次 stale sample。
  2. EngineShutdownDetector＋EngineShutdownReceiver：FGS 期間動態註冊（RECEIVER_NOT_EXPORTED）；車充必聽、藍牙 ACL 只限有 BLUETOOTH_CONNECT 且 class 係 CAR_AUDIO／HANDSFREE；ENGINE_OFF_DEBOUNCE_MS 去抖；近期車速 ≥ENGINE_OFF_MAX_SPEED_KMH 忽略。
  3. 熄匙判定 = APPROACH_MUTED 或最後已知距離 ≤150m；release／候選用已 commit 車場。
  4. release 用 PlatformLocationSource（LocationManager，冇新依賴）。
  已接受、唔改代碼嘅遺留項：
  - 「目標場」以終點坐標代替（卡／commit 冇坐標）
  - 候選只存記憶體（TodayTripStore 留 3.4）
  - 紅燈停車拔線（範圍外）會結束本程
  - debug build 冇真 GPS；GPX 回放要 release build
  遺留項：
  - 卡片／commit 帶車場 lat/lng，或閘口坐標
  - 用 Flutter 頁面 6 配對車機 MAC 代替 device class
  - A-1 ACL 連線 Heads-up
  - DISTANCE_SANITY_TOLERANCE_MULTIPLIER 定義或刪除
  - 04 L120-122 改成 listener 式 port；04 L41 release fixture
  - 真機：去抖／速度門檻、藍牙熄匙、耗電 1–3%
- 影響任務編號／常數名／檔案：Step 3.3；W-A.4／W-A.9／W-A.17；VALID_FIX_MAX_ACCURACY_M、DISTANCE_TICK_MS、ENGINE_OFF_DEBOUNCE_MS、ENGINE_OFF_MAX_SPEED_KMH；DistanceTracker.kt（新）、EngineShutdownDetector.kt（新）、EngineShutdownReceiver.kt（新）、FloatingBubbleService.kt、NativeConfig.kt、di/CorePorts.kt、debug/di/Bindings.kt、debug/sim/SimLocationSource.kt、debug/sim/FixtureApiTransport.kt、release/di/Bindings.kt
- 類別：規格缺漏
- 狀態：待處理
