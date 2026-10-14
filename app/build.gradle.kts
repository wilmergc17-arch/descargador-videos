plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.wilmer.descargador"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wilmer.descargador"
        minSdk = 24
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
        // yt-dlp trae Python y ffmpeg nativos; arm64 cubre casi todos los teléfonos actuales.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        // Llave fija incluida en el repo para que cada APK nuevo se instale encima del anterior.
        create("fija") {
            storeFile = file("firma.keystore")
            storePassword = "android"
            keyAlias = "descargador"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fija")
        }
        debug {
            signingConfig = signingConfigs.getByName("fija")
        }
    }

    packaging {
        // Requerido por youtubedl-android: los binarios nativos deben extraerse al disco.
        jniLibs { useLegacyPackaging = true }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
}
