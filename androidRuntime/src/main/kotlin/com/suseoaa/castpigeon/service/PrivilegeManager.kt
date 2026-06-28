package com.suseoaa.castpigeon.service

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.IBinder
import android.util.Log
import com.suseoaa.castpigeon.IRootClipboard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

enum class PrivilegeMode {
    DEFAULT, // 默认模式，无后台提权
    SHIZUKU  // Shizuku 模式
}

enum class ActivePrivilegeBackend {
    NONE,
    SHIZUKU
}

object PrivilegeManager {
    private const val SHIZUKU_USER_SERVICE_VERSION = 3

    val privilegeMode = MutableStateFlow(PrivilegeMode.DEFAULT)
    val isPrivileged = MutableStateFlow(false)
    val bindStatus = MutableStateFlow<BindStatus>(BindStatus.Idle)
    val activeBackend = MutableStateFlow(ActivePrivilegeBackend.NONE)

    // 共享的特权剪贴板读写 Binder
    var privilegedClipboard: IRootClipboard? = null

    enum class BindStatus { Idle, Binding, Connected, Failed }

    private var prefs: SharedPreferences? = null
    private var applicationContext: Context? = null
    private var bindingTarget: PrivilegeMode = PrivilegeMode.DEFAULT

    // Shizuku 用户服务连接器
    private val shizukuConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i("CastPigeonRoot", "ShizukuClipboardService 连接成功")
            synchronized(this@PrivilegeManager) {
                if (bindingTarget != PrivilegeMode.SHIZUKU || privilegeMode.value != PrivilegeMode.SHIZUKU) {
                    Log.w("CastPigeonRoot", "忽略过期的 Shizuku 连接，当前目标模式=$bindingTarget, 已选模式=${privilegeMode.value}")
                    return
                }
                privilegedClipboard = IRootClipboard.Stub.asInterface(service)
                activeBackend.value = ActivePrivilegeBackend.SHIZUKU
                isPrivileged.value = true
                bindStatus.value = BindStatus.Connected
            }
            validateClipboardBackendAsync("Shizuku", ActivePrivilegeBackend.SHIZUKU)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.i("CastPigeonRoot", "ShizukuClipboardService 连接断开")
            synchronized(this@PrivilegeManager) {
                if (activeBackend.value != ActivePrivilegeBackend.SHIZUKU &&
                    bindingTarget != PrivilegeMode.SHIZUKU &&
                    privilegeMode.value != PrivilegeMode.SHIZUKU
                ) {
                    Log.i("CastPigeonRoot", "忽略过期的 Shizuku 断开回调")
                    return
                }
                clearRuntimeStateLocked(BindStatus.Idle)
            }
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
                synchronized(this@PrivilegeManager) {
                    clearRuntimeStateLocked(BindStatus.Failed)
                }
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

    private fun clearRuntimeStateLocked(status: BindStatus) {
        privilegedClipboard = null
        isPrivileged.value = false
        activeBackend.value = ActivePrivilegeBackend.NONE
        bindStatus.value = status
        if (status != BindStatus.Binding) {
            bindingTarget = PrivilegeMode.DEFAULT
        }
    }

    private fun persistSelectedMode(mode: PrivilegeMode) {
        prefs?.edit()?.putString("privilege_mode", mode.name)?.apply()
    }

    private fun teardownConnectionsLocked(clearSelectedMode: Boolean) {
        applicationContext?.let { ctx ->
            try {
                val serviceArgs = getShizukuArgs(ctx)
                Shizuku.unbindUserService(serviceArgs, shizukuConnection, true)
            } catch (_: Exception) { }
        }

        privilegedClipboard = null
        isPrivileged.value = false
        activeBackend.value = ActivePrivilegeBackend.NONE
        bindStatus.value = BindStatus.Idle
        bindingTarget = PrivilegeMode.DEFAULT

        if (clearSelectedMode) {
            privilegeMode.value = PrivilegeMode.DEFAULT
            persistSelectedMode(PrivilegeMode.DEFAULT)
        }
    }

