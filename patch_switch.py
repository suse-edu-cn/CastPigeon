with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/service/PrivilegeManager.kt', 'r') as f:
    content = f.read()

disable_target = """        override fun onServiceDisconnected(name: ComponentName?) {
            Log.i("CastPigeonRoot", "RootService disconnected")
            rootClipboard = null
            isPrivileged.value = false
        }
    }"""
new_disable = """        override fun onServiceDisconnected(name: ComponentName?) {
            Log.i("CastPigeonRoot", "RootService disconnected")
            rootClipboard = null
            isPrivileged.value = false
        }
    }

    fun disable() {
        Log.i("CastPigeonRoot", "Disabling RootService...")
        try {
            RootService.unbind(connection)
        } catch (e: Exception) {}
        rootClipboard = null
        isPrivileged.value = false
        prefs?.edit()?.putBoolean("isPrivileged", false)?.apply()
    }"""

if "fun disable()" not in content:
    content = content.replace(disable_target, new_disable)

with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/service/PrivilegeManager.kt', 'w') as f:
    f.write(content)

with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/ui/MainScreen.kt', 'r') as f:
    content2 = f.read()

switch_target = """                        onCheckedChange = { checked ->
                            if (checked && !isPrivileged) {
                                val success = com.suseoaa.castpigeon.service.PrivilegeManager.executeAppOpsCommand(context)
                                if (success) {
                                    android.widget.Toast.makeText(context, "提权成功！已开启真·静默模式", android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    android.widget.Toast.makeText(context, "提权失败！(KSU/APatch 用户请先在管理器中勾选 Root 权限，或检查 Shizuku 是否运行)", android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        }"""
new_switch = """                        onCheckedChange = { checked ->
                            if (checked && !isPrivileged) {
                                val success = com.suseoaa.castpigeon.service.PrivilegeManager.executeAppOpsCommand(context)
                                if (success) {
                                    android.widget.Toast.makeText(context, "提权成功！已开启真·静默模式", android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    android.widget.Toast.makeText(context, "提权失败！(KSU/APatch 用户请先在管理器中勾选 Root 权限)", android.widget.Toast.LENGTH_LONG).show()
                                }
                            } else if (!checked && isPrivileged) {
                                com.suseoaa.castpigeon.service.PrivilegeManager.disable()
                                android.widget.Toast.makeText(context, "已关闭真·静默模式", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }"""

if "PrivilegeManager.disable()" not in content2:
    content2 = content2.replace(switch_target, new_switch)

with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/ui/MainScreen.kt', 'w') as f:
    f.write(content2)

with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/CastPigeonApp.kt', 'r') as f:
    content3 = f.read()

app_target = """        Log.i("CastPigeonApp", "Application onCreate")"""
new_app = """        Log.i("CastPigeonApp", "Application onCreate")
        com.suseoaa.castpigeon.service.PrivilegeManager.init(this)"""

if "PrivilegeManager.init" not in content3:
    content3 = content3.replace(app_target, new_app)

with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/CastPigeonApp.kt', 'w') as f:
    f.write(content3)
