with open('/Users/vincent/Desktop/CastPigeon/settings.gradle.kts', 'r') as f:
    content = f.read()

target = """dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}"""

new_target = """dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        maven("https://jitpack.io")
    }
}"""

if 'maven("https://jitpack.io")' not in content:
    content = content.replace(target, new_target)

with open('/Users/vincent/Desktop/CastPigeon/settings.gradle.kts', 'w') as f:
    f.write(content)
