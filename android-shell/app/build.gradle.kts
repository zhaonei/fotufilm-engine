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

    signingConfigs {
        // A keystore in the tree, with its password, on purpose: AGP's own debug keystore lives
        // in ~/.android and is generated afresh on each machine, so every CI run would hand out a
        // different certificate and no build could be installed over the one before it. This key
        // is a shell identity, not a secret -- do not put anything you care about behind it.
        //
        // v1 is turned on explicitly. AGP defaults it to "minSdk < 24", and a VIVO installer
        // reads the certificate out of META-INF (the v1 / JAR signature), reporting
        // "安装包缺乏开发者证书" when it is absent even though v2/v3 signed the APK just fine.
        create("fotufilm") {
            storeFile = file("../keystore/fotufilm-shell.jks")
            storePassword = "fotufilm"
            keyAlias = "fotufilm"
            keyPassword = "fotufilm"
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fotufilm")
        }
        release {
            isMinifyEnabled = false
            // Not debuggable, and signed with the same certificate as debug, so an install
            // upgrades in place rather than fighting over the signing identity.
            signingConfig = signingConfigs.getByName("fotufilm")
        }
    }

    lint {
        // assembleRelease runs lintVital; this shell is a few dozen lines of Java that CI then
        // checks against the built APK anyway.
        checkReleaseBuilds = false
        abortOnError = false
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
