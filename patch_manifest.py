with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/AndroidManifest.xml', 'r') as f:
    content = f.read()

target = """        <activity
            android:name=".ui.TransparentClipboardActivity\""""
new_target = """        <service
            android:name=".service.RootClipboardService"
            android:exported="false" />

        <activity
            android:name=".ui.TransparentClipboardActivity\""""

if ".service.RootClipboardService" not in content:
    content = content.replace(target, new_target)

with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/AndroidManifest.xml', 'w') as f:
    f.write(content)
