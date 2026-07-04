plugins {
    id("com.android.library")
}

android {
    namespace = "dev.yatori.mobile.runtime"
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
    // Expose mobilecore-api (and its transitive Gson api dep) to consumers
    api(project(":mobilecore-api"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // EncryptedFile + MasterKey for config/session/course-cache encryption
    implementation(libs.security.crypto)

    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.kotlin.test)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
