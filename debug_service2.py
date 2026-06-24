with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/service/BleForegroundService.kt', 'r') as f:
    content = f.read()

target = """            android.util.Log.i("CastPigeon", "准备读取剪贴板并发送")
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
                }"""

new_target = """            android.util.Log.i("CastPigeon", "准备读取剪贴板并发送")
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                android.util.Log.i("CastPigeon", "读取剪贴板结果: text=$text")
                if (!text.isNullOrEmpty()) {
                    val payload = "CLIP|$text"
                    try {
                        android.util.Log.i("CastPigeon", "调用 blePeripheral.sendNotificationData 准备发送 payload...")
                        AppConnectionManager.blePeripheral.sendNotificationData(payload.encodeToByteArray())
                        Toast.makeText(this@BleForegroundService, "已推送到 Mac", Toast.LENGTH_SHORT).show()
                        android.util.Log.i("CastPigeon", "调用 blePeripheral.sendNotificationData 完成！")
                    } catch (e: Exception) {
                        android.util.Log.e("CastPigeon", "调用发送异常", e)
                        e.printStackTrace()
                    }
                } else {
                    android.util.Log.w("CastPigeon", "剪贴板为空或者被拦截，执行降级方案：拉起 TransparentClipboardActivity")
                    // Fallback to Transparent Activity
                    val fallbackIntent = Intent(this@BleForegroundService, com.suseoaa.castpigeon.ui.TransparentClipboardActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    startActivity(fallbackIntent)
                }"""

if "读取剪贴板结果:" not in content:
    content = content.replace(target, new_target)

with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/service/BleForegroundService.kt', 'w') as f:
    f.write(content)
