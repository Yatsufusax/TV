plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mistv.canales"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mistv.canales"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Solo celulares y TV box reales (ahorra mucho tamaño de APK por libVLC)
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    // Motor de VLC: reproduce MPEG-2 y otros formatos que el equipo no soporta
    implementation("org.videolan.android:libvlc-all:3.6.0")
}
