# Nav shell（Step 4 / 4-fix）

- 冷啟動（`sessionId == null`）主畫面底層已係 `NavEngine.buildNavView()`（`HomeMapBackdrop`）。唔再用實色 ListView 做唯一主殼。相機／Fake 佔位對準香港 `22.3193, 114.1694`。
- Pre-trip：底部 fixed bar `PreTripBottomBar`（`Key('pre_trip_bottom_bar')`）＝出發／現場即泊／今日行程。偏好／熱詞喺設定 sheet，唔蓋滿地圖。
- In-trip：`sessionId != null` 切 `NavShellScreen`（唔 `Navigator.push`）。底欄消失，改圓形 icon（`chrome_compass`／`chrome_park`／`chrome_cards`／`chrome_mute`／`chrome_exit`，≥48dp，Tooltip，無長中文 label 列）。Exit 後返 pre-trip 底欄。
- Recommend 卡只喺 `cards` 非空時插入 `RecommendCardsStrip`；空卡唔佔位。
- `HKP_USE_REAL_NAVIGATION=true` 用 `GoogleNavEngine`（`google_navigation_flutter` 0.11.2）。`false` 用 `FakeNavEngine`（`Key('fake_nav_view')`，文案含 HK 座標），唔外跳 Maps。Fake 唔算真圖驗收。
- API key 只經 `android/local.properties` 的 `MAPS_API_KEY` → manifest `com.google.android.geo.API_KEY` placeholder。同檔 `HKP_USE_REAL_NAVIGATION=true` 先跑真圖。Secret 唔入庫。GCP 要開 Maps SDK for Android 同 Navigation SDK。黑圖／logcat `notAuthorized`／`API_KEY`＝未過，唔算 A2。
- compileSdk 36 係 in-repo：`android/build.gradle.kts` 喺 `evaluationDependsOn` 之前用 `pluginManager.withPlugin` 登記 `finalizeDsl(Action)`，喺 plugin `android {}` 之後、compileSdk lock 之前設 36。唔用 `afterEvaluate`（已 evaluated／lock 後會失敗）。唔靠手改 pub-cache。`minSdk` 仍 33。plugin 0.11.2 上游 `compileSdk = 35`，AAR metadata 要 36。
- Navigation SDK 通知 id＝42；FGS 通知 id＝1。`taskRemovedBehavior=quitService`。
- 首次進入殼可能彈 Navigation T&C，要手動同意一次。
- Mute 只關 Google 轉彎聲（`silent`）。泊車 `spokenCue` 仍走 `speak`。
- 閘口座標：`GateCoordResolver` card → `kDemoCarparkCoords`（含 `CP_LANGHAM`）→ `LocalPoiTrie(name)` → 行程終點。guidance 失敗先 `launchGateNavigation`。
- Debug 位置：`adb emu geo fix <lng> <lat>`，再 `SIM_EVENT FIX`。無搖桿。
