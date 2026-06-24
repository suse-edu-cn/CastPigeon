with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/service/RootClipboardService.kt', 'r') as f:
    content = f.read()

target_class = "class RootClipboardService : RootService() {"
new_class = """class RootClipboardService : RootService() {

    private var isListening = false
    private var lastText: String? = null

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
            if (!text.isNullOrEmpty() && text != lastText) {
                lastText = text
                Log.i("CastPigeonRoot", "Root daemon detected clipboard change: $text")
                val intent = Intent("com.suseoaa.castpigeon.ROOT_CLIPBOARD_CHANGED")
                intent.putExtra("text", text)
                sendBroadcast(intent)
            }
        } catch (e: Exception) {
            Log.e("CastPigeonRoot", "Failed to read in root listener", e)
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.addPrimaryClipChangedListener(clipboardListener)
            isListening = true
            Log.i("CastPigeonRoot", "RootClipboardService started listening to clipboard")
        } catch (e: Exception) {
            Log.e("CastPigeonRoot", "Failed to register root listener", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (isListening) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.removePrimaryClipChangedListener(clipboardListener)
            }
        } catch (e: Exception) {}
    }"""

if "private var isListening = false" not in content:
    content = content.replace(target_class, new_class)

with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/service/RootClipboardService.kt', 'w') as f:
    f.write(content)
