with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/AndroidManifest.xml', 'r') as f:
    content = f.read()

# Make TransparentClipboardActivity exported so am start can reach it
old = 'android:name=".ui.TransparentClipboardActivity"\n            android:exported="false"'
new = 'android:name=".ui.TransparentClipboardActivity"\n            android:exported="true"'

if 'ui.TransparentClipboardActivity"\n            android:exported="false"' in content:
    content = content.replace(old, new)

with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/AndroidManifest.xml', 'w') as f:
    f.write(content)
