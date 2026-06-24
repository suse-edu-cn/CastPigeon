with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/ui/MainScreen.kt', 'r') as f:
    content = f.read()

target = """                .hazeChild(
                    state = hazeState,
                    style = HazeStyle(backgroundColor = if (isSystemDark) Color.White.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.6f), blurRadius = 30.dp) Color.White.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.6f), blurRadius = 30.dp)
                        
                        blurRadius = 30.dp
                    )
                )"""

new_target = """                .hazeChild(
                    state = hazeState,
                    style = HazeStyle(backgroundColor = if (isSystemDark) Color.White.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.6f), blurRadius = 30.dp)
                )"""

if target in content:
    content = content.replace(target, new_target)

# Add imports correctly
if "import dev.chrisbanes.haze.hazeChild" not in content:
    content = content.replace("import dev.chrisbanes.haze.hazeSource", "import dev.chrisbanes.haze.hazeSource\nimport dev.chrisbanes.haze.hazeChild\nimport dev.chrisbanes.haze.HazeStyle")

with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/ui/MainScreen.kt', 'w') as f:
    f.write(content)
