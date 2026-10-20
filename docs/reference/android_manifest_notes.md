# AndroidManifest 參考摘錄（v2.9.1）

> **參考用**：摘自實作版 Doc PART 2 §二.2。本檔**唔代表** Flutter 專案內已有對應 Manifest；實作時對照 `NativeConfig.kt` 與本節邊界寫入 `android/app/src/main/AndroidManifest.xml`。

## 權限與 FGS 類型

```xml
<!-- 位置：只限使用期間 (While-in-use)；不宣告 ACCESS_BACKGROUND_LOCATION -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />

<!-- 模式 A／B 開程必要：POST_NOTIFICATIONS、SYSTEM_ALERT_WINDOW（連同上方精確位置）；RECORD_AUDIO 只屬全語音模式（語音輸入）必要權限；BLUETOOTH_CONNECT 為唯一可選 -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />

<!-- Android 14+ 前台服務類型（Manifest 宣告；Play Console 須申報）-->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />

<service
    android:name=".FloatingBubbleService"
    android:exported="false"
    android:foregroundServiceType="location|microphone" />

<!-- 模式 A：通知存取權 -->
<service
    android:name=".MapsNotificationListener"
    android:exported="true"
    android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
    <intent-filter>
        <action android:name="android.service.notification.NotificationListenerService" />
    </intent-filter>
</service>

<!-- 短命透明跳板：通知／App Actions／分享 → 權限檢查 → startForeground → 等 FGS_STARTED -->
<activity
    android:name=".FgsBootstrapActivity"
    android:exported="false"
    android:theme="@android:style/Theme.Translucent.NoTitleBar" />
<activity
    android:name=".ShareIntentReceiverActivity"
    android:exported="true"
    android:theme="@android:style/Theme.Translucent.NoTitleBar">
    <intent-filter>
        <action android:name="android.intent.action.SEND" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:mimeType="text/plain" />
    </intent-filter>
</activity>
```

## 實作邊界（摘要）

- `minSdkVersion = 33`（Android 13+）只屬安裝下限，並不保證裝置具備離線粵語 STT／TTS；語音能力一律以能力檢查判定行程語音模式（見 `docs/specs/01_ui_and_voice_fsm.md` §5.1）。
- Manifest 宣告 `location|microphone`；FGS **平時只以 location** `startForeground`；全語音模式下用戶手勢內先升級 `location|microphone`；播報＋手動／純手動模式全程只以 `location` 運行。
- **不申請** `ACCESS_BACKGROUND_LOCATION`；**不採用**系統 Geofencing API；150m 外圈用 FGS 前台定位軟件距離追蹤。
- 藍牙 ACL 只發 Heads-up；PendingIntent → `FgsBootstrapActivity` → 權限檢查 → `startForeground`。嚴禁 BR→Activity trampoline；嚴禁 Service 內請求執行期權限。
- **FGS 啟動握手**：`FgsBootstrapActivity`／`ShareIntentReceiverActivity` 呼叫 `startForeground` 後保持存在，等候 Service 回報 `FGS_STARTED`；收到即 `finish()`；逾時 `NativeConfig.FGS_BOOTSTRAP_TIMEOUT_MS = 2000` 照樣 `finish()` 並記錄失敗原因。不設固定 sleep；啟動延遲真機實測。
- `MIC_READY` 為獨立訊號：麥克風初始化失敗即只停用語音輸入（膠囊照常；TTS 可用則照常口播）。
- 行程中撤銷按模式：本模式開程必要權限被撤銷 → 停止 FGS、結束本程；全語音模式下 `RECORD_AUDIO` 被撤銷 → 停止聆聽與熱詞、降級 `location`、TTS 可用則按播報＋手動繼續。
- Google Maps 導航語音不受影響：Maps 搶 AudioFocus 時暫停本 App AudioRecord，結束恢復。
- Pre-FGS：只通知、無膠囊、無 GREY／AMBER overlay。
- 常駐通知文案：「正在聽」。OEM 殺 FGS＝abandon trip，司機自行重開（新 `session_id`）。
- 另見 `res/xml/shortcuts.xml`（Hey Google App Actions；`destination` 可選；現場講法入現場即泊）。
- 入口優先聲明（決定版）：「B-1 路徑 1A：Hey Google／App Actions 冷態入口屬實驗性能力，須按指定 intent、語言及裝置驗證通過後方可啟用，不列入本版必要交付或免手操作保證。正文相關冷態流程均以驗證通過為前提。本限制不適用於 B-1 路徑 1B 已開程後的本機語音互動。」驗證步驟見 `docs/testing/01_test_plan.md`（Google Doc「泊車副駕 測試清單 v2.9.1」，ID `1OaMcyeEU8m0HKuhhCLf2DCJX9iY0h1vu-ar7WGdd148`） Phase B 第 12 項。

行車常數門檻一律引用 `android/app/src/main/kotlin/com/hkparking/copilot/NativeConfig.kt`（Flutter 畫面常數見 `lib/core/constants/app_config.dart`），唔好喺 Manifest 註解或 Kotlin 散落 hardcode。
