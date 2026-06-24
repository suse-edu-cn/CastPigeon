import re

with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/ui/MainScreen.kt', 'r') as f:
    content = f.read()

# Remove all bad haze imports
content = re.sub(r'import dev\.chrisbanes\.haze\..*\n', '', content)
content = content.replace("import hazeChild\n", "")

# Add them right after import top.yukonga.miuix.kmp.theme.MiuixTheme
new_imports = """import top.yukonga.miuix.kmp.theme.MiuixTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
"""
content = content.replace("import top.yukonga.miuix.kmp.theme.MiuixTheme", new_imports)

with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/ui/MainScreen.kt', 'w') as f:
    f.write(content)
print("Imports fixed")
