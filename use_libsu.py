with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/service/PrivilegeManager.kt', 'r') as f:
    content = f.read()

import_target = "import java.io.DataOutputStream"
new_import = "import com.topjohnwu.superuser.Shell\nimport java.io.DataOutputStream"

if "com.topjohnwu.superuser.Shell" not in content:
    content = content.replace(import_target, new_import)

execute_su_target = """    private fun executeViaSu(pkgName: String): Boolean {
        try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("appops set $pkgName READ_CLIPBOARD allow\\n")
            os.writeBytes("appops set $pkgName WRITE_CLIPBOARD allow\\n")
            os.writeBytes("exit\\n")
            os.flush()
            val exitValue = process.waitFor()
            if (exitValue == 0) {
                isPrivileged = true
                return true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }"""

new_execute_su = """    private fun executeViaSu(pkgName: String): Boolean {
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

if "Shell.cmd(" not in content:
    content = content.replace(execute_su_target, new_execute_su)

with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/service/PrivilegeManager.kt', 'w') as f:
    f.write(content)
