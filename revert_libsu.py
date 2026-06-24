with open('/Users/vincent/Desktop/CastPigeon/gradle/libs.versions.toml', 'r') as f:
    content = f.read()

content = content.replace('libsu = "5.2.2"\n', '')
content = content.replace('libsu-core = { module = "com.github.topjohnwu.libsu:core", version.ref = "libsu" }\n', '')

with open('/Users/vincent/Desktop/CastPigeon/gradle/libs.versions.toml', 'w') as f:
    f.write(content)

with open('/Users/vincent/Desktop/CastPigeon/androidApp/build.gradle.kts', 'r') as f:
    content = f.read()

content = content.replace('    implementation(libs.libsu.core)\n', '')

with open('/Users/vincent/Desktop/CastPigeon/androidApp/build.gradle.kts', 'w') as f:
    f.write(content)

with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/service/PrivilegeManager.kt', 'r') as f:
    content = f.read()

content = content.replace('import com.topjohnwu.superuser.Shell\n', '')

execute_su_target = """    private fun executeViaSu(pkgName: String): Boolean {
        try {
            // 使用 libsu 确保全面兼容 Magisk/KernelSU/APatch
            val result = Shell.cmd(
                "appops set $pkgName READ_CLIPBOARD allow",
                "appops set $pkgName WRITE_CLIPBOARD allow"
            ).exec()
            
            if (result.isSuccess) {
                isPrivileged = true
                android.util.Log.i("CastPigeon", "su appops 执行成功")
                return true
            } else {
                android.util.Log.w("CastPigeon", "su appops 执行失败: ${result.err}")
            }
        } catch (e: Exception) {
            android.util.Log.e("CastPigeon", "su 执行异常", e)
        }
        return false
    }"""

new_execute_su = """    private fun executeViaSu(pkgName: String): Boolean {
        try {
            val process = Runtime.getRuntime().exec("su")
            val os = java.io.DataOutputStream(process.outputStream)
            os.writeBytes("appops set $pkgName READ_CLIPBOARD allow\\n")
            os.writeBytes("appops set $pkgName WRITE_CLIPBOARD allow\\n")
            os.writeBytes("exit\\n")
            os.flush()
            val exitValue = process.waitFor()
            if (exitValue == 0) {
                isPrivileged = true
                android.util.Log.i("CastPigeon", "su appops 执行成功")
                return true
            } else {
                android.util.Log.w("CastPigeon", "su appops 执行失败，退出码: $exitValue")
            }
        } catch (e: Exception) {
            android.util.Log.w("CastPigeon", "找不到 su 或未授权，请检查 KSU/APatch 管理器")
        }
        return false
    }"""

content = content.replace(execute_su_target, new_execute_su)

with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/service/PrivilegeManager.kt', 'w') as f:
    f.write(content)
