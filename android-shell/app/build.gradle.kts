plugins {
    id("com.android.application")
}

android {
    namespace = "com.fotufilm.shell"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.fotufilm.shell"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // The engine is arm64 only; listing it keeps Gradle from looking for other ABIs.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            // Signed with the debug key so assembleRelease also yields an installable file;
            // swap in a real signingConfig before publishing anything.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    packaging {
        jniLibs {
            // Extract the .so files at install time (extractNativeLibs="true") instead of having
            // the installer map them straight out of the APK. It costs some space and needs no
            // page alignment, which is the combination least likely to trip an OEM installer --
            // and this app carries the Swift runtime and FFmpeg beside the engine.
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf("META-INF/*.kotlin_module", "**/*.txt", "**/*.md")
        }
    }
}
