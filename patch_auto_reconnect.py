with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/service/PrivilegeManager.kt', 'r') as f:
    content = f.read()

target = """object PrivilegeManager {
    val isPrivileged = MutableStateFlow(false)"""

new_target = """import android.content.SharedPreferences

object PrivilegeManager {
    val isPrivileged = MutableStateFlow(false)
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("castpigeon_prefs", Context.MODE_PRIVATE)
        val wasPrivileged = prefs?.getBoolean("isPrivileged", false) ?: false
        if (wasPrivileged) {
            Log.i("CastPigeonRoot", "Auto-reconnecting to RootService...")
            executeAppOpsCommand(context)
        }
    }"""

if "fun init(context: Context)" not in content:
    content = content.replace(target, new_target)

# Update onServiceConnected to save state
connected_target = """            Log.i("CastPigeonRoot", "RootService connected")
            rootClipboard = IRootClipboard.Stub.asInterface(service)
            isPrivileged.value = true"""
new_connected = """            Log.i("CastPigeonRoot", "RootService connected")
            rootClipboard = IRootClipboard.Stub.asInterface(service)
            isPrivileged.value = true
            prefs?.edit()?.putBoolean("isPrivileged", true)?.apply()"""
            
if "putBoolean" not in content:
    content = content.replace(connected_target, new_connected)

# Also handle explicit disconnect? Not strictly needed unless user wants to turn it off. Let's add a disable function.
with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/service/PrivilegeManager.kt', 'w') as f:
    f.write(content)
