with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/service/BleForegroundService.kt', 'r') as f:
    content = f.read()

# Update the clipboardListener to fall back to transparent activity
old_listener = """        // 无 Root 权限：尝试直接读取（仅在 App 有焦点时有效）
        android.util.Log.w("CastPigeon", "无 Root 权限或守护进程未连接，尝试直接读取剪贴板")
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = try {
            clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        } catch (e: Exception) { null }
        
        if (!text.isNullOrEmpty() && text != lastSyncedText) {
            lastSyncedText = text
            val payload = "CLIP|$text"
            android.util.Log.i("CastPigeon", "直接读取剪贴板成功，准备发送: $payload")
            try {
                AppConnectionManager.blePeripheral.sendNotificationData(payload.encodeToByteArray())
                android.util.Log.i("CastPigeon", "发送到 Mac 成功！")
            } catch (e: Exception) {
                android.util.Log.e("CastPigeon", "发送失败", e)
            }
        } else {
            android.util.Log.w("CastPigeon", "后台直接读取剪贴板为空（被系统拦截），请开启高级实验室提权")
        }"""

new_listener = """        // Root 返回 null 或无 Root：降级方案
        // 尝试直接读取（仅在 App 有焦点时有效）
        android.util.Log.w("CastPigeon", "Root 守护进程未连接或返回 null，尝试直接读取")
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = try {
            clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        } catch (e: Exception) { null }
        
        if (!text.isNullOrEmpty() && text != lastSyncedText) {
            lastSyncedText = text
            val payload = "CLIP|$text"
            android.util.Log.i("CastPigeon", "直接读取剪贴板成功，准备发送: $payload")
            try {
                AppConnectionManager.blePeripheral.sendNotificationData(payload.encodeToByteArray())
                android.util.Log.i("CastPigeon", "发送到 Mac 成功！")
            } catch (e: Exception) {
                android.util.Log.e("CastPigeon", "发送失败", e)
            }
        } else {
            android.util.Log.w("CastPigeon", "后台直接读取剪贴板为空（被系统拦截）。Root 守护进程会通过 am start 自动触发透明 Activity 解决此问题。")
        }"""

if "Root 守护进程未连接或返回 null" not in content:
    content = content.replace(old_listener, new_listener)

with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/service/BleForegroundService.kt', 'w') as f:
    f.write(content)
