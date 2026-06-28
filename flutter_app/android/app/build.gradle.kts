plugins {
    id("com.android.application")
    id("dev.flutter.flutter-gradle-plugin")
}

val localReleaseKeystorePath = "/Users/vincent/Desktop/SUSE-APP-Key/APP-Key.jks"
val releaseKeystorePath = System.getenv("KEYSTORE_FILE_PATH")
    ?.takeIf { it.isNotBlank() }
    ?: localReleaseKeystorePath.takeIf { file(it).exists() }

android {
    namespace = "com.suseoaa.castpigeon.flutter"
    compileSdk = 37
    ndkVersion = "27.0.12077973"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "com.suseoaa.castpigeon"
        minSdk = 33
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    signingConfigs {
        create("release") {
            require(!releaseKeystorePath.isNullOrBlank()) {
                "Release keystore not found. Set KEYSTORE_FILE_PATH or place it at $localReleaseKeystorePath."
            }
            storeFile = file(releaseKeystorePath)
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "LinuxisUbuntu18"
            keyAlias = System.getenv("KEY_ALIAS") ?: "suse-app-key"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "LinuxisUbuntu18"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}

dependencies {
    implementation(project(":androidRuntime"))
}