    private fun prepareBinding(target: PrivilegeMode): Boolean {
        synchronized(this) {
            val targetBackend = when (target) {
                PrivilegeMode.SHIZUKU -> ActivePrivilegeBackend.SHIZUKU
                PrivilegeMode.DEFAULT -> ActivePrivilegeBackend.NONE
            }

            if (bindStatus.value == BindStatus.Connected &&
                activeBackend.value == targetBackend &&
                privilegedClipboard != null &&
                privilegeMode.value == target
            ) {
                Log.i("CastPigeonRoot", "$target 已处于生效状态，跳过重复绑定")
                return false
            }

            if (bindStatus.value == BindStatus.Binding && bindingTarget == target) {
                Log.i("CastPigeonRoot", "$target 正在绑定中，跳过重复请求")
                return false
            }

            teardownConnectionsLocked(clearSelectedMode = false)
            privilegeMode.value = target
            persistSelectedMode(target)
            bindingTarget = target
            bindStatus.value = BindStatus.Binding
            return true
        }
    }

    private fun validateClipboardBackendAsync(label: String, backend: ActivePrivilegeBackend) {
        CoroutineScope(Dispatchers.IO).launch {
            val clipboard = synchronized(this@PrivilegeManager) {
                if (activeBackend.value != backend) return@launch
                privilegedClipboard
            } ?: return@launch

            try {
                clipboard.getClipboardText()
                Log.i("CastPigeonRoot", "$label 后端连通性校验通过，实际生效后端=${activeBackend.value}")
            } catch (e: Exception) {
                Log.e("CastPigeonRoot", "$label 后端连通性校验失败", e)
                synchronized(this@PrivilegeManager) {
                    if (activeBackend.value == backend) {
                        clearRuntimeStateLocked(BindStatus.Failed)
                    }
                }
            }
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
        if (mode == PrivilegeMode.SHIZUKU) {
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
        synchronized(this) {
            teardownConnectionsLocked(clearSelectedMode = true)
        }
    }

    /**
     * 执行 Shizuku 提权授权，并绑定 Shizuku 剪贴板用户服务
     */
    fun executeShizukuCommand(context: Context): Boolean {
        if (!Shizuku.pingBinder()) {
            Log.w("CastPigeonRoot", "Shizuku 服务未运行")
            synchronized(this) {
                clearRuntimeStateLocked(BindStatus.Failed)
            }
            return false
        }

        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w("CastPigeonRoot", "未获得 Shizuku 权限")
            synchronized(this) {
                clearRuntimeStateLocked(BindStatus.Failed)
            }
            return false
        }

        if (!prepareBinding(PrivilegeMode.SHIZUKU)) return true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val process = shizukuNewProcess(arrayOf(
                    "appops", "set", "com.suseoaa.castpigeon", "READ_CLIPBOARD", "allow"
                ), null, null)
                val exitCode = process.waitFor()
                if (exitCode == 0) {
                    Log.i("CastPigeonRoot", "Shizuku AppOps 提权成功，开始绑定 ShizukuClipboardService")

                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        try {
                            synchronized(this@PrivilegeManager) {
                                if (bindingTarget != PrivilegeMode.SHIZUKU || privilegeMode.value != PrivilegeMode.SHIZUKU) {
                                    Log.w("CastPigeonRoot", "Shizuku 绑定前检测到目标模式已变化，取消本次绑定")
                                    return@post
                                }
                            }
                            val serviceArgs = getShizukuArgs(context)
                            Shizuku.bindUserService(serviceArgs, shizukuConnection)
                        } catch (e: Exception) {
                            Log.e("CastPigeonRoot", "绑定 Shizuku 用户服务失败", e)
                            synchronized(this@PrivilegeManager) {
                                clearRuntimeStateLocked(BindStatus.Failed)
                            }
                        }
                    }
                } else {
                    Log.e("CastPigeonRoot", "Shizuku AppOps 提权失败，退出码: $exitCode")
                    synchronized(this@PrivilegeManager) {
                        clearRuntimeStateLocked(BindStatus.Failed)
                    }
                }
            } catch (e: Exception) {
                Log.e("CastPigeonRoot", "Shizuku AppOps 提权异常", e)
                synchronized(this@PrivilegeManager) {
                    clearRuntimeStateLocked(BindStatus.Failed)
                }
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
