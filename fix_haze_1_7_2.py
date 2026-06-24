with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/ui/MainScreen.kt', 'r') as f:
    content = f.read()

# Fix imports
if "import dev.chrisbanes.haze.hazeChild" in content:
    content = content.replace("import dev.chrisbanes.haze.hazeChild", "import dev.chrisbanes.haze.hazeEffect\nimport dev.chrisbanes.haze.HazeTint")

# Fix hazeChild to hazeEffect
content = content.replace(".hazeChild(", ".hazeEffect(")

# Fix HazeStyle
target_style = "HazeStyle(blurRadius = 30.dp)"
new_style = "HazeStyle(backgroundColor = if (isSystemDark) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.05f) else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.6f), tint = dev.chrisbanes.haze.HazeTint(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.1f)), blurRadius = 30.dp)"
content = content.replace(target_style, new_style)

# If the previous fix left "dev.chrisbanes.haze.HazeStyle", clean it up or leave it.

with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/ui/MainScreen.kt', 'w') as f:
    f.write(content)
