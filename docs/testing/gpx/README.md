# 模擬器行車 GPX（正常模式）

給 emulator-5554 的 Extended Controls → Location → Routes → **Import GPX**。不要用起終點點選：香港路線的 Play 常常是灰的。這兩份是已展開的 `<trk><trkseg><trkpt>`，每點相距約 8 m、相隔 1 秒（約 29 km/h），時間單調遞增。

設定保持 **正常**（不要 DEMO）。正常模式用模擬器 GPS 和真 API，不走 fixture／固定重播。

## 載入

1. 模擬器右側 `...` → Location → Routes。
2. Import GPX，選下面其中一個檔。
3. 在路線清單點選剛匯入的名稱，再按 Play。若 Play 仍是灰的，先確認清單裡有這條軌跡，而不是空白的起終點欄。
4. App 用 **正常** 模式。目的地要打路線終點附近的地名，再按出發。Play 可以在出發前或出發後開始。

## A — `route-a-prince-edward-langham.gpx`

太子 22.32470, 114.16830 → 朗豪坊商場停車場 22.31820, 114.16850。約 90 秒。

App 目的地輸入「朗豪坊」。預期：

- 出發後有藍色路線，SDK 日誌 `HKP_NAV route status=statusOk`，目標是朗豪坊。
- 車位進入目的地約 1 km 時出現 `HKP_RECOMMEND trigger=BACKGROUND_PREFETCH`。有卡就可以選；沒有自動彈出就按 P。
- 選定成功：卡片收起，回到導航主畫面。
- 第二次 `HKP_NAV route request` 的目標改成停車場名，並 `route status=statusOk`。
- 確認那句粵語口播期間，SDK 英文導航聲會暫停，口播結束再恢復。

## B — `route-b-sham-shui-po-harbour-city.gpx`

深水埗 22.33080, 114.16220 → 旺角 → 尖沙咀 → 海港城 22.29560, 114.16880。約 8 分鐘。

App 目的地輸入「海港城」。結束行程後再開一程，預期同樣有藍線、1 km 推薦、選定後收卡，以及第二次 `statusOk`。
