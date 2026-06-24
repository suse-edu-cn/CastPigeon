package com.suseoaa.castpigeon.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteCallbackList
import android.util.Log
import com.suseoaa.castpigeon.IClipboardChangeCallback
import com.suseoaa.castpigeon.IRootClipboard
import com.topjohnwu.superuser.ipc.RootService

/**
 * Root 权限守护进程服务（UID=0）。
 *
 * Android 13+ 的 ClipboardService 会限制普通后台进程读取剪贴板。
 * Root 模式尽量在 RootService 进程中直接读写；如果 ROM 仍然限制 UID=0，
 * 再降级到 root 切 shell UID 执行系统 clipboard 命令。
 */
class RootClipboardService : RootService() {
    private val callbacks = RemoteCallbackList<IClipboardChangeCallback>()
    private val pollHandler = Handler(Looper.getMainLooper())
    private var clipboardListenerRegistered = false
    private var pollingStarted = false
    private var lastNotifiedText: String? = null

    private val clipboardPoller = object : Runnable {
        override fun run() {
            val text = getClipboardTextInternal()
            if (!text.isNullOrEmpty() && text != lastNotifiedText) {
                lastNotifiedText = text
                Log.i("CastPigeonRoot", "Root 轮询到剪贴板变化，准备回调主进程: $text")
                notifyClipboardChanged(text)
            }

            if (pollingStarted) {
                pollHandler.postDelayed(this, CLIPBOARD_POLL_INTERVAL_MS)
            }
        }
    }

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        val text = getClipboardTextInternal()
        if (text.isNullOrEmpty() || text == lastNotifiedText) return@OnPrimaryClipChangedListener

