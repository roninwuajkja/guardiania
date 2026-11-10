plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Firebase solo se activa cuando el usuario ya descargó google-services.json.
// Sin ese archivo el build sigue funcionando con WebSocket y sin push.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "cl.guardiania.agent"
    compileSdk = 35

    defaultConfig {
        applicationId = "cl.guardiania.agent"
        minSdk = 26
        targetSdk = 35
        versionCode = 24
        versionName = "1.7.15"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Se activa solo en el build de diagnóstico: guarda el último fotograma
        // analizado en caché para revisar qué ve realmente el lector.
        buildConfigField("boolean", "QR_DIAGNOSTIC", "false")

        // Autoridad del FileProvider en un placeholder: permite darle una
        // distinta a un build de pruebas sin tocar el manifiesto.
        manifestPlaceholders["fileProviderAuthority"] = "cl.guardiania.agent.fileprovider"

        // Solo teléfonos reales: sin las librerías x86/x86_64 el APK pesa
        // ~15 MB menos. Un emulador x86 necesitaría esta línea revertida.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    signingConfigs {
        val storePath = System.getenv("GUARDIANIA_KEYSTORE")
        val storePass = System.getenv("GUARDIANIA_KEYSTORE_PASSWORD")
        val keyAliasValue = System.getenv("GUARDIANIA_KEY_ALIAS")
        val keyPass = System.getenv("GUARDIANIA_KEY_PASSWORD")
        if (!storePath.isNullOrBlank() && !storePass.isNullOrBlank() && !keyAliasValue.isNullOrBlank() && !keyPass.isNullOrBlank()) {
            create("release") {
                storeFile = file(storePath)
                storePassword = storePass
                keyAlias = keyAliasValue
                keyPassword = keyPass
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }

        // El emulador del PC es x86_64. El release se queda solo en ARM para
        // no cargar 15 MB de bibliotecas que un teléfono no usa.
        debug {
            ndk {
                abiFilters += listOf("x86_64")
            }
        }

        // Mismo paquete y misma firma que release, sin R8 y depurable: se instala
        // encima sin borrar la vinculación y conserva los Log. Sirve para ver por
        // qué un QR no se detecta. No se distribuye.
        create("diagnostic") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = true
            signingConfig = signingConfigs.findByName("release")
            matchingFallbacks += listOf("release")
            versionNameSuffix = "-diag"
            buildConfigField("boolean", "QR_DIAGNOSTIC", "true")
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("net.zetetic:android-database-sqlcipher:4.5.4")
    implementation("androidx.sqlite:sqlite:2.4.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // QR: CameraX para la cámara y ML Kit (modelo empaquetado, sin red) para
    // decodificar. Sustituye a zxing-android-embedded, que recortaba el
    // fotograma con un rectángulo interno y no detectaba el código.
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("com.google.zxing:core:3.5.3")

    // FCM: el plugin de Google solo se aplica si existe google-services.json.
    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
    implementation("com.google.firebase:firebase-messaging")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
}
