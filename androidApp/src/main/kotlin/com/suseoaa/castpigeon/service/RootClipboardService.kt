package com.suseoaa.castpigeon.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.suseoaa.castpigeon.IRootClipboard
import com.topjohnwu.superuser.ipc.RootService

/**
 * Root 权限守护进程服务（UID=0）。
 *
 * 核心价值：由于 Android 13+ 的 ClipboardService 只允许 UID=1000（system server）
 * 绕过焦点限制，UID=0（root）也无法直接读取剪贴板。
 *
 * 因此本服务的核心作用是：当主进程 clipboardListener 检测到剪贴板变化并通过 AIDL
 * 调用 launchClipboardActivity() 时，以 UID=0 身份执行 "am start"，
 * 绕过 Android 11+ 对前台服务后台启动 Activity 的限制，
 * 让 TransparentClipboardActivity 获得焦点、读取剪贴板、通过 BLE 发送到 Mac。
 */
class RootClipboardService : RootService() {

    override fun onCreate() {
        super.onCreate()
        Log.i("CastPigeonRoot", "RootClipboardService onCreate, myUID=${android.os.Process.myUid()}")
    }

    override fun onDestroy() {
        super.onDestroy()
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

        /**
         * 尝试读取剪贴板（诊断用途）。
         * 注意：Android 13+ 中即使 UID=0 也无法绕过 ClipboardService 焦点检查，通常返回 null。
         */
        override fun getClipboardText(): String? {
            Log.i("CastPigeonRoot", "getClipboardText (diagnostic), myUID=${android.os.Process.myUid()}")
            return try {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = clipboard.primaryClip
                val text = clip?.getItemAt(0)?.text?.toString()
                Log.i("CastPigeonRoot", "getClipboardText result: $text (clip=$clip)")
                text
            } catch (e: Exception) {
                Log.e("CastPigeonRoot", "getClipboardText exception", e)
                null
            }
        }

        /**
         * 写入剪贴板（Mac -> Android 方向）。
         * UID=0 的写入操作在许多 Android 版本中可以成功（写比读限制少）。
         */
        override fun setClipboardText(text: String?) {
            if (text == null) return
            Log.i("CastPigeonRoot", "setClipboardText: $text, myUID=${android.os.Process.myUid()}")
            try {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("CastPigeon", text)
                clipboard.setPrimaryClip(clip)
                Log.i("CastPigeonRoot", "setClipboardText success")
            } catch (e: Exception) {
                Log.e("CastPigeonRoot", "setClipboardText failed", e)
            }
        }
    }
}