        lastNotifiedText = text
        Log.i("CastPigeonRoot", "Root 监听到剪贴板变化，准备回调主进程: $text")
        notifyClipboardChanged(text)
    }

    override fun onCreate() {
        super.onCreate()
        Log.i("CastPigeonRoot", "RootClipboardService onCreate, myUID=${android.os.Process.myUid()}")
    }

    override fun onDestroy() {
        super.onDestroy()
        pollingStarted = false
        pollHandler.removeCallbacksAndMessages(null)
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.removePrimaryClipChangedListener(clipboardListener)
        } catch (e: Exception) {
            Log.w("CastPigeonRoot", "移除 Root 剪贴板监听器失败", e)
        }
        callbacks.kill()
        Log.i("CastPigeonRoot", "RootClipboardService onDestroy")
    }

    override fun onBind(intent: Intent): IBinder {
        Log.i("CastPigeonRoot", "RootClipboardService onBind")
        return RootClipboardBinder()
    }

    inner class RootClipboardBinder : IRootClipboard.Stub() {

        /**
         * 以 UID=0 身份通过 am start 启动 TransparentClipboardActivity。
         * am start 经由系统 ActivityManager 执行，可绕过前台服务的后台 Activity 启动限制。
         * TransparentClipboardActivity 获得焦点后读取剪贴板并通过 BLE 发送至 Mac。
         */
        override fun launchClipboardActivity() {
            Log.i("CastPigeonRoot", "launchClipboardActivity() called from main process, myUID=${android.os.Process.myUid()}")
            try {
                val process = Runtime.getRuntime().exec(arrayOf(
                    "am", "start",
                    "-n", "com.suseoaa.castpigeon/.ui.TransparentClipboardActivity",
                    "--activity-no-animation",
                    "-a", "ACTION_SYNC_CLIPBOARD_AUTO"
                ))
                val exitCode = process.waitFor()
                val output = process.inputStream.bufferedReader().readText().trim()
                val errorOutput = process.errorStream.bufferedReader().readText().trim()
                Log.i("CastPigeonRoot", "am start result: exitCode=$exitCode, output=$output${if (errorOutput.isNotEmpty()) ", error=$errorOutput" else ""}")
            } catch (e: Exception) {
                Log.e("CastPigeonRoot", "am start failed", e)
            }
        }

        override fun getClipboardText(): String? {
            Log.i("CastPigeonRoot", "getClipboardText, myUID=${android.os.Process.myUid()}")
            return getClipboardTextInternal()
        }

        override fun setClipboardText(text: String?): Boolean {
            if (text == null) return false
            Log.i("CastPigeonRoot", "setClipboardText: $text, myUID=${android.os.Process.myUid()}")
            return if (setClipboardTextDirect(text)) {
                true
            } else {
                setClipboardTextByCommand(text)
            }
        }

        override fun registerClipboardCallback(callback: IClipboardChangeCallback?) {
            if (callback == null) return
            callbacks.register(callback)
            ensureClipboardListenerRegistered()
            ensureClipboardPollingStarted()
            Log.i("CastPigeonRoot", "Root 剪贴板变化回调注册成功")
        }

        override fun unregisterClipboardCallback(callback: IClipboardChangeCallback?) {
            if (callback == null) return
            callbacks.unregister(callback)
            Log.i("CastPigeonRoot", "Root 剪贴板变化回调注销成功")
        }
    }

    private fun ensureClipboardListenerRegistered() {
        if (clipboardListenerRegistered) return

        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.addPrimaryClipChangedListener(clipboardListener)
            clipboardListenerRegistered = true
            Log.i("CastPigeonRoot", "Root 剪贴板监听器注册成功")
        } catch (e: Exception) {
            Log.e("CastPigeonRoot", "Root 剪贴板监听器注册失败", e)
        }
    }

    private fun ensureClipboardPollingStarted() {
        if (pollingStarted) return

        pollingStarted = true
        pollHandler.post(clipboardPoller)
        Log.i("CastPigeonRoot", "Root 剪贴板轮询兜底已启动")
    }

    private fun notifyClipboardChanged(text: String) {
        val count = callbacks.beginBroadcast()
        try {
            for (i in 0 until count) {
                try {
                    callbacks.getBroadcastItem(i).onClipboardChanged(text)
                } catch (e: Exception) {
                    Log.e("CastPigeonRoot", "Root 回调剪贴板变化失败", e)
                }
            }
        } finally {
            callbacks.finishBroadcast()
        }
    }

    private fun getClipboardTextInternal(): String? {
        return getClipboardTextDirect() ?: getClipboardTextByCommand()
    }

    private fun getClipboardTextDirect(): String? {
        return try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            val text = clip?.getItemAt(0)?.coerceToText(this)?.toString()
            Log.i("CastPigeonRoot", "Root 直接读取剪贴板结果: $text")
            text
        } catch (e: Exception) {
            Log.e("CastPigeonRoot", "Root 直接读取剪贴板失败", e)
            null
        }
    }

    private fun setClipboardTextDirect(text: String): Boolean {
        return try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("CastPigeon", text)
            clipboard.setPrimaryClip(clip)
            Log.i("CastPigeonRoot", "Root 直接写入剪贴板成功")
            true
        } catch (e: Exception) {
            Log.e("CastPigeonRoot", "Root 直接写入剪贴板失败", e)
            false
        }
    }

    private fun getClipboardTextByCommand(): String? {
        val commands = listOf(
            arrayOf("su", "2000", "-c", "cmd clipboard get"),
            arrayOf("su", "shell", "-c", "cmd clipboard get"),
            arrayOf("cmd", "clipboard", "get")
        )

        for (command in commands) {
            val result = runCommand(command)
            if (result.exitCode == 0 && result.stdout.isNotBlank()) {
                Log.i("CastPigeonRoot", "Root 命令读取剪贴板成功: ${command.joinToString(" ")}")
                return result.stdout
            }
            Log.w("CastPigeonRoot", "Root 命令读取剪贴板失败: ${command.joinToString(" ")}, exit=${result.exitCode}, stderr=${result.stderr}")
        }
        return null
    }

    private fun setClipboardTextByCommand(text: String): Boolean {
        val escaped = text.replace("'", "'\\''")
        val commands = listOf(
            arrayOf("su", "2000", "-c", "cmd clipboard set '$escaped'"),
            arrayOf("su", "shell", "-c", "cmd clipboard set '$escaped'"),
            arrayOf("cmd", "clipboard", "set", text)
        )

        for (command in commands) {
            val result = runCommand(command)
            if (result.exitCode == 0) {
                Log.i("CastPigeonRoot", "Root 命令写入剪贴板成功: ${command.take(4).joinToString(" ")}")
                return true
            }
            Log.w("CastPigeonRoot", "Root 命令写入剪贴板失败: ${command.take(4).joinToString(" ")}, exit=${result.exitCode}, stderr=${result.stderr}")
        }
        return false
    }

    private fun runCommand(command: Array<String>): CommandResult {
        return try {
            val process = Runtime.getRuntime().exec(command)
            val stdout = process.inputStream.bufferedReader().readText().trim()
            val stderr = process.errorStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            CommandResult(exitCode, stdout, stderr)
        } catch (e: Exception) {
            Log.e("CastPigeonRoot", "Root 命令执行异常: ${command.joinToString(" ")}", e)
            CommandResult(-1, "", e.message.orEmpty())
        }
    }

    private data class CommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    )

    private companion object {
        private const val CLIPBOARD_POLL_INTERVAL_MS = 1_000L
    }
}
