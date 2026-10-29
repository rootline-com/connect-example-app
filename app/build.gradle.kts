import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Per-integrator Payment API settings, read from the gitignored local.properties so no real key,
// account id, or terminal id reaches the repo. The committed defaults are placeholders; a fresh clone
// still builds, and PaymentApi reports a missing key at runtime instead of sending an unauthenticated
// request. See "Payment API configuration" in the README.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

fun localProperty(name: String, default: String = ""): String =
    localProperties.getProperty(name) ?: default

android {
    namespace = "com.rootline.connect.example"
    compileSdk = 36
    // Only build-tools 35.0.0 is provisioned locally; pin it so AGP doesn't reach for a newer one.
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.rootline.connect.example"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "0.0.3"
        multiDexEnabled = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "PAYMENT_API_KEY", "\"${localProperty("paymentApiKey")}\"")
        buildConfigField(
            "String",
            "PAYMENT_ACCOUNT_ID",
            "\"${localProperty("paymentAccountId", "acc_YOUR_ACCOUNT_ID")}\"",
        )
        buildConfigField(
            "String",
            "PAYMENT_DEVICE_ID",
            "\"${localProperty("paymentDeviceId", "device_YOUR_PAYMENT_DEVICE_ID")}\"",
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Rootline Connect integrator SDK — the single dependency that makes this an integrator sample.
    implementation(libs.connect.sdk)

    // Compose UI + activity entry point. androidx.core/lifecycle come transitively from the SDK.
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.foundation.layout)
    implementation(libs.compose.material3)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.text)

    // Pure-JVM reducer tests (ConnectClientTest) — the reducer is Android-free, so no Robolectric.
    testImplementation(libs.junit)

    // Instrumented tests (ConnectEmulatorTest) — run on an emulator with :connect-app-emulator installed.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
