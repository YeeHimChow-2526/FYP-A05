# Nav shell（Step 4）

- 行程中主畫面係 `NavShellScreen`：`sessionId != null` 同頁切換，唔 `Navigator.push`。
- `HKP_USE_REAL_NAVIGATION=true` 用 `GoogleNavEngine`（`google_navigation_flutter` 0.11.2）。`false` 用 `FakeNavEngine`（`Key('fake_nav_view')`），唔外跳 Maps。
- API key 只經 `android/local.properties` 的 `MAPS_API_KEY` → manifest placeholder。唔入庫。
- Navigation SDK 通知 id＝42；FGS 通知 id＝1。`taskRemovedBehavior=quitService`。
- 首次進入殼可能彈 Navigation T&C，要手動同意一次。
- Mute 只關 Google 轉彎聲（`silent`）。泊車 `spokenCue` 仍走 `speak`。
- 閘口座標：`GateCoordResolver` card → `kDemoCarparkCoords`（含 `CP_LANGHAM`）→ `LocalPoiTrie(name)` → 行程終點。guidance 失敗先 `launchGateNavigation`。
- Debug 位置：`adb emu geo fix <lng> <lat>`，再 `SIM_EVENT FIX`。無搖桿。
