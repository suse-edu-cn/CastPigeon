import re

with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/ui/MainScreen.kt', 'r') as f:
    content = f.read()

# Fix PrivilegeManager.kt Shizuku call
with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/service/PrivilegeManager.kt', 'r') as f2:
    pm_content = f2.read()
pm_content = pm_content.replace(
    'Shizuku.newProcess(arrayOf("sh", "-c", script), null, null)',
    'Shizuku.newProcess(arrayOf("sh", "-c", script), null as Array<String>?, null as String?)'
)
with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/service/PrivilegeManager.kt', 'w') as f2:
    f2.write(pm_content)

# Fix MainScreen.kt call to DashboardContent
target_call = """                DashboardContent(
                    stateMachine = stateMachine,
                    blePeripheral = blePeripheral,
                    bleCentral = bleCentral,
                    connectionState = connectionState,
                    role = role,
                    workMode = workMode,
                    myHashStr = myHashStr,
                    receivedMockMessage = receivedMockMessage,
                    onAction = { /* handled inside */ }
                )"""

new_call = """                DashboardContent(
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

if target_call in content:
    content = content.replace(target_call, new_call)
else:
    # Try regex because indentation might differ
    content = re.sub(r'DashboardContent\([\s\S]*?onAction = \{.*?\}.*?\)', new_call, content)

# Fix ConnectionState.Pairing -> ConnectionState.AdvertisingOrScanning
content = content.replace("ConnectionState.Pairing ->", "ConnectionState.AdvertisingOrScanning ->")
content = content.replace("ConnectionState.AdvertisingOrScanning -> if (role == DeviceRole.Sender) \"等待 Mac 连接\" else \"寻找 Mac 中\"", "ConnectionState.Connecting -> \"正在建立加密通道\"")

# Fix ConnectionState when branch
target_when = """                        text = when (connectionState) {
                            ConnectionState.Idle -> "系统待机中"
                            ConnectionState.AdvertisingOrScanning -> "正在寻找发送端"
                            ConnectionState.Connecting -> "正在建立加密通道"
                            ConnectionState.Connecting -> "正在建立加密通道"
                            ConnectionState.Transferring -> "已连接 Mac - 静默监控中"
                        },"""
new_when = """                        text = when (connectionState) {
                            ConnectionState.Idle -> "系统待机中"
                            ConnectionState.AdvertisingOrScanning -> if (role == DeviceRole.Sender) "等待连接..." else "正在寻找广播..."
                            ConnectionState.Connecting -> "正在建立加密通道"
                            ConnectionState.Transferring -> "已连接 - 静默监控中"
                            else -> "未知状态"
                        },"""
# Because string replacement might fail if duplicates, let's use regex
content = re.sub(r'text = when \(connectionState\) \{[\s\S]*?\},', new_when, content)


# Fix Haze styling
content = content.replace("dev.chrisbanes.haze.hazeChild(", "dev.chrisbanes.haze.hazeChild(")
content = content.replace("dev.chrisbanes.haze.HazeStyle(", "dev.chrisbanes.haze.HazeStyle(")

# Wait, `dev.chrisbanes.haze` might not be imported or `dev` is unresolvable because `import dev.chrisbanes.haze.hazeSource` exists but `hazeChild` isn't imported.
# Let's add imports
if "import dev.chrisbanes.haze.hazeChild" not in content:
    content = content.replace("import dev.chrisbanes.haze.hazeSource", "import dev.chrisbanes.haze.hazeSource\nimport dev.chrisbanes.haze.hazeChild\nimport dev.chrisbanes.haze.HazeStyle")

# Fix Color tint
# HazeTint was changed or is a class in newer versions, but if `HazeStyle(tint = Color...)` fails, we can just use `HazeStyle(backgroundColor = Color...)` or just pass the color. Let's just use `HazeStyle(blurRadius = 30.dp)` and no tint, because the Box already has a background color!
content = content.replace("tint = if (isSystemDark) Color.White.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.6f),", "")

with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/ui/MainScreen.kt', 'w') as f:
    f.write(content)
print("Fixed MainScreen errors")

