with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/service/BleForegroundService.kt', 'r') as f:
    content = f.read()

target = """            if (msg.startsWith("CLIP|")) {
                val text = msg.substring(5)
                lastSyncedText = text // 防止回环触发
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                try {
                    val clip = android.content.ClipData.newPlainText("CastPigeon", text)
                    clipboard.setPrimaryClip(clip)
                    android.widget.Toast.makeText(this@BleForegroundService, "已同步 Mac 剪贴板", android.widget.Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    android.util.Log.e("CastPigeon", "后台设置剪贴板失败", e)
                }
            }"""

new_target = """            if (msg.startsWith("CLIP|")) {
                val text = msg.substring(5)
                lastSyncedText = text // 防止回环触发
                
                android.util.Log.w("CastPigeon", "收到 Mac 剪贴板，由于 Android 13+ 限制，拉起 TransparentClipboardActivity 抢焦点写入")
                val fallbackIntent = android.content.Intent(this@BleForegroundService, com.suseoaa.castpigeon.ui.TransparentClipboardActivity::class.java).apply {
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                    action = "ACTION_SET_CLIPBOARD"
                    putExtra("text", text)
                }
                startActivity(fallbackIntent)
            }"""

if "ACTION_SET_CLIPBOARD" not in content:
    content = content.replace(target, new_target)

with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/service/BleForegroundService.kt', 'w') as f:
    f.write(content)
