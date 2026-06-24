import re

with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/AndroidManifest.xml', 'r') as f:
    content = f.read()

target = """        <activity
            android:exported="true"
            android:name=".MainActivity">"""

new_target = """        <activity
            android:name=".ui.TransparentClipboardActivity"
            android:exported="false"
            android:theme="@android:style/Theme.Translucent.NoTitleBar"
            android:excludeFromRecents="true"
            android:taskAffinity="" />

        <activity
            android:exported="true"
            android:name=".MainActivity">"""

if "TransparentClipboardActivity" not in content:
    content = content.replace(target, new_target)
    with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/AndroidManifest.xml', 'w') as f:
        f.write(content)
    print("Patched AndroidManifest.xml")
