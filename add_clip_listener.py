with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/service/BleForegroundService.kt', 'r') as f:
    content = f.read()

import_target = "import android.content.ClipData"
new_import = """import android.content.ClipData
import android.content.ClipboardManager.OnPrimaryClipChangedListener"""

if "OnPrimaryClipChangedListener" not in content:
    content = content.replace(import_target, new_import)

class_target = """    private var isObserving = false"""
new_class = """    private var isObserving = false
    private var lastSyncedText: String? = null
    
    private val clipboardListener = OnPrimaryClipChangedListener {
        android.util.Log.i("CastPigeon", "触发 OnPrimaryClipChangedListener!")
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        
        if (!text.isNullOrEmpty() && text != lastSyncedText) {
            lastSyncedText = text
            // 只有当有特权（Root/Shizuku）时，或者App恰好在前台时，才能真正在后台读到内容
            val payload = "CLIP|$text"
            android.util.Log.i("CastPigeon", "自动监听到剪贴板变化，准备发送: $payload")
            try {
                AppConnectionManager.blePeripheral.sendNotificationData(payload.encodeToByteArray())
                android.util.Log.i("CastPigeon", "自动发送剪贴板到 Mac 成功！")
            } catch (e: Exception) {
                android.util.Log.e("CastPigeon", "自动发送剪贴板失败", e)
            }
        } else {
            android.util.Log.i("CastPigeon", "剪贴板为空，或与上次同步的内容相同，忽略。")
        }
    }"""

if "clipboardListener = OnPrimaryClipChangedListener" not in content:
    content = content.replace(class_target, new_class)

on_create_target = """        startObservingNotifications()
    }"""
new_on_create = """        startObservingNotifications()
        
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.addPrimaryClipChangedListener(clipboardListener)
            android.util.Log.i("CastPigeon", "已注册后台剪贴板监听器")
        } catch (e: Exception) {
            android.util.Log.e("CastPigeon", "注册剪贴板监听器失败", e)
        }
    }"""

if "addPrimaryClipChangedListener(clipboardListener)" not in content:
    content = content.replace(on_create_target, new_on_create)

on_destroy_target = """        try {
            unregisterReceiver(clipboardSyncReceiver)
        } catch (e: Exception) { }
    }"""
new_on_destroy = """        try {
            unregisterReceiver(clipboardSyncReceiver)
        } catch (e: Exception) { }
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.removePrimaryClipChangedListener(clipboardListener)
        } catch (e: Exception) { }
    }"""

if "removePrimaryClipChangedListener(clipboardListener)" not in content:
    content = content.replace(on_destroy_target, new_on_destroy)
    
mac_sync_target = """            if (msg.startsWith("CLIP|")) {
                val text = msg.substring(5)
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                try {
                    val clip = ClipData.newPlainText("CastPigeon", text)
                    clipboard.setPrimaryClip(clip)"""
new_mac_sync = """            if (msg.startsWith("CLIP|")) {
                val text = msg.substring(5)
                lastSyncedText = text // 防止回环触发
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                try {
                    val clip = ClipData.newPlainText("CastPigeon", text)
                    clipboard.setPrimaryClip(clip)"""
                    
if "lastSyncedText = text // 防止回环触发" not in content:
    content = content.replace(mac_sync_target, new_mac_sync)

with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/service/BleForegroundService.kt', 'w') as f:
    f.write(content)
