with open('/Users/vincent/Desktop/CastPigeon/gradle/libs.versions.toml', 'r') as f:
    content = f.read()

if "libsu =" not in content:
    content = content.replace('[versions]', '[versions]\nlibsu = "5.2.2"')

if "libsu-core" not in content:
    content = content.replace('[libraries]', '[libraries]\nlibsu-core = { module = "com.github.topjohnwu.libsu:core", version.ref = "libsu" }')

with open('/Users/vincent/Desktop/CastPigeon/gradle/libs.versions.toml', 'w') as f:
    f.write(content)

with open('/Users/vincent/Desktop/CastPigeon/androidApp/build.gradle.kts', 'r') as f:
    content = f.read()

if "implementation(libs.libsu.core)" not in content:
    content = content.replace('implementation(libs.androidx.material3)', 'implementation(libs.androidx.material3)\n    implementation(libs.libsu.core)')

with open('/Users/vincent/Desktop/CastPigeon/androidApp/build.gradle.kts', 'w') as f:
    f.write(content)
