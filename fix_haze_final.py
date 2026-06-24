with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/ui/MainScreen.kt', 'r') as f:
    content = f.read()

content = content.replace("dev.chrisbanes.haze.hazeChild", "hazeChild")
content = content.replace("dev.chrisbanes.haze.HazeStyle", "HazeStyle")
content = content.replace("HazeStyle(backgroundColor = if (isSystemDark) Color.White.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.6f), blurRadius = 30.dp)", "HazeStyle(blurRadius = 30.dp)")

with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/ui/MainScreen.kt', 'w') as f:
    f.write(content)
print("Final fix applied")
