plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ca.derickcampbell.qrcards"
    compileSdk = 34

    defaultConfig {
        // NOTE: applicationId is permanent once published to the Play Store.
        // Easy to change now, impossible later — speak up before first release.
        applicationId = "ca.derickcampbell.qrcards"
        minSdk = 26
        targetSdk = 34
        // CI can override these per release: gradle assembleDebug
        // -PversionName=0.0.3 -PversionCode=42. Defaults are the fallback.
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 2
        versionName = project.findProperty("versionName") as String? ?: "0.0.2"
    }

    signingConfigs {
        // Stable debug key for CI: every CI build signs with this keystore, so
        // updates install cleanly over each other. Debug credentials are the
        // public android/android pair (same as every dev machine's) — safe to
        // commit. The Play Store upload key is a different, secret key.
        //
        // The keystore is stored base64-encoded (debug.keystore.b64) because
        // binary files can't ride the text-only file pipeline; Gradle decodes
        // it into build/ at configuration time.
        getByName("debug") {
            // Note: project./project.layout are qualified explicitly — bare
            // layout/rootProject don't resolve inside this nested DSL block.
            val decoded = project.layout.buildDirectory.file("ci-debug.keystore").get().asFile
                .apply {
                    parentFile.mkdirs()
                    writeBytes(
                        java.util.Base64.getMimeDecoder().decode(
                            project.rootProject.file("debug.keystore.b64").readText()
                        )
                    )
                }
            storeFile = decoded
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // QR generation. ZXing is in maintenance mode (bug fixes only) but the
    // encoder is mature and stable — still the standard choice.
    implementation("com.google.zxing:core:3.5.3")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // Encrypted local backup (no cloud). Key lives in the Android Keystore.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
