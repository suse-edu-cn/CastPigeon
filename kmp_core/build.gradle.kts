plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

val flutterSdkPath = providers.gradleProperty("flutterSdk")
    .orElse(providers.environmentVariable("FLUTTER_ROOT"))
    .orElse(providers.environmentVariable("FLUTTER_HOME"))
    .orElse(
        providers.exec {
            commandLine("sh", "-lc", "cd ${rootProject.projectDir.absolutePath} && command -v flutter")
        }.standardOutput.asText.map { flutterExecutable ->
            file(flutterExecutable.trim()).parentFile.parentFile.absolutePath
        }
    )
val dartSdkIncludeDir = flutterSdkPath.map { "$it/bin/cache/dart-sdk/include" }

fun registerDartApiShimObject(taskName: String, arch: String) =
    tasks.register<Exec>(taskName) {
        val objectFile = layout.buildDirectory.file("nativeInterop/dartApiShim/$arch/dart_api_shim.o")
        val outputDir = layout.buildDirectory.dir("nativeInterop/dartApiShim/$arch")
        val cSourceFile = project.layout.projectDirectory.file("src/nativeInterop/c/dart_api_shim.c")
        val cIncludeDir = project.layout.projectDirectory.dir("src/nativeInterop/c")
        inputs.file("src/nativeInterop/c/dart_api_shim.c")
        inputs.file("src/nativeInterop/c/dart_api_shim.h")
        inputs.dir(dartSdkIncludeDir)
        outputs.file(objectFile)

        commandLine(
            "sh",
            "-c",
            listOf(
                "test -d '${dartSdkIncludeDir.get()}'",
                "mkdir -p '${outputDir.get().asFile.absolutePath}'",
                "clang -c -fPIC -arch '$arch' -I '${dartSdkIncludeDir.get()}' -I '${cIncludeDir.asFile.absolutePath}' '${cSourceFile.asFile.absolutePath}' -o '${objectFile.get().asFile.absolutePath}'"
            ).joinToString(" && ")
        )
    }

val compileDartApiShimMacosArm64 = registerDartApiShimObject("compileDartApiShimMacosArm64", "arm64")
val compileDartApiShimMacosX64 = registerDartApiShimObject("compileDartApiShimMacosX64", "x86_64")
val dartApiShimMacosArm64Object = layout.buildDirectory.file("nativeInterop/dartApiShim/arm64/dart_api_shim.o")
val dartApiShimMacosX64Object = layout.buildDirectory.file("nativeInterop/dartApiShim/x86_64/dart_api_shim.o")

kotlin {
    macosArm64 {
        compilations.getByName("main") {
            cinterops {
                val dartApiShim by creating {
                    defFile(project.file("src/nativeInterop/cinterop/dart_api_shim.def"))
                    includeDirs(
                        project.file("src/nativeInterop/c"),
                        file(dartSdkIncludeDir.get())
                    )
                }
            }
        }
        binaries.sharedLib {
            baseName = "castpigeon_core"
            linkerOpts(dartApiShimMacosArm64Object.get().asFile.absolutePath)
        }
    }
    macosX64 {
        compilations.getByName("main") {
            cinterops {
                val dartApiShim by creating {
                    defFile(project.file("src/nativeInterop/cinterop/dart_api_shim.def"))
                    includeDirs(
                        project.file("src/nativeInterop/c"),
                        file(dartSdkIncludeDir.get())
                    )
                }
            }
        }
        binaries.sharedLib {
            baseName = "castpigeon_core"
            linkerOpts(dartApiShimMacosX64Object.get().asFile.absolutePath)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.network)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

tasks.matching { it.name == "linkDebugSharedMacosArm64" || it.name == "linkReleaseSharedMacosArm64" }
    .configureEach {
        dependsOn(compileDartApiShimMacosArm64)
    }

tasks.matching { it.name == "linkDebugSharedMacosX64" || it.name == "linkReleaseSharedMacosX64" }
    .configureEach {
        dependsOn(compileDartApiShimMacosX64)
    }
