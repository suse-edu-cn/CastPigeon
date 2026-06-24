with open('/Users/vincent/Desktop/CastPigeon/androidApp/build.gradle.kts', 'r') as f:
    content = f.read()

target_deps = "    implementation(libs.compose.material3)"
new_target_deps = "    implementation(libs.compose.material3)\n    implementation(\"com.github.topjohnwu.libsu:core:6.0.0\")\n    implementation(\"com.github.topjohnwu.libsu:service:6.0.0\")"

if "com.github.topjohnwu.libsu:core" not in content:
    content = content.replace(target_deps, new_target_deps)

target_android = """android {
    namespace = "com.suseoaa.castpigeon"
    compileSdk = libs.versions.android.compileSdk.get().toInt()"""
new_target_android = """android {
    namespace = "com.suseoaa.castpigeon"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    buildFeatures {
        aidl = true
    }"""

if "aidl = true" not in content:
    content = content.replace(target_android, new_target_android)

with open('/Users/vincent/Desktop/CastPigeon/androidApp/build.gradle.kts', 'w') as f:
    f.write(content)
