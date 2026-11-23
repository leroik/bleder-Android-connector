plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val scannerSideBySide = providers.gradleProperty("scannerSideBySide").isPresent

android {
    namespace = "dev.hybrid.scanner"
    compileSdk = 35
    defaultConfig {
        applicationId = if (scannerSideBySide) "dev.hybrid.scanner.qr" else "dev.hybrid.scanner"
        resValue("string", "scanner_app_name",
            if (scannerSideBySide) "Hybrid 3D Scanner QR" else "Hybrid 3D Scanner")
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.1.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("com.google.ar:core:1.48.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
