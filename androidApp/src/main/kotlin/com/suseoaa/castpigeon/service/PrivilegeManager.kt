package com.suseoaa.castpigeon.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.IBinder
import android.util.Log
import com.suseoaa.castpigeon.IRootClipboard
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import kotlinx.coroutines.flow.MutableStateFlow

object PrivilegeManager {
    val isPrivileged = MutableStateFlow(false)
    val bindStatus = MutableStateFlow<BindStatus>(BindStatus.Idle)

    enum class BindStatus { Idle, Binding, Connected, Failed }

    private var prefs: SharedPreferences? = null
    private var applicationContext: Context? = null
    private var bindAttempts = 0
    private const val MAX_BIND_ATTEMPTS = 1 // 每次用户手动触发只尝试一次，不自动重试

    var rootClipboard: IRootClipboard? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i("CastPigeonRoot", "RootService connected ✅")
            rootClipboard = IRootClipboard.Stub.asInterface(service)
            isPrivileged.value = true
            bindStatus.value = BindStatus.Connected
            prefs?.edit()?.putBoolean("isPrivileged", true)?.apply()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.i("CastPigeonRoot", "RootService disconnected")
            rootClipboard = null
            isPrivileged.value = false
            bindStatus.value = BindStatus.Idle
        }
    }

    fun init(context: Context) {
        applicationContext = context.applicationContext
        prefs = context.getSharedPreferences("castpigeon_prefs", Context.MODE_PRIVATE)
        val wasPrivileged = prefs?.getBoolean("isPrivileged", false) ?: false
        if (wasPrivileged) {
            Log.i("CastPigeonRoot", "上次开启了 Root 模式，尝试自动重连...")
            // 异步尝试一次自动重连，不阻塞启动
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                executeAppOpsCommand(context)
            }, 500)
        }
    }

    fun disable() {
        Log.i("CastPigeonRoot", "Disabling RootService...")
        try {
            RootService.unbind(connection)
        } catch (e: Exception) {}
        rootClipboard = null
        isPrivileged.value = false
        bindStatus.value = BindStatus.Idle
        prefs?.edit()?.putBoolean("isPrivileged", false)?.apply()
    }

    /**
     * 执行 Root 绑定。
     * 返回值：
     *   true  = 已开始绑定（异步，不代表成功）
     *   false = Root 不可用（su 命令失败）
     */
    fun executeAppOpsCommand(context: Context): Boolean {
        if (bindStatus.value == BindStatus.Binding || bindStatus.value == BindStatus.Connected) {
            Log.w("CastPigeonRoot", "已在绑定中或已连接，跳过重复绑定")
            return true
        }

        return try {
            bindStatus.value = BindStatus.Binding
            
            // 诊断 1：看 su 1000 是否可用
            val su1000Test = Shell.cmd("su 1000 -c id").exec()
            Log.i("CastPigeonRoot", "Diagnostic su 1000 id: success=${su1000Test.isSuccess}, out=${su1000Test.out}, err=${su1000Test.err}")

            // 检查 su 是否可用并执行 appops 提权
            val result = Shell.cmd("appops set com.suseoaa.castpigeon READ_CLIPBOARD allow").exec()
            
            // 诊断 2：验证 appops 是否真的生效
            val getResult = Shell.cmd("appops get com.suseoaa.castpigeon READ_CLIPBOARD").exec()
            Log.i("CastPigeonRoot", "Diagnostic appops get: out=${getResult.out}")

            if (result.isSuccess) {
                Log.i("CastPigeonRoot", "AppOps 提权命令执行成功！应用尝试获取后台读取剪贴板权限")
                bindStatus.value = BindStatus.Connected
                isPrivileged.value = true
                prefs?.edit()?.putBoolean("isPrivileged", true)?.apply()
                true
            } else {
                Log.w("CastPigeonRoot", "su 命令执行失败或 appops 失败，Root 不可用。错误信息: ${result.err}")
                bindStatus.value = BindStatus.Failed
                prefs?.edit()?.putBoolean("isPrivileged", false)?.apply()
                false
            }
        } catch (e: Exception) {
            Log.e("CastPigeonRoot", "执行 AppOps 提权失败", e)
            bindStatus.value = BindStatus.Failed
            false
        }
    }
}