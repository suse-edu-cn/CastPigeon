import re

with open('/Users/vincent/.gemini/antigravity/brain/32256c08-68d6-4382-aeed-20831567e350/task.md', 'r') as f:
    content = f.read()

content = content.replace("[ ] 在 `build.gradle.kts` 引入 Shizuku 依赖", "[x] 在 `build.gradle.kts` 引入 Shizuku 依赖")
content = content.replace("[ ] 编写 `PrivilegeManager.kt` 实现 `su` 与 `Shizuku` 的执行判断逻辑", "[x] 编写 `PrivilegeManager.kt` 实现 `su` 与 `Shizuku` 的执行判断逻辑")
content = content.replace("[ ] 修改 `BleForegroundService.kt` 实现真正的自动降级", "[x] 修改 `BleForegroundService.kt` 实现真正的自动降级")

with open('/Users/vincent/.gemini/antigravity/brain/32256c08-68d6-4382-aeed-20831567e350/task.md', 'w') as f:
    f.write(content)
