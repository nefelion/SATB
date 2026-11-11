// Generates the app's startup profile on a connected device (Android 13+ or rooted); see app/build.gradle.kts
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "com.nefelion.fourpartwriting.baselineprofile"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        minSdk = 28
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    targetProjectPath = ":app"
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.androidx.benchmark.macro.junit4)
}
