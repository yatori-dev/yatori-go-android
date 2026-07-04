import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Signing credentials live in local.properties (never committed) — reused from the original
// Yatori-Android project so release builds keep the same signing identity / key.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val selectedAbis = providers.gradleProperty("yatori.abis")
    .orNull
    ?.split(",", ";")
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() }
    .orEmpty()

android {
    namespace = "dev.yatori.mobile.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.yatori.go.android"
        minSdk = 33   // upgraded: miuix-blur requires API 33 for RenderEffect blur
        targetSdk = 37
        versionCode = 26070504
        versionName = "1.0.5"
        if (selectedAbis.isNotEmpty()) {
            ndk {
                abiFilters += selectedAbis
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    signingConfigs {
        create("release") {
            val ksPath = localProps.getProperty("KEYSTORE_PATH")
            if (!ksPath.isNullOrBlank()) {
                storeFile = file(ksPath)
                storePassword = localProps.getProperty("KEYSTORE_PASSWORD")
                keyAlias = localProps.getProperty("KEY_ALIAS")
                keyPassword = localProps.getProperty("KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        resources.excludes += "META-INF/NOTICE.md"
        resources.excludes += "META-INF/LICENSE.md"
    }
}

dependencies {
    implementation(project(":mobilecore-runtime"))
    implementation(project(":captcha-ocr"))

    implementation(libs.compose.activity)
    implementation(platform(libs.compose.bom))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.runtime)
    implementation(libs.coroutines.android)
    implementation(libs.work.runtime)
    implementation(libs.security.crypto)
    implementation(libs.core.ktx)

    implementation(libs.miuix.ui)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.shader)
    implementation("androidx.navigationevent:navigationevent-compose:1.0.0")

    // SMTP email sending (android-mail + android-activation are the Android-compatible javax.mail ports)
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")

    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.test)
}
