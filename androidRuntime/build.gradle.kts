val releaseRepository = providers.gradleProperty("githubRepository")
    .orElse(providers.environmentVariable("GITHUB_REPOSITORY"))
    .orElse("suse-edu-cn/CastPigeon")

plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
    }
}

android {
    namespace = "com.suseoaa.castpigeon.runtime"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        buildConfigField("String", "GITHUB_REPOSITORY", "\"${releaseRepository.get()}\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":sharedLogic"))
    api(libs.api)
    api(libs.provider)

    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
}
