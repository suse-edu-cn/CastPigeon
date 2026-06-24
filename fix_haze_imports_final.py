with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/ui/MainScreen.kt', 'r') as f:
    lines = f.readlines()

new_lines = []
imports_seen = set()

for line in lines:
    if line.startswith("import "):
        if line in imports_seen:
            continue
        imports_seen.add(line)
    new_lines.append(line)

with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/ui/MainScreen.kt', 'w') as f:
    f.writelines(new_lines)
print("Duplicate imports removed")
