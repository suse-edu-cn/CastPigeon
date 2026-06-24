with open('/Users/vincent/Desktop/CastPigeon/gradle/libs.versions.toml', 'r') as f:
    content = f.read()

if "libsu =" not in content:
    content = content.replace('[versions]', '[versions]\nlibsu = "6.0.0"')

if "libsu-core" not in content:
    content = content.replace('[libraries]', '[libraries]\nlibsu-core = { module = "com.github.topjohnwu.libsu:core", version.ref = "libsu" }\nlibsu-service = { module = "com.github.topjohnwu.libsu:service", version.ref = "libsu" }\nlibsu-nio = { module = "com.github.topjohnwu.libsu:nio", version.ref = "libsu" }')

with open('/Users/vincent/Desktop/CastPigeon/gradle/libs.versions.toml', 'w') as f:
    f.write(content)

with open('/Users/vincent/Desktop/CastPigeon/androidApp/build.gradle.kts', 'r') as f:
    content = f.read()

if "implementation(libs.libsu.core)" not in content:
    content = content.replace('implementation(libs.androidx.material3)', 'implementation(libs.androidx.material3)\n    implementation(libs.libsu.core)\n    implementation(libs.libsu.service)\n    implementation(libs.libsu.nio)')

with open('/Users/vincent/Desktop/CastPigeon/androidApp/build.gradle.kts', 'w') as f:
    f.write(content)
