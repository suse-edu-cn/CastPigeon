with open('androidApp/src/main/kotlin/com/suseoaa/castpigeon/ui/MainScreen.kt', 'r') as f:
    lines = f.readlines()

in_dashboard = False
in_settings = False

for i, line in enumerate(lines):
    if line.startswith("@Composable") and "fun DashboardContent" in lines[i+1]:
        in_dashboard = True
    if in_dashboard:
        print(line, end='')
        if line.startswith("}") and lines[i-1] == "    }\n":
            in_dashboard = False

print("\n---SETTINGS---\n")
for i, line in enumerate(lines):
    if line.startswith("@Composable") and "fun SettingsContent" in lines[i+1]:
        in_settings = True
    if in_settings:
        print(line, end='')
        if line.startswith("}") and lines[i-1] == "    }\n":
            in_settings = False

