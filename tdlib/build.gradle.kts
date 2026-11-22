plugins {
    id("com.android.library")
}

// TDLib's Java API (Client + generated TdApi) and its native libtdjni.so, both produced by
// scripts/build-tdlib.sh from the pinned third_party/td submodule. Kept as a separate module so
// the 10 MB TdApi.java compiles once and isn't recompiled on every app change.
android {
    namespace = "org.drinkless.tdlib"
    compileSdk = 34

    defaultConfig {
        minSdk = 34
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val tdApi = file("src/main/java/org/drinkless/tdlib/TdApi.java")
tasks.named("preBuild") {
    doFirst {
        if (!tdApi.exists()) {
            throw GradleException("TDLib isn't built yet: run scripts/build-tdlib.sh first.")
        }
    }
}
