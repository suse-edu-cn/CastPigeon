with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/service/BleForegroundService.kt', 'r') as f:
    content = f.read()

target = """            if (intent?.action == "com.suseoaa.castpigeon.ACTION_SYNC_CLIPBOARD") {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                if (!text.isNullOrEmpty()) {
                    val payload = "CLIP|$text"
                    try {
                        AppConnectionManager.blePeripheral.sendNotificationData(payload.encodeToByteArray())
                        Toast.makeText(this@BleForegroundService, "已推送到 Mac", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                } else {
                    Toast.makeText(this@BleForegroundService, "剪贴板为空或无法访问", Toast.LENGTH_SHORT).show()
                }
            } else if (intent?.action == "com.suseoaa.castpigeon.ACTION_COPY_CLIPBOARD") {"""

new_target = """            if (intent?.action == "com.suseoaa.castpigeon.ACTION_SYNC_CLIPBOARD") {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                if (!text.isNullOrEmpty()) {
                    val payload = "CLIP|$text"
                    try {
                        AppConnectionManager.blePeripheral.sendNotificationData(payload.encodeToByteArray())
                        Toast.makeText(this@BleForegroundService, "已推送到 Mac", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                } else {
                    // Fallback to Transparent Activity
                    val fallbackIntent = Intent(this@BleForegroundService, com.suseoaa.castpigeon.ui.TransparentClipboardActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    startActivity(fallbackIntent)
                }
            } else if (intent?.action == "com.suseoaa.castpigeon.ACTION_COPY_CLIPBOARD") {"""

content = content.replace(target, new_target)

with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/service/BleForegroundService.kt', 'w') as f:
    f.write(content)
