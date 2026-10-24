import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.readaloud"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.readaloud"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += "arm64-v8a" } // sherpa-onnx ships four ABIs (~47 MB); the Fold is arm64
    }

    // Release signing: put your upload key's details in keystore.properties next to the root build.gradle.kts (never commit it):
    //   storeFile=C:/path/to/readaloud-upload.jks   storePassword=...   keyAlias=readaloud   keyPassword=...
    // Without that file the release build is signed with the debug key, which is fine for testing on a phone but cannot go to Play.
    val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) create("release") {
            storeFile = file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    // The voice models are downloaded, but the sherpa-onnx native libraries must stay uncompressed-extractable
    packaging {
        jniLibs { useLegacyPackaging = false }
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.core:core-ktx:1.19.1")

    // ISBN barcode scanning and page scanning via Google Play services (no camera permission, no bundled model)
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // PDF text extraction (free, on-device)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // On-device Kokoro TTS runtime (AAR from the k2-fsa/sherpa-onnx v1.13.8 release) and a .tar.bz2 unpacker for its model
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    implementation("org.apache.commons:commons-compress:1.27.1")

    // EPUB is a zip of XHTML; Jsoup strips the markup
    implementation("org.jsoup:jsoup:1.23.2")
}
