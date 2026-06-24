with open('/Users/vincent/.gemini/antigravity/brain/32256c08-68d6-4382-aeed-20831567e350/task.md', 'r') as f:
    content = f.read()

content = content.replace("[ ] 在 `SettingsContent` 增加「高级实验室」区块", "[x] 在 `SettingsContent` 增加「高级实验室」区块")
content = content.replace("[ ] 添加提权开关（带状态提示），调用 `PrivilegeManager` 并给出 Shizuku 提示", "[x] 添加提权开关（带状态提示），调用 `PrivilegeManager` 并给出 Shizuku 提示")
content = content.replace("[ ] 采用自适应暗色的白色毛玻璃卡片风格", "[x] 采用自适应暗色的白色毛玻璃卡片风格")
content = content.replace("[ ] 使用自定义白色配色的 Switch 替换原有的启停大按钮", "[x] 使用自定义白色配色的 Switch 替换原有的启停大按钮")
content = content.replace("[ ] 设计红、黄、绿状态呼吸灯，并与 `ConnectionState` 绑定", "[x] 设计红、黄、绿状态呼吸灯，并与 `ConnectionState` 绑定")
content = content.replace("[ ] 重新排版在线设备列表与测试消息视图，使其更具极客感", "[x] 重新排版在线设备列表与测试消息视图，使其更具极客感")
content = content.replace("[ ] 编译验证无报错", "[x] 编译验证无报错")
content = content.replace("[ ] 编写 Walkthrough 总结成果", "[x] 编写 Walkthrough 总结成果")

with open('/Users/vincent/.gemini/antigravity/brain/32256c08-68d6-4382-aeed-20831567e350/task.md', 'w') as f:
    f.write(content)
