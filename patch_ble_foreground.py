with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/service/BleForegroundService.kt', 'r') as f:
    content = f.read()

# Add ROOT_CLIPBOARD_CHANGED to IntentFilter
filter_target = 'val filter = android.content.IntentFilter("com.suseoaa.castpigeon.ACTION_SYNC_CLIPBOARD")'
new_filter = 'val filter = android.content.IntentFilter("com.suseoaa.castpigeon.ACTION_SYNC_CLIPBOARD").apply { addAction("com.suseoaa.castpigeon.ROOT_CLIPBOARD_CHANGED") }'

if 'addAction("com.suseoaa.castpigeon.ROOT_CLIPBOARD_CHANGED")' not in content:
    content = content.replace(filter_target, new_filter)

# Handle intent actions in clipboardSyncReceiver
receiver_target = """            android.util.Log.i("CastPigeon", "收到广播: ${intent?.action}")
            if (intent?.action == "com.suseoaa.castpigeon.ACTION_SYNC_CLIPBOARD") {"""

new_receiver = """            android.util.Log.i("CastPigeon", "收到广播: ${intent?.action}")
            if (intent?.action == "com.suseoaa.castpigeon.ROOT_CLIPBOARD_CHANGED") {
                val text = intent.getStringExtra("text")
                if (!text.isNullOrEmpty() && text != lastSyncedText) {
                    lastSyncedText = text
                    val payload = "CLIP|$text"
                    android.util.Log.i("CastPigeon", "收到 Root 守护进程剪贴板更新，准备发送: $payload")
                    try {
                        AppConnectionManager.blePeripheral.sendNotificationData(payload.encodeToByteArray())
                        android.util.Log.i("CastPigeon", "自动发送剪贴板到 Mac 成功！")
                    } catch (e: Exception) {
                        android.util.Log.e("CastPigeon", "自动发送剪贴板失败", e)
                    }
                }
                return@BroadcastReceiver
            }

            if (intent?.action == "com.suseoaa.castpigeon.ACTION_SYNC_CLIPBOARD") {"""

if 'intent?.action == "com.suseoaa.castpigeon.ROOT_CLIPBOARD_CHANGED"' not in content:
    content = content.replace(receiver_target, new_receiver)

# Handle reading clipboard in ACTION_SYNC_CLIPBOARD
read_target = """                android.util.Log.i("CastPigeon", "准备读取剪贴板并发送")
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()"""
new_read = """                android.util.Log.i("CastPigeon", "准备读取剪贴板并发送")
                var text: String? = null
                if (PrivilegeManager.isPrivileged && PrivilegeManager.rootClipboard != null) {
                    try {
                        text = PrivilegeManager.rootClipboard?.getClipboardText()
                        android.util.Log.i("CastPigeon", "使用 RootService 读取剪贴板: $text")
                    } catch (e: Exception) {
                        android.util.Log.e("CastPigeon", "RootService 读取失败", e)
                    }
                }
                
                if (text == null) {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                }"""

if 'PrivilegeManager.rootClipboard?.getClipboardText()' not in content:
    content = content.replace(read_target, new_read)

# Handle writing clipboard when receiving from Mac
write_target = """            if (msg.startsWith("CLIP|")) {
                val text = msg.substring(5)
                lastSyncedText = text // 防止回环触发
                
                android.util.Log.w("CastPigeon", "收到 Mac 剪贴板，由于 Android 13+ 限制，拉起 TransparentClipboardActivity 抢焦点写入")"""
new_write = """            if (msg.startsWith("CLIP|")) {
                val text = msg.substring(5)
                lastSyncedText = text // 防止回环触发
                
                if (PrivilegeManager.isPrivileged && PrivilegeManager.rootClipboard != null) {
                    try {
                        PrivilegeManager.rootClipboard?.setClipboardText(text)
                        android.util.Log.i("CastPigeon", "使用 RootService 设置剪贴板成功")
                        android.widget.Toast.makeText(this@BleForegroundService, "已同步 Mac 剪贴板 (真·无感)", android.widget.Toast.LENGTH_SHORT).show()
                        return
                    } catch (e: Exception) {
                        android.util.Log.e("CastPigeon", "RootService 设置剪贴板失败", e)
                    }
                }
                
                android.util.Log.w("CastPigeon", "收到 Mac 剪贴板，拉起 TransparentClipboardActivity 抢焦点写入")"""

if 'PrivilegeManager.rootClipboard?.setClipboardText(text)' not in content:
    content = content.replace(write_target, new_write)

with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/service/BleForegroundService.kt', 'w') as f:
    f.write(content)
