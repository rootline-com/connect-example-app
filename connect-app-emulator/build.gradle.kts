import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// A stand-in for the Rootline connect-app, for emulators only. It installs under the real package
// name because the SDK addresses the connect-app by package name; it therefore cannot be installed
// next to the real connect-app, and a terminal that has the real one rejects this APK.
android {
    namespace = "com.rootline.connect.emulator"
    compileSdk = 36
    // Same pin as :app — only build-tools 35.0.0 is provisioned locally.
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.rootline.connect"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "emulator-${libs.versions.connectSdk.get()}"
    }

    buildFeatures {
        compose = true
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
    // The SDK carries the connect-app contract (com.rootline.connect.contract.*: the intent
    // actions, the outbox authority and the proto-JSON messages). Those classes are SDK internals,
    // not public API. Reusing them keeps this stand-in on the exact wire format of the SDK version
    // this repo pins, so a contract change breaks this build before it breaks a test.
    implementation(libs.connect.sdk)
    // The SDK declares wire on its runtime scope only; the contract messages extend wire's Message.
    implementation(libs.wire.runtime)

    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.foundation.layout)
    implementation(libs.compose.material3)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.text)
}
