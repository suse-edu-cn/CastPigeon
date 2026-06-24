with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/ui/TransparentClipboardActivity.kt', 'r') as f:
    content = f.read()

target = """                try {
                    val payload = "CLIP|$text"
                    AppConnectionManager.blePeripheral.sendNotificationData(payload.encodeToByteArray())
                    Toast.makeText(this@TransparentClipboardActivity, "已推送到 Mac (降级)", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    e.printStackTrace()
                }"""

new_target = """                try {
                    val payload = "CLIP|$text"
                    android.util.Log.i("CastPigeon", "TransparentClipboardActivity 准备发送 payload...")
                    AppConnectionManager.blePeripheral.sendNotificationData(payload.encodeToByteArray())
                    Toast.makeText(this@TransparentClipboardActivity, "已推送到 Mac (降级)", Toast.LENGTH_SHORT).show()
                    android.util.Log.i("CastPigeon", "TransparentClipboardActivity 发送成功！")
                } catch (e: Exception) {
                    android.util.Log.e("CastPigeon", "TransparentClipboardActivity 发送异常", e)
                    e.printStackTrace()
                }"""

if "TransparentClipboardActivity 准备发送" not in content:
    content = content.replace(target, new_target)

with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/ui/TransparentClipboardActivity.kt', 'w') as f:
    f.write(content)
