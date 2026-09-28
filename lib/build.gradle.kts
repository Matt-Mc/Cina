plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.arm.aichat"
    compileSdk = 36
    if (!providers.gradleProperty("skipNative").isPresent) ndkVersion = "29.0.13113456"
    defaultConfig {
        minSdk = 29
        ndk { abiFilters += if (providers.gradleProperty("emulator").isPresent) listOf("arm64-v8a", "x86_64") else listOf("arm64-v8a") }
        if (!providers.gradleProperty("skipNative").isPresent) externalNativeBuild { cmake {
            arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DBUILD_SHARED_LIBS=ON", "-DLLAMA_BUILD_APP=OFF", "-DLLAMA_BUILD_COMMON=ON", "-DLLAMA_OPENSSL=OFF", "-DGGML_NATIVE=OFF", "-DGGML_BACKEND_DL=ON", "-DGGML_CPU_ALL_VARIANTS=ON", "-DGGML_LLAMAFILE=OFF")
        } }
    }
    if (!providers.gradleProperty("skipNative").isPresent) externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.31.6" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
}
dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
}
