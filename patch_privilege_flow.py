with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/service/PrivilegeManager.kt', 'r') as f:
    content = f.read()

target = """object PrivilegeManager {
    var isPrivileged = false
        private set

    var rootClipboard: IRootClipboard? = null"""
new_target = """import kotlinx.coroutines.flow.MutableStateFlow

object PrivilegeManager {
    val isPrivileged = MutableStateFlow(false)

    var rootClipboard: IRootClipboard? = null"""

content = content.replace(target, new_target)
content = content.replace("isPrivileged = true", "isPrivileged.value = true")
content = content.replace("isPrivileged = false", "isPrivileged.value = false")

with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/service/PrivilegeManager.kt', 'w') as f:
    f.write(content)

with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/service/BleForegroundService.kt', 'r') as f:
    content2 = f.read()

content2 = content2.replace("PrivilegeManager.isPrivileged && PrivilegeManager.rootClipboard != null", "PrivilegeManager.isPrivileged.value && PrivilegeManager.rootClipboard != null")

with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/service/BleForegroundService.kt', 'w') as f:
    f.write(content2)

with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/ui/MainScreen.kt', 'r') as f:
    content3 = f.read()

content3 = content3.replace("val success = com.suseoaa.castpigeon.service.PrivilegeManager.executeAppOpsCommand()", "val success = com.suseoaa.castpigeon.service.PrivilegeManager.executeAppOpsCommand(context)")

with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/ui/MainScreen.kt', 'w') as f:
    f.write(content3)
