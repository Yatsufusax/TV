plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// GitHub pasa el número de build; sube solo en cada compilación (así Android reconoce las versiones nuevas).
val buildNumber = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "com.mistv.canales"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mistv.canales"
        minSdk = 21
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.$buildNumber"

        // Solo celulares y TV box reales (ahorra mucho tamaño de APK por libVLC)
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    // Misma firma en todas las compilaciones: permite actualizar la app instalada sin desinstalarla.
    // La clave ya no está en el repositorio: GitHub la entrega desde "Secrets" como variables de entorno.
    signingConfigs {
        create("shared") {
            val ks = System.getenv("KEYSTORE_FILE")
            val sp = System.getenv("KEYSTORE_PASSWORD")
            val al = System.getenv("KEY_ALIAS")
            val kp = System.getenv("KEY_PASSWORD")
            if (ks.isNullOrBlank() || sp.isNullOrBlank() || al.isNullOrBlank() || kp.isNullOrBlank()) {
                throw GradleException(
                    "Falta la clave de firma. Cargá los 4 secrets en GitHub: " +
                        "KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD."
                )
            }
            storeFile = file(ks)
            storePassword = sp
            keyAlias = al
            keyPassword = kp
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
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
    implementation("androidx.media3:media3-exoplayer-dash:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    // Motor de VLC: reproduce MPEG-2 y otros formatos que el equipo no soporta
    implementation("org.videolan.android:libvlc-all:3.6.0")
}
