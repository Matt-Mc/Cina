plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val ciBuildNumber = providers.environmentVariable("CINA_BUILD_NUMBER").orNull?.toIntOrNull()
val releaseKeystore = providers.environmentVariable("CINA_KEYSTORE_FILE").orNull
val releaseKeyAlias = providers.environmentVariable("CINA_KEY_ALIAS").orNull
val releaseKeystorePassword = providers.environmentVariable("CINA_KEYSTORE_PASSWORD").orNull
val releaseKeyPassword = providers.environmentVariable("CINA_KEY_PASSWORD").orNull

android {
    namespace = "dev.pocketmuse"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.pocketmuse"
        minSdk = 29
        targetSdk = 36
        versionCode = ciBuildNumber ?: 1
        versionName = ciBuildNumber?.let { "0.1.$it" } ?: "0.1.0"
        ndk { abiFilters += if (providers.gradleProperty("emulator").isPresent) listOf("arm64-v8a", "x86_64") else listOf("arm64-v8a") }
    }
    signingConfigs {
        if (releaseKeystore != null && releaseKeyAlias != null && releaseKeystorePassword != null && releaseKeyPassword != null) {
            create("ciRelease") {
                storeFile = file(releaseKeystore)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("ciRelease")?.let { signingConfig = it }
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    packaging { jniLibs { useLegacyPackaging = true } }
}
dependencies {
    implementation(project(":lib"))
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
