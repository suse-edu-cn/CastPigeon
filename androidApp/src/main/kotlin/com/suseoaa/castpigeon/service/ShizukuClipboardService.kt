package com.suseoaa.castpigeon.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.RemoteCallbackList
import android.util.Log
import com.suseoaa.castpigeon.IClipboardChangeCallback
import com.suseoaa.castpigeon.IRootClipboard

/**
 * Shizuku 用户绑定服务实现类。
 *
 * 核心原理：Shizuku 会在独立的进程（运行于 shell 权限，UID=2000）中启动此类。
 * 由于拥有 Shell 权限，该进程可以无视前台焦点限制，直接在后台通过系统 Context 读取/写入剪贴板。
 */
class ShizukuClipboardService(private val context: Context) : IRootClipboard.Stub() {
    private val shellContext = ShellClipboardContext(context)
    private val callbacks = RemoteCallbackList<IClipboardChangeCallback>()
    private val pollHandler = Handler(Looper.getMainLooper())
    private var clipboardListenerRegistered = false
    private var pollingStarted = false
    private var lastNotifiedText: String? = null

    private val clipboardPoller = object : Runnable {
        override fun run() {
            val text = getClipboardText()
            if (!text.isNullOrEmpty() && text != lastNotifiedText) {
                lastNotifiedText = text
                Log.i("CastPigeonShizuku", "轮询到剪贴板变化，准备回调主进程: $text")
                notifyClipboardChanged(text)
            }

            if (pollingStarted) {
                pollHandler.postDelayed(this, CLIPBOARD_POLL_INTERVAL_MS)
            }
        }
    }

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        val text = getClipboardText()
        if (text.isNullOrEmpty() || text == lastNotifiedText) return@OnPrimaryClipChangedListener

        lastNotifiedText = text
        Log.i("CastPigeonShizuku", "监听到剪贴板变化，准备回调主进程: $text")
        notifyClipboardChanged(text)
    }

    init {
        Log.i("CastPigeonShizuku", "ShizukuClipboardService 实例化成功, context=$context")
    }

    override fun getClipboardText(): String? {
        Log.i("CastPigeonShizuku", "getClipboardText (Shizuku), myUID=${Process.myUid()}")
        return try {
            val clipboard = shellContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            val text = clip?.getItemAt(0)?.text?.toString()
            Log.i("CastPigeonShizuku", "getClipboardText 读取成功: $text")
            text
        } catch (e: Exception) {
            Log.e("CastPigeonShizuku", "getClipboardText 发生异常", e)
            null
        }
    }

    override fun setClipboardText(text: String?): Boolean {
        if (text == null) return false
        Log.i("CastPigeonShizuku", "setClipboardText: $text, myUID=${Process.myUid()}")
        return try {
            val clipboard = shellContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("CastPigeon", text)
            clipboard.setPrimaryClip(clip)
            Log.i("CastPigeonShizuku", "setClipboardText 写入成功")
            true
        } catch (e: Exception) {
            Log.e("CastPigeonShizuku", "setClipboardText 发生异常", e)
            false
        }
    }

    override fun registerClipboardCallback(callback: IClipboardChangeCallback?) {
        if (callback == null) return
        callbacks.register(callback)
        ensureClipboardListenerRegistered()
        ensureClipboardPollingStarted()
        Log.i("CastPigeonShizuku", "注册剪贴板变化回调成功")
    }

    override fun unregisterClipboardCallback(callback: IClipboardChangeCallback?) {
        if (callback == null) return
        callbacks.unregister(callback)
        Log.i("CastPigeonShizuku", "注销剪贴板变化回调成功")
    }

    override fun launchClipboardActivity() {
        Log.i("CastPigeonShizuku", "launchClipboardActivity() 被调用, myUID=${Process.myUid()}")
        try {
            val process = Runtime.getRuntime().exec(arrayOf(
                "am", "start",
                "-n", "com.suseoaa.castpigeon/.ui.TransparentClipboardActivity",
                "--activity-no-animation",
                "-a", "ACTION_SYNC_CLIPBOARD_AUTO"
            ))
            val exitCode = process.waitFor()
            Log.i("CastPigeonShizuku", "am start 退出码: $exitCode")
        } catch (e: Exception) {
            Log.e("CastPigeonShizuku", "am start 启动失败", e)
        }
    }

    private fun ensureClipboardListenerRegistered() {
        if (clipboardListenerRegistered) return

        try {
            val clipboard = shellContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.addPrimaryClipChangedListener(clipboardListener)
            clipboardListenerRegistered = true
            Log.i("CastPigeonShizuku", "Shizuku 剪贴板监听器注册成功")
        } catch (e: Exception) {
            Log.e("CastPigeonShizuku", "Shizuku 剪贴板监听器注册失败", e)
        }
    }

    private fun ensureClipboardPollingStarted() {
        if (pollingStarted) return

        pollingStarted = true
        pollHandler.post(clipboardPoller)
        Log.i("CastPigeonShizuku", "Shizuku 剪贴板轮询兜底已启动")
    }

    private fun notifyClipboardChanged(text: String) {
        val count = callbacks.beginBroadcast()
        try {
            for (i in 0 until count) {
                try {
                    callbacks.getBroadcastItem(i).onClipboardChanged(text)
                } catch (e: Exception) {
                    Log.e("CastPigeonShizuku", "回调剪贴板变化失败", e)
                }
            }
        } finally {
            callbacks.finishBroadcast()
        }
    }

    /**
     * Shizuku UserService 运行在 shell UID 下，但默认 Context 常带 android 包名。
     * ClipboardService 会校验调用 UID 是否拥有 callingPackage；shell UID 应使用
     * com.android.shell，否则会抛出 "Package android does not belong to 2000"。
     */
    private class ShellClipboardContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName(): String = SHELL_PACKAGE

        override fun getOpPackageName(): String = SHELL_PACKAGE

        override fun getApplicationContext(): Context = this

        override fun createPackageContext(packageName: String?, flags: Int): Context = this

        override fun getSystemService(name: String): Any? {
            val service = super.getSystemService(name) ?: return null
            if (name == Context.CLIPBOARD_SERVICE || name == Context.ACTIVITY_SERVICE) {
                replaceServiceContext(service)
            }
            return service
        }

        override fun getAttributionSource(): android.content.AttributionSource {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                android.content.AttributionSource.Builder(Process.SHELL_UID)
                    .setPackageName(SHELL_PACKAGE)
                    .build()
            } else {
                super.getAttributionSource()
            }
        }

        override fun getDeviceId(): Int = 0

        private fun replaceServiceContext(service: Any) {
            try {
                val field = service.javaClass.getDeclaredField("mContext")
                field.isAccessible = true
                field.set(service, this)
            } catch (e: ReflectiveOperationException) {
                Log.w("CastPigeonShizuku", "替换系统服务 Context 失败: ${service.javaClass.name}", e)
            }
        }

        companion object {
            private const val SHELL_PACKAGE = "com.android.shell"
        }
    }

    private companion object {
        private const val CLIPBOARD_POLL_INTERVAL_MS = 1_000L
    }
}
