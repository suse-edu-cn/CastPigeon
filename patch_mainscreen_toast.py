with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/ui/MainScreen.kt', 'r') as f:
    content = f.read()

target = 'android.widget.Toast.makeText(context, "提权失败，请检查是否已授权 Root 或启动 Shizuku", android.widget.Toast.LENGTH_LONG).show()'
new_target = 'android.widget.Toast.makeText(context, "提权失败！(KSU/APatch 用户请先在管理器中勾选 Root 权限，或检查 Shizuku 是否运行)", android.widget.Toast.LENGTH_LONG).show()'

content = content.replace(target, new_target)

with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/ui/MainScreen.kt', 'w') as f:
    f.write(content)
