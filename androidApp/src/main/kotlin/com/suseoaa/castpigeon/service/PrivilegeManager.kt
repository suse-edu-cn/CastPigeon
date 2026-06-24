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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

enum class PrivilegeMode {
    DEFAULT, // 默认模式，无后台提权
    ROOT,    // Root 模式
    SHIZUKU  // Shizuku 模式
}

object PrivilegeManager {
    private const val SHIZUKU_USER_SERVICE_VERSION = 3

    val privilegeMode = MutableStateFlow(PrivilegeMode.DEFAULT)
    val isPrivileged = MutableStateFlow(false)
    val bindStatus = MutableStateFlow<BindStatus>(BindStatus.Idle)

    // 共享的特权剪贴板读写 Binder
    var privilegedClipboard: IRootClipboard? = null

    enum class BindStatus { Idle, Binding, Connected, Failed }

    private var prefs: SharedPreferences? = null
    private var applicationContext: Context? = null

    // Root 服务连接器
    private val rootConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i("CastPigeonRoot", "RootClipboardService 连接成功")
            privilegedClipboard = IRootClipboard.Stub.asInterface(service)
            isPrivileged.value = true
            bindStatus.value = BindStatus.Connected
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.i("CastPigeonRoot", "RootClipboardService 连接断开")
            privilegedClipboard = null
            isPrivileged.value = false
            bindStatus.value = BindStatus.Idle
        }
    }

    // Shizuku 用户服务连接器
    private val shizukuConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i("CastPigeonRoot", "ShizukuClipboardService 连接成功")
            privilegedClipboard = IRootClipboard.Stub.asInterface(service)
            isPrivileged.value = true
            bindStatus.value = BindStatus.Connected
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.i("CastPigeonRoot", "ShizukuClipboardService 连接断开")
            privilegedClipboard = null
            isPrivileged.value = false
            bindStatus.value = BindStatus.Idle
        }
    }

    private fun getShizukuArgs(context: Context): Shizuku.UserServiceArgs {
        return Shizuku.UserServiceArgs(
            ComponentName(context.packageName, ShizukuClipboardService::class.java.name)
        ).apply {
            processNameSuffix("user_service")
            debuggable(true)
            version(SHIZUKU_USER_SERVICE_VERSION)
        }
    }

    // Shizuku 权限申请结果监听器
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        Log.i("CastPigeonRoot", "Shizuku 权限监听器触发: requestCode=$requestCode, grantResult=$grantResult")
        if (requestCode == 1001) {
            if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.i("CastPigeonRoot", "通过监听器成功获取 Shizuku 权限")
                applicationContext?.let { executeShizukuCommand(it) }
            } else {
                Log.w("CastPigeonRoot", "Shizuku 权限被拒绝")
                bindStatus.value = BindStatus.Failed
                isPrivileged.value = false
            }
        }
    }

    // Shizuku Binder 接收监听器
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i("CastPigeonRoot", "Shizuku Binder 已接收")
        val mode = privilegeMode.value
        if (mode == PrivilegeMode.SHIZUKU) {
            applicationContext?.let { executeShizukuCommand(it) }
        }
    }

    fun init(context: Context) {
        applicationContext = context.applicationContext
        prefs = context.getSharedPreferences("castpigeon_prefs", Context.MODE_PRIVATE)

        // 注册 Shizuku 权限监听器
        try {
            Shizuku.addRequestPermissionResultListener(permissionListener)
        } catch (e: Exception) {
            Log.e("CastPigeonRoot", "注册 Shizuku 权限监听器失败", e)
        }

        // 注册 Shizuku Binder 接收监听器
        try {
            Shizuku.addBinderReceivedListener(binderReceivedListener)
        } catch (e: Exception) {
            Log.e("CastPigeonRoot", "注册 Shizuku Binder 接收监听器失败", e)
        }

        val modeStr = prefs?.getString("privilege_mode", PrivilegeMode.DEFAULT.name) ?: PrivilegeMode.DEFAULT.name
        val mode = try {
            PrivilegeMode.valueOf(modeStr)
        } catch (e: Exception) {
            PrivilegeMode.DEFAULT
        }
        privilegeMode.value = mode

        // 根据上次保存的模式自动连接
        if (mode == PrivilegeMode.ROOT) {
            Log.i("CastPigeonRoot", "上次开启了 Root 模式，尝试自动提权...")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                executeAppOpsCommand(context)
            }, 500)
        } else if (mode == PrivilegeMode.SHIZUKU) {
            Log.i("CastPigeonRoot", "上次开启了 Shizuku 模式，尝试自动提权...")
            if (Shizuku.pingBinder()) {
                executeShizukuCommand(context)
            } else {
                Log.i("CastPigeonRoot", "Shizuku Binder 暂未就绪，等待 OnBinderReceivedListener 触发")
            }
        }
    }

    fun disable() {
        Log.i("CastPigeonRoot", "禁用后台提权模式...")
        
        // 尝试解绑 Root 服务
        try {
            RootService.unbind(rootConnection)
        } catch (e: Exception) {}
        
        // 尝试解绑 Shizuku 服务
        applicationContext?.let { ctx ->
            try {
                val serviceArgs = getShizukuArgs(ctx)
                Shizuku.unbindUserService(serviceArgs, shizukuConnection, true)
            } catch (e: Exception) {}
        }

        privilegedClipboard = null
        isPrivileged.value = false
        bindStatus.value = BindStatus.Idle
        privilegeMode.value = PrivilegeMode.DEFAULT
        prefs?.edit()?.putString("privilege_mode", PrivilegeMode.DEFAULT.name)?.apply()
    }

    /**
     * 执行 Root 提权授权，并绑定 Root 守护进程
     */
    fun executeAppOpsCommand(context: Context): Boolean {
        if (bindStatus.value == BindStatus.Binding) {
            Log.w("CastPigeonRoot", "已在绑定中，跳过重复绑定")
            return true
        }

        bindStatus.value = BindStatus.Binding
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 诊断：看 su 是否可用
                val suTest = Shell.cmd("id").exec()
                if (!suTest.isSuccess) {
                    Log.w("CastPigeonRoot", "Root 不可用，su 执行失败")
                    bindStatus.value = BindStatus.Failed
                    isPrivileged.value = false
                    return@launch
                }

                Log.i("CastPigeonRoot", "Root 可用: out=${suTest.out}")

                // AppOps 是普通进程读取兜底，不作为 Root 模式可用性的唯一判断。
                val result = Shell.cmd("appops set com.suseoaa.castpigeon READ_CLIPBOARD allow").exec()
                if (result.isSuccess) {
                    Log.i("CastPigeonRoot", "Root AppOps 提权命令执行成功")
                } else {
                    Log.w("CastPigeonRoot", "Root AppOps 提权命令失败，继续绑定 RootClipboardService: ${result.err}")
                }

                privilegeMode.value = PrivilegeMode.ROOT
                prefs?.edit()?.putString("privilege_mode", PrivilegeMode.ROOT.name)?.apply()

                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try {
                        val intent = Intent(context, RootClipboardService::class.java)
                        RootService.bind(intent, rootConnection)
                    } catch (e: Exception) {
                        Log.e("CastPigeonRoot", "绑定 RootClipboardService 失败", e)
                        bindStatus.value = BindStatus.Failed
                        isPrivileged.value = false
                    }
                }
            } catch (e: Exception) {
                Log.e("CastPigeonRoot", "执行 Root 提权发生异常", e)
                bindStatus.value = BindStatus.Failed
                isPrivileged.value = false
            }
        }
        return true
    }

    /**
     * 执行 Shizuku 提权授权，并绑定 Shizuku 剪贴板用户服务
     */
    fun executeShizukuCommand(context: Context): Boolean {
        if (!Shizuku.pingBinder()) {
            Log.w("CastPigeonRoot", "Shizuku 服务未运行")
            bindStatus.value = BindStatus.Failed
            isPrivileged.value = false
            return false
        }

        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w("CastPigeonRoot", "未获得 Shizuku 权限")
            bindStatus.value = BindStatus.Failed
            isPrivileged.value = false
            return false
        }

        if (bindStatus.value == BindStatus.Binding) {
            return true
        }

        bindStatus.value = BindStatus.Binding
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val process = shizukuNewProcess(arrayOf(
                    "appops", "set", "com.suseoaa.castpigeon", "READ_CLIPBOARD", "allow"
                ), null, null)
                val exitCode = process.waitFor()
                if (exitCode == 0) {
                    Log.i("CastPigeonRoot", "Shizuku AppOps 提权成功，开始绑定 ShizukuClipboardService")
                    privilegeMode.value = PrivilegeMode.SHIZUKU
                    prefs?.edit()?.putString("privilege_mode", PrivilegeMode.SHIZUKU.name)?.apply()
                    
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        try {
                            val serviceArgs = getShizukuArgs(context)
                            Shizuku.bindUserService(serviceArgs, shizukuConnection)
                        } catch (e: Exception) {
                            Log.e("CastPigeonRoot", "绑定 Shizuku 用户服务失败", e)
                            bindStatus.value = BindStatus.Failed
                            isPrivileged.value = false
                        }
                    }
                } else {
                    Log.e("CastPigeonRoot", "Shizuku AppOps 提权失败，退出码: $exitCode")
                    bindStatus.value = BindStatus.Failed
                    isPrivileged.value = false
                }
            } catch (e: Exception) {
                Log.e("CastPigeonRoot", "Shizuku AppOps 提权异常", e)
                bindStatus.value = BindStatus.Failed
                isPrivileged.value = false
            }
        }
        return true
    }

    /**
     * 使用特权方式（Root 或 Shizuku）启动透明 Activity 绕过后台启动限制
     */
    fun launchClipboardActivityViaPrivilege(context: Context): Boolean {
        val mode = privilegeMode.value
        Log.i("CastPigeonRoot", "尝试使用特权方式启动透明 Activity, 当前模式: $mode")
        
        // 优先尝试直接用 Binder 服务拉起
        val clipboard = privilegedClipboard
        if (clipboard != null) {
            try {
                clipboard.launchClipboardActivity()
                Log.i("CastPigeonRoot", "通过特权 Binder 启动 Activity 成功")
                return true
            } catch (e: Exception) {
                Log.e("CastPigeonRoot", "通过特权 Binder 启动 Activity 异常，将回退到命令行方式", e)
            }
        }
        
        when (mode) {
            PrivilegeMode.ROOT -> {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val result = Shell.cmd("am start -n com.suseoaa.castpigeon/.ui.TransparentClipboardActivity --activity-no-animation -a ACTION_SYNC_CLIPBOARD_AUTO").exec()
                        Log.i("CastPigeonRoot", "Root 启动 Activity 结果: success=${result.isSuccess}")
                    } catch (e: Exception) {
                        Log.e("CastPigeonRoot", "Root 启动 Activity 异常", e)
                    }
                }
                return true
            }
            PrivilegeMode.SHIZUKU -> {
                if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val process = shizukuNewProcess(arrayOf(
                                "am", "start",
                                "-n", "com.suseoaa.castpigeon/.ui.TransparentClipboardActivity",
                                "--activity-no-animation",
                                "-a", "ACTION_SYNC_CLIPBOARD_AUTO"
                            ), null, null)
                            val exitCode = process.waitFor()
                            Log.i("CastPigeonRoot", "Shizuku 启动 Activity 退出码: $exitCode")
                        } catch (e: Exception) {
                            Log.e("CastPigeonRoot", "Shizuku 启动 Activity 异常", e)
                        }
                    }
                    return true
                }
                Log.w("CastPigeonRoot", "Shizuku 未就绪，无法启动 Activity")
                return false
            }
            PrivilegeMode.DEFAULT -> {
                return false
            }
        }
    }

    /**
     * 利用反射调用 Shizuku.newProcess（新版本 API 将其设为 private，此处进行兼容性反射调用）
     */
    private fun shizukuNewProcess(cmd: Array<String>, env: Array<String>?, dir: String?): java.lang.Process {
        val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        ).apply { isAccessible = true }
        return newProcessMethod.invoke(null, cmd, env, dir) as java.lang.Process
    }
}
