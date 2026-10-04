plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ca.derickcampbell.qrcards"
    compileSdk = 34

    defaultConfig {
        // NOTE: applicationId is permanent once published to the Play Store.
        // Easy to change now, impossible later â speak up before first release.
        applicationId = "ca.derickcampbell.qrcards"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.0.2"
    }

    signingConfigs {
        // Stable debug key for CI: every CI build signs with this keystore, so
        // updates install cleanly over each other. Debug credentials are the
        // public android/android pair (same as every dev machine's) â safe to
        // commit. The Play Store upload key is a different, secret key.
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
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
    // encoder is mature and stable â still the standard choice.
    implementation("com.google.zxing:core:3.5.3")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // Encrypted local backup (no cloud). Key lives in the Android Keystore.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
