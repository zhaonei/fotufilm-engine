// flutter/build-android.sh sets these for an official build; a bare `flutter build apk` is a source
// build, an app of its own beside the official one, signed with the debug keys.
fun setting(name: String): String? = System.getenv(name)?.takeIf { it.isNotEmpty() }

plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.muastudio.fotufilm.flutter"
    compileSdk = flutter.compileSdkVersion
    // Pinned rather than flutter.ndkVersion: the app builds no native code through Gradle -- the
    // engine's .so arrives prebuilt in FOTUFILM_ANDROID_ENGINE -- so all this decides is which NDK
    // the SDK manager has to fetch. Flutter's default is another 600 MB download per run; 27.2 is
    // the NDK the engine is built with and the one the workflow caches.
    ndkVersion = "27.2.12479018"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        applicationId = setting("FOTUFILM_ANDROID_APPLICATION_ID") ?: "com.muastudio.fotufilm.flutter"
        // The engine is built for android-28 (the Swift SDK's lowest), for 64-bit ARM alone.
        minSdk = 28
        ndk { abiFilters += "arm64-v8a" }
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    // The engine, its Swift runtime and codecs, and the films and camera profiles it reads, from
    // tools/build-android-engine.sh; build output, so outside the source tree.
    val engine = setting("FOTUFILM_ANDROID_ENGINE") ?: "../../build/android-engine"
    sourceSets["main"].jniLibs.srcDir("$engine/jniLibs")
    sourceSets["main"].assets.srcDir("$engine/assets")

    // A release keystore when one is named, so the build can go to Google Play; otherwise the
    // debug keys, so `flutter run --release` works on a phone.
    val keystore = setting("FOTUFILM_ANDROID_KEYSTORE")
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = setting("FOTUFILM_ANDROID_KEYSTORE_PASSWORD")
                keyAlias = setting("FOTUFILM_ANDROID_KEY_ALIAS")
                keyPassword = setting("FOTUFILM_ANDROID_KEY_PASSWORD")
                    ?: setting("FOTUFILM_ANDROID_KEYSTORE_PASSWORD")
                // v2/v3 alone is not enough for every installer: VIVO reads the developer
                // certificate out of META-INF, which only the v1 (JAR) signature writes, and
                // reports "安装包缺乏开发者证书" without it. minSdk 28 makes AGP leave v1 off.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName(if (keystore != null) "release" else "debug")
        }
    }
}

flutter {
    source = "../.."
}
