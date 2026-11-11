import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "com.nefelion.fourpartwriting"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.nefelion.fourpartwriting"
        minSdk = 26 // AAudio
        targetSdk = 37
        versionCode = 7
        versionName = "0.3.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }
    ndkVersion = "27.0.12077973"
    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Local release key; without keystore.properties the release APK stays unsigned (F-Droid signs its own).
    val keystoreFile = rootProject.file("keystore.properties")
    if (keystoreFile.exists()) {
        val keystore = Properties().apply { keystoreFile.inputStream().use { load(it) } }
        signingConfigs {
            create("release") {
                storeFile = file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("password")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("password")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // F-Droid: no Google-signed dependency metadata blob in the APK/AAB
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

// Startup profile from the :baselineprofile journey, kept in src/release/generated; regenerate with
// ./gradlew :app:generateBaselineProfile -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
baselineProfile {
    dexLayoutOptimization = true
}

// The profile build types install over the debug app: the debug key keeps its data on the test device
androidComponents {
    finalizeDsl { ext ->
        ext.buildTypes.filter { it.name.startsWith("nonMinified") || it.name.startsWith("benchmark") }
            .forEach { it.signingConfig = ext.signingConfigs.getByName("debug") }
        // The plugin turns off isMinifyEnabled, not this newer switch: the profile must name the classes unobfuscated
        ext.buildTypes.filter { it.name.startsWith("nonMinified") }.forEach { it.optimization.enable = false }
    }
}

dependencies {
    "baselineProfile"(project(":baselineprofile"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}