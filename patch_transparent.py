with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/ui/TransparentClipboardActivity.kt', 'r') as f:
    content = f.read()

target = """        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
            if (!text.isNullOrEmpty()) {
                try {
                    val payload = "CLIP|$text"
                    android.util.Log.i("CastPigeon", "TransparentClipboardActivity 准备发送 payload...")
                    AppConnectionManager.blePeripheral.sendNotificationData(payload.encodeToByteArray())
                    Toast.makeText(this@TransparentClipboardActivity, "已推送到 Mac (降级)", Toast.LENGTH_SHORT).show()
                    android.util.Log.i("CastPigeon", "TransparentClipboardActivity 发送成功！")
                } catch (e: Exception) {
                    android.util.Log.e("CastPigeon", "TransparentClipboardActivity 发送异常", e)
                    e.printStackTrace()
                }
            } else {
                Toast.makeText(this@TransparentClipboardActivity, "剪贴板为空，无法推送", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this@TransparentClipboardActivity, "读取剪贴板失败", Toast.LENGTH_SHORT).show()
        }"""

new_target = """        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            
            if (intent.action == "ACTION_SET_CLIPBOARD") {
                val textToSet = intent.getStringExtra("text")
                if (textToSet != null) {
                    val clip = android.content.ClipData.newPlainText("CastPigeon", textToSet)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this@TransparentClipboardActivity, "已同步 Mac 剪贴板", Toast.LENGTH_SHORT).show()
                    android.util.Log.i("CastPigeon", "TransparentClipboardActivity 设置剪贴板成功: $textToSet")
                }
            } else {
                val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                if (!text.isNullOrEmpty()) {
                    try {
                        val payload = "CLIP|$text"
                        android.util.Log.i("CastPigeon", "TransparentClipboardActivity 准备发送 payload...")
                        AppConnectionManager.blePeripheral.sendNotificationData(payload.encodeToByteArray())
                        Toast.makeText(this@TransparentClipboardActivity, "已推送到 Mac (降级)", Toast.LENGTH_SHORT).show()
                        android.util.Log.i("CastPigeon", "TransparentClipboardActivity 发送成功！")
                    } catch (e: Exception) {
                        android.util.Log.e("CastPigeon", "TransparentClipboardActivity 发送异常", e)
                        e.printStackTrace()
                    }
                } else {
                    Toast.makeText(this@TransparentClipboardActivity, "剪贴板为空，无法推送", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this@TransparentClipboardActivity, "剪贴板操作失败", Toast.LENGTH_SHORT).show()
        }"""

if "ACTION_SET_CLIPBOARD" not in content:
    content = content.replace(target, new_target)

with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/ui/TransparentClipboardActivity.kt', 'w') as f:
    f.write(content)
