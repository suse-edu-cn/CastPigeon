with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/service/BleForegroundService.kt', 'r') as f:
    content = f.read()

# Fix return
content = content.replace("return@BroadcastReceiver", "return")

# Fix read logic
read_target = """                android.util.Log.i("CastPigeon", "准备读取剪贴板并发送")

                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()"""
new_read = """                android.util.Log.i("CastPigeon", "准备读取剪贴板并发送")

                var text: String? = null
                if (PrivilegeManager.isPrivileged && PrivilegeManager.rootClipboard != null) {
                    try {
                        text = PrivilegeManager.rootClipboard?.clipboardText
                        android.util.Log.i("CastPigeon", "使用 RootService 读取剪贴板: $text")
                    } catch (e: Exception) {
                        android.util.Log.e("CastPigeon", "RootService 读取失败", e)
                    }
                }
                
                if (text == null) {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                }"""

if "text = PrivilegeManager.rootClipboard?.clipboardText" not in content:
    content = content.replace(read_target, new_read)

with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/service/BleForegroundService.kt', 'w') as f:
    f.write(content)
