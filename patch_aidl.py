with open('/Users/vincent/Desktop/CastPigeon/androidApp/build.gradle.kts', 'r') as f:
    content = f.read()

target = "buildFeatures {\n        compose = true\n    }"
new_target = "buildFeatures {\n        compose = true\n        aidl = true\n    }"

if "aidl = true" not in content:
    content = content.replace(target, new_target)

with open('/Users/vincent/Desktop/CastPigeon/androidApp/build.gradle.kts', 'w') as f:
    f.write(content)
