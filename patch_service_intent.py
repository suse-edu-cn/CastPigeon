import re

with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/service/BleForegroundService.kt', 'r') as f:
    content = f.read()

# Remove ACTION_SYNC_CLIPBOARD from the BroadcastReceiver logic
target1 = """            if (intent?.action == "com.suseoaa.castpigeon.ACTION_SYNC_CLIPBOARD") {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                if (!text.isNullOrEmpty()) {
                    val payload = "CLIP|" + text
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

new_target1 = """            if (intent?.action == "com.suseoaa.castpigeon.ACTION_COPY_CLIPBOARD") {"""

if target1 in content:
    content = content.replace(target1, new_target1)

# Remove ACTION_SYNC_CLIPBOARD from the IntentFilter
target2 = """            addAction("com.suseoaa.castpigeon.ACTION_SYNC_CLIPBOARD")
            addAction("com.suseoaa.castpigeon.ACTION_COPY_CLIPBOARD")"""

new_target2 = """            addAction("com.suseoaa.castpigeon.ACTION_COPY_CLIPBOARD")"""

if target2 in content:
    content = content.replace(target2, new_target2)

# Update the buildNotification PendingIntent
target3 = """        val syncIntent = Intent("com.suseoaa.castpigeon.ACTION_SYNC_CLIPBOARD").apply {
            setPackage(packageName)
        }
        val syncPendingIntent = android.app.PendingIntent.getBroadcast("""

new_target3 = """        val syncIntent = Intent(this, com.suseoaa.castpigeon.ui.TransparentClipboardActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val syncPendingIntent = android.app.PendingIntent.getActivity("""

if target3 in content:
    content = content.replace(target3, new_target3)

with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/service/BleForegroundService.kt', 'w') as f:
    f.write(content)

print("Patched BleForegroundService.kt")
