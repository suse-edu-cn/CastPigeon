import re

with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/ui/MainScreen.kt', 'r') as f:
    content = f.read()

# Fix PrivilegeManager reflection
with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/service/PrivilegeManager.kt', 'r') as f2:
    pm_content = f2.read()

target_shizuku = 'val process = Shizuku.newProcess(arrayOf("sh", "-c", script), null as Array<String>?, null as String?)'
new_shizuku = """val newProcessMethod = Shizuku::class.java.getMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            val process = newProcessMethod.invoke(null, arrayOf("sh", "-c", script), null, null) as Process"""
if target_shizuku in pm_content:
    pm_content = pm_content.replace(target_shizuku, new_shizuku)
with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/service/PrivilegeManager.kt', 'w') as f2:
    f2.write(pm_content)

# Fix MainScreen.kt DashboardContent call
# It's inside the HorizontalPager
start_idx = content.find("DashboardContent(")
if start_idx != -1:
    end_idx = content.find(")", start_idx)
    if end_idx != -1:
        old_call = content[start_idx:end_idx+1]
        new_call = """DashboardContent(
                    stateMachine = stateMachine,
                    blePeripheral = blePeripheral,
                    bleCentral = bleCentral,
                    connectionState = connectionState,
                    role = role,
                    workMode = workMode,
                    pairingDeviceName = pairingDeviceName,
                    connectedDeviceName = connectedDeviceName,
                    deviceHash = deviceHash,
                    boundMacs = boundMacs,
                    myName = myName,
                    prefs = prefs,
                    hazeState = hazeState
                )"""
        content = content.replace(old_call, new_call)

# Fix dev.chrisbanes.haze Unresolved reference
# It's caused by dev.chrisbanes.haze.HazeStyle not being imported, or trying to use the package name inline when it's shadowed.
content = content.replace("dev.chrisbanes.haze.hazeChild", "hazeChild")
content = content.replace("dev.chrisbanes.haze.HazeStyle", "HazeStyle")
if "import dev.chrisbanes.haze.HazeStyle" not in content:
    content = content.replace("import dev.chrisbanes.haze.hazeSource", "import dev.chrisbanes.haze.hazeSource\nimport dev.chrisbanes.haze.hazeChild\nimport dev.chrisbanes.haze.HazeStyle")

# Fix HazeStyle Overload resolution ambiguity
# backgroundColor = Color..., blurRadius = ...
content = content.replace("HazeStyle(", "HazeStyle(backgroundColor = if (isSystemDark) Color.White.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.6f), blurRadius = 30.dp)")
# the previous replace might result in `HazeStyle(backgroundColor=... blurRadius=30.dp, blurRadius = 30.dp)` if it already had blurRadius.
# Let's fix that using regex.
content = re.sub(r'HazeStyle\([\s\S]*?\)', 'HazeStyle(backgroundColor = if (isSystemDark) Color.White.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.6f), blurRadius = 30.dp)', content)


with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/ui/MainScreen.kt', 'w') as f:
    f.write(content)
print("Fixed again")
