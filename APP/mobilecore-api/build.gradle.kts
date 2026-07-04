plugins {
    id("com.android.library")
}

android {
    namespace = "dev.yatori.mobile.api"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(files("libs/yatori_mobilecore.aar"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // api: JsonObject appears in public DTOs (SessionData.extra, MobileConfig.users)
    api("com.google.code.gson:gson:2.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.kotlin.test)

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("junit:junit:4.13.2")
}
