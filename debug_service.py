with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/service/BleForegroundService.kt', 'r') as f:
    content = f.read()

target = """            if (intent?.action == "com.suseoaa.castpigeon.ACTION_SYNC_CLIPBOARD") {"""

new_target = """            android.util.Log.i("CastPigeon", "收到广播: ${intent?.action}")
            if (intent?.action == "com.suseoaa.castpigeon.ACTION_SYNC_CLIPBOARD") {
                android.util.Log.i("CastPigeon", "准备读取剪贴板并发送")
"""

if 'android.util.Log.i("CastPigeon", "收到广播:' not in content:
    content = content.replace(target, new_target)

with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/service/BleForegroundService.kt', 'w') as f:
    f.write(content)
