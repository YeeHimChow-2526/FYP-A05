import java.util.Properties

plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

// Step 3.5（SC-018）：雲端 API 設定。優先次序：-P Gradle property → android/local.properties → 環境變數；預設空白。
// android/local.properties 已喺 .gitignore，key 唔會入庫。空白 URL／key = 離線（networkError），唔會 crash。
val hkpLocalProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

fun hkpSetting(name: String): String =
    ((project.findProperty(name) as String?)
        ?: hkpLocalProperties.getProperty(name)
        ?: System.getenv(name)
        ?: "").trim()

fun hkpQuoted(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.hkparking.copilot"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    defaultConfig {
        applicationId = "com.hkparking.copilot"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = 33
        targetSdk = flutter.targetSdkVersion
        // Uses the version code from pubspec.yaml. When using split APKs, 1000 * ABI_VERSION
        // is added automatically by Flutter. (https://developer.android.com/studio/build/configure-apk-splits#configure-APK-versions)
        // You can force using the value of versionCode by specifying the `-P force-version-code-ignoring-abi=true`
        // flag during build.
        versionCode = flutter.versionCode
        versionName = flutter.versionName

        buildConfigField("String", "HKP_API_BASE_URL", hkpQuoted(hkpSetting("HKP_API_BASE_URL")))
        buildConfigField("String", "HKP_API_KEY", hkpQuoted(hkpSetting("HKP_API_KEY")))
        // 只影響 debug：true = 改用 HttpApiTransport；release 一律打真雲端。
        buildConfigField("boolean", "HKP_USE_REAL_API", hkpSetting("HKP_USE_REAL_API").equals("true", ignoreCase = true).toString())
        buildConfigField("boolean", "HKP_USE_REAL_NAVIGATION", hkpSetting("HKP_USE_REAL_NAVIGATION").equals("true", ignoreCase = true).toString())
        manifestPlaceholders["MAPS_API_KEY"] = hkpSetting("MAPS_API_KEY")
        manifestPlaceholders["HKP_APP_LABEL"] = "泊車副駕"
        // debug 橋模式：true = SimLocationSource；預設 false = 模擬器真 GPS。JVM 單測強制 true。
        buildConfigField("boolean", "HKP_USE_SIM_LOCATION", hkpSetting("HKP_USE_SIM_LOCATION").equals("true", ignoreCase = true).toString())
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        // 驗收身份（只限 debug）：HKP_ACCEPTANCE_TAG=r3（＋可選 HKP_ACCEPTANCE_LABEL=r4） → applicationId com.hkparking.copilot.acceptance.r3、
        // 名「泊車副駕測試 r3」，可以同正式／舊版 com.hkparking.copilot 並存。空白＝原本身份。release 永遠唔受影響。
        debug {
            val tag = hkpSetting("HKP_ACCEPTANCE_TAG").trim()
            if (tag.isNotEmpty()) {
                require(Regex("[a-z][a-z0-9]{0,15}").matches(tag)) { "HKP_ACCEPTANCE_TAG must match [a-z][a-z0-9]{0,15}" }
                // 穩定 ID（tag）同顯示版本（HKP_ACCEPTANCE_LABEL，例如 r4）分開：
                // 新一輪用同一個 tag＋更大 --build-number，adb install -r 就可以升級而唔清資料。
                val label = hkpSetting("HKP_ACCEPTANCE_LABEL").trim().ifEmpty { tag }
                require(Regex("[a-z][a-z0-9]{0,15}").matches(label)) { "HKP_ACCEPTANCE_LABEL must match [a-z][a-z0-9]{0,15}" }
                applicationIdSuffix = ".acceptance.$tag"
                versionNameSuffix = "-acceptance-$label"
                manifestPlaceholders["HKP_APP_LABEL"] = "泊車副駕測試 $label"
            }
        }
        release {
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}

dependencies {
    // google_navigation_flutter 0.11.2 pulls navigation:7.9.0, which rejects desugar_jdk_libs < 2.1.5.
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}
