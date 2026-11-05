plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.pilot.mobile"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.pilot.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.2.3-preview"
        resValue("string", "app_name", "Pilot")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64") }
    }
    signingConfigs {
        if (System.getenv("PILOT_ANDROID_KEYSTORE") != null) create("production") {
            storeFile = file(System.getenv("PILOT_ANDROID_KEYSTORE"))
            storePassword = System.getenv("PILOT_ANDROID_STORE_PASSWORD")
            keyAlias = System.getenv("PILOT_ANDROID_KEY_ALIAS")
            keyPassword = System.getenv("PILOT_ANDROID_KEY_PASSWORD")
        }
    }
    buildTypes {
        getByName("release") { signingConfig = signingConfigs.findByName("production") }
        create("qa") { initWith(getByName("debug")); applicationIdSuffix = ".qa"; resValue("string", "app_name", "Pilot QA"); matchingFallbacks += "debug" } }
    testBuildType = "qa"
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
    buildFeatures { compose = true }
    packaging { resources.excludes += listOf("META-INF/AL2.0", "META-INF/LGPL2.1", "darwin-*/**", "linux-*/**", "win32-*/**") }
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation(project(":transport")) { exclude(group = "net.java.dev.jna", module = "jna") }
    implementation("computer.iroh:iroh-android:1.2.0")
    // Android 必须使用带 JNI 库的 AAR，而不是桌面 JNA JAR。
    // 5.17+ 修复 Android 16 KiB 页大小设备的 JNI 加载问题。
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
