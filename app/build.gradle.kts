import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
}

// Telegram API credentials. Every third-party client needs its own pair, from
// https://my.telegram.org -> "API development tools". They are read from the gitignored
// local.properties (tg9.apiId / tg9.apiHash) or the TG9_API_ID / TG9_API_HASH environment
// variables, never committed. Without them the app still builds and says so on its login screen.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun secret(property: String, env: String): String =
    localProperties.getProperty(property) ?: System.getenv(env) ?: ""

val apiId = secret("tg9.apiId", "TG9_API_ID").ifBlank { "0" }
val apiHash = secret("tg9.apiHash", "TG9_API_HASH")

val keystoreProperties = Properties()
val keystorePropertiesFile = project.file("keystore.properties")
val hasKeystore = keystorePropertiesFile.exists()
if (hasKeystore) keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }

android {
    namespace = "dev.tg9"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.ninetailsflip.tg9"
        minSdk = 34
        targetSdk = 34
        versionCode = 8
        versionName = "0.1.8"
        buildConfigField("int", "TG_API_ID", apiId)
        buildConfigField("String", "TG_API_HASH", "\"$apiHash\"")
    }

    buildFeatures {
        buildConfig = true
    }

    if (hasKeystore) {
        signingConfigs {
            create("release") {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without a release key, sign with the debug key: a non-debuggable, R8-optimized build
            // is several times faster on a keypad phone than the debug build, and the same key
            // means it installs over a debug build without losing the signed-in account.
            signingConfig = if (hasKeystore) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
            vcsInfo { include = false }
        }
    }

    // One APK per ABI keeps each download to a single copy of libtdjni.so (~20 MB) instead of
    // two; a keypad phone has neither the storage nor, often, the bandwidth to spare. The ABIs
    // are what scripts/build-tdlib.sh builds by default: every keypad phone TG9 targets is ARM.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            // Plus one APK with both, for sideloading onto a phone whose ABI you don't know.
            isUniversalApk = true
        }
    }

    packaging {
        jniLibs.useLegacyPackaging = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        // TG9, like every Nine app, is English-only and builds its screens in code, so its text
        // is set from string literals on purpose. Everything else lint reports is meant to be
        // fixed, and a warning fails the build.
        disable += "SetTextI18n"
        // targetSdk 34 is deliberate: the Nine suite targets exactly the Android 14 keypad phones
        // it's tested on. Lint only flags it where a newer platform is installed (CI runners).
        disable += "OldTargetApi"
        warningsAsErrors = true
        abortOnError = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":update"))
    implementation(project(":tdlib"))
    implementation(project(":kit"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
}

tasks.withType<Test> {
    jvmArgs(
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
    )
}
