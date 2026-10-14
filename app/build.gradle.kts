plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val ciBuildNumber = providers.environmentVariable("CINA_BUILD_NUMBER").orNull?.toIntOrNull()
val inboxEnabled = providers.gradleProperty("inboxEnabled").orNull?.toBooleanStrict() ?: true
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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = ciBuildNumber ?: 1
        buildConfigField("boolean", "INBOX_ENABLED", inboxEnabled.toString())
        manifestPlaceholders["inboxEnabled"] = inboxEnabled.toString()
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
    implementation("com.google.android.gms:play-services-auth:22.0.0")
    implementation(project(":lib"))
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("org.commonmark:commonmark:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.24.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    debugImplementation("androidx.compose.ui:ui-tooling")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.6.2")
    // Android 16 removed the reflective InputManager API used by Espresso 3.6.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
