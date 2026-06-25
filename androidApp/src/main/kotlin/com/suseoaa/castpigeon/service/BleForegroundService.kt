package com.suseoaa.castpigeon.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.suseoaa.castpigeon.db.MessageDatabaseHelper
import com.suseoaa.castpigeon.shared.ConnectionState
import com.suseoaa.castpigeon.shared.DeviceRole
import com.suseoaa.castpigeon.shared.NotificationRepository
import com.suseoaa.castpigeon.shared.WorkMode
import com.suseoaa.castpigeon.shared.network.UdpDevice
import com.suseoaa.castpigeon.shared.network.UdpDiscovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.ClipboardManager
import android.content.ClipData
import android.content.ClipboardManager.OnPrimaryClipChangedListener
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.NetworkCapabilities
import android.widget.Toast
import com.suseoaa.castpigeon.IClipboardChangeCallback
import java.net.Inet4Address

class BleForegroundService : Service() {

    companion object {
        private const val MAX_BLE_NOTIFICATION_BYTES = 420
        private const val CHANNEL_ID = "CastPigeonBleChannel"
        private const val NOTIFICATION_ID = 1001
        var isInternalClipboardWrite = false
        
        fun start(context: Context) {
            val intent = Intent(context, BleForegroundService::class.java)
            context.startForegroundService(intent)
        }
        
        fun stop(context: Context) {
            val intent = Intent(context, BleForegroundService::class.java)
            context.stopService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private lateinit var dbHelper: MessageDatabaseHelper
    private var isObserving = false
    private var lastSyncedText: String? = null
    private var lastListenerTriggerTime = 0L
    private var registeredPrivilegedClipboard: com.suseoaa.castpigeon.IRootClipboard? = null
    private var pendingClipboardTextForMac: String? = null
    private var lastCapabilitySentAt = 0L

    private val privilegedClipboardCallback = object : IClipboardChangeCallback.Stub() {
        override fun onClipboardChanged(text: String?) {
            if (text.isNullOrEmpty() || text == lastSyncedText) return
            val backend = PrivilegeManager.activeBackend.value
            android.util.Log.i("CastPigeon", "收到特权剪贴板变化回调，来源=$backend，准备发送到 Mac")
            sendClipboardTextToMac(text, "$backend 剪贴板监听")
        }
    }

    private fun writeClipboardDirectly(text: String, source: String): Boolean {
        return try {
            isInternalClipboardWrite = true
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("CastPigeon", text))
            android.util.Log.i("CastPigeon", "$source 直接写入系统剪贴板成功")
            true
        } catch (e: Exception) {
            isInternalClipboardWrite = false
            android.util.Log.e("CastPigeon", "$source 直接写入系统剪贴板失败", e)
            false
        }
    }

    private fun writeClipboardViaPrivilege(text: String, source: String): Boolean {
        val clipboard = PrivilegeManager.privilegedClipboard ?: return false
        if (!PrivilegeManager.isPrivileged.value) return false

        return try {
            isInternalClipboardWrite = true
            val success = clipboard.setClipboardText(text)
            if (success) {
                android.util.Log.i("CastPigeon", "$source 使用特权服务写入剪贴板成功")
            } else {
                isInternalClipboardWrite = false
                android.util.Log.w("CastPigeon", "$source 特权服务返回写入失败")
            }
            success
        } catch (e: Exception) {
            isInternalClipboardWrite = false
            android.util.Log.e("CastPigeon", "$source 特权服务写入剪贴板异常", e)
            false
        }
    }

    private fun sendClipboardTextToMac(text: String, source: String) {
        if (text == lastSyncedText) return

        val state = AppConnectionManager.stateMachine.state.value
        val role = AppConnectionManager.stateMachine.role.value
        val workMode = AppConnectionManager.stateMachine.workMode.value
        if (role != DeviceRole.Sender || workMode != WorkMode.Working || state != ConnectionState.Transferring) {
            pendingClipboardTextForMac = text
            lastSyncedText = text
            android.util.Log.i("CastPigeon", "$source 已缓存，等待 BLE 进入 Transferring 后发送: state=$state, role=$role, workMode=$workMode")
            return
        }

        pendingClipboardTextForMac = null
        lastSyncedText = text
        val payload = "CLIP|$text"
        try {
            AppConnectionManager.blePeripheral.sendNotificationData(payload.encodeToByteArray())
            android.util.Log.i("CastPigeon", "$source 发送到 Mac 成功")
        } catch (e: Exception) {
            android.util.Log.e("CastPigeon", "$source 发送到 Mac 失败", e)
        }
    }

    private fun registerPrivilegedClipboardCallbackIfNeeded() {
        val clipboard = PrivilegeManager.privilegedClipboard ?: return
        if (!PrivilegeManager.isPrivileged.value) return
        if (registeredPrivilegedClipboard === clipboard) return

        try {
            registeredPrivilegedClipboard?.unregisterClipboardCallback(privilegedClipboardCallback)
        } catch (e: Exception) {
            android.util.Log.w("CastPigeon", "注销旧特权剪贴板回调失败", e)
        }

        try {
            clipboard.registerClipboardCallback(privilegedClipboardCallback)
            registeredPrivilegedClipboard = clipboard
            android.util.Log.i("CastPigeon", "已注册特权剪贴板变化回调，实际后端=${PrivilegeManager.activeBackend.value}")
        } catch (e: Exception) {
            registeredPrivilegedClipboard = null
            android.util.Log.e("CastPigeon", "注册 Shizuku 剪贴板变化回调失败", e)
        }
    }

    private fun flushPendingClipboardToMacIfReady(trigger: String) {
        val text = pendingClipboardTextForMac ?: return
        val state = AppConnectionManager.stateMachine.state.value
        val role = AppConnectionManager.stateMachine.role.value
        val workMode = AppConnectionManager.stateMachine.workMode.value
        if (role != DeviceRole.Sender || workMode != WorkMode.Working || state != ConnectionState.Transferring) {
            return
        }

        pendingClipboardTextForMac = null
        val payload = "CLIP|$text"
        try {
            AppConnectionManager.blePeripheral.sendNotificationData(payload.encodeToByteArray())
            android.util.Log.i("CastPigeon", "$trigger 补发缓存剪贴板到 Mac 成功")
        } catch (e: Exception) {
            pendingClipboardTextForMac = text
            android.util.Log.e("CastPigeon", "$trigger 补发缓存剪贴板到 Mac 失败", e)
        }
    }

    private val clipboardListener = OnPrimaryClipChangedListener {
        val now = System.currentTimeMillis()
        // 防抖：600ms 内不重复触发
        if (now - lastListenerTriggerTime < 600) return@OnPrimaryClipChangedListener
        lastListenerTriggerTime = now

        if (isInternalClipboardWrite) {
            isInternalClipboardWrite = false
            android.util.Log.i("CastPigeon", "OnPrimaryClipChangedListener: 忽略内置剪贴板写入触发的事件")
            return@OnPrimaryClipChangedListener
        }

        android.util.Log.i("CastPigeon", "触发 OnPrimaryClipChangedListener! isPrivileged=${PrivilegeManager.isPrivileged.value}")

        // Android 13+ 即使有 AppOps，有时第一次读取会慢一拍，所以稍微延时或者直接读
        tryDirectClipboardRead()
    }

    private fun tryDirectClipboardRead() {
        var text: String? = null
        
        // 1. 如果有特权服务，先尝试静默读取
        if (PrivilegeManager.isPrivileged.value && PrivilegeManager.privilegedClipboard != null) {
            try {
                text = PrivilegeManager.privilegedClipboard?.getClipboardText()
                android.util.Log.i("CastPigeon", "通过特权服务读取剪贴板成功: $text, backend=${PrivilegeManager.activeBackend.value}")
            } catch (e: Exception) {
                android.util.Log.e("CastPigeon", "通过特权服务读取剪贴板失败", e)
            }
        }
        
        // 2. 如果没有或者特权读取为空，再尝试常规读取
        if (text.isNullOrEmpty()) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            text = try {
                clipboard.primaryClip?.getItemAt(0)?.text?.toString()
            } catch (e: Exception) { null }
        }

        if (!text.isNullOrEmpty() && text != lastSyncedText) {
            sendClipboardTextToMac(text, "普通剪贴板监听")
        } else {
            if (text.isNullOrEmpty() && PrivilegeManager.isPrivileged.value) {
                android.util.Log.w("CastPigeon", "普通后台读取为空，等待 Shizuku 剪贴板监听回调")
            } else if (text.isNullOrEmpty()) {
                android.util.Log.w("CastPigeon", "读取为空（可能被系统拦截）。请在设置中开启提权模式。")
            }
        }
    }

    private val clipboardSyncReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            android.util.Log.i("CastPigeon", "收到广播: ${intent?.action}")
            if (intent?.action == "com.suseoaa.castpigeon.ACTION_SYNC_CLIPBOARD") {
                android.util.Log.i("CastPigeon", "准备读取剪贴板并发送")
                
                var text: String? = null
                
                // 1. 优先尝试特权静默读取
                if (PrivilegeManager.isPrivileged.value && PrivilegeManager.privilegedClipboard != null) {
                    try {
                        text = PrivilegeManager.privilegedClipboard?.getClipboardText()
                        android.util.Log.i("CastPigeon", "ACTION_SYNC_CLIPBOARD 特权读取成功: $text")
                    } catch (e: Exception) {
                        android.util.Log.e("CastPigeon", "ACTION_SYNC_CLIPBOARD 特权读取失败", e)
                    }
                }
                
                // 2. 尝试常规读取
                if (text.isNullOrEmpty()) {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    text = try {
                        clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                    } catch (e: Exception) { null }
                }

                if (!text.isNullOrEmpty()) {
                    val payload = "CLIP|$text"
                    try {
                        AppConnectionManager.blePeripheral.sendNotificationData(payload.encodeToByteArray())
                        Toast.makeText(this@BleForegroundService, "已推送到 Mac", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                } else {
                    // 3. 降级：拉起透明 Activity
                    if (PrivilegeManager.isPrivileged.value) {
                        PrivilegeManager.launchClipboardActivityViaPrivilege(this@BleForegroundService)
                    } else {
                        val fallbackIntent = Intent(this@BleForegroundService, com.suseoaa.castpigeon.ui.TransparentClipboardActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        }
                        startActivity(fallbackIntent)
                    }
                }
            } else if (intent?.action == "com.suseoaa.castpigeon.ACTION_COPY_CLIPBOARD") {
                val text = intent.getStringExtra("EXTRA_TEXT") ?: return
                
                val success = writeClipboardDirectly(text, "ACTION_COPY_CLIPBOARD")
                    || writeClipboardViaPrivilege(text, "ACTION_COPY_CLIPBOARD")

                if (success) {
                    Toast.makeText(this@BleForegroundService, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                }
                
                if (!success) {
                    android.util.Log.e("CastPigeon", "ACTION_COPY_CLIPBOARD 直接写入剪贴板失败，未拉起空白页面")
                    Toast.makeText(this@BleForegroundService, "复制失败，请检查 Shizuku 权限", Toast.LENGTH_SHORT).show()
                }
                
                val id = intent.getIntExtra("EXTRA_NOTIF_ID", 1002)
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(id)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        dbHelper = MessageDatabaseHelper(this)
        createNotificationChannel()
        
        val filter = IntentFilter().apply {
            addAction("com.suseoaa.castpigeon.ACTION_SYNC_CLIPBOARD")
            addAction("com.suseoaa.castpigeon.ACTION_COPY_CLIPBOARD")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(clipboardSyncReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(clipboardSyncReceiver, filter)
        }
        if (Build.VERSION.SDK_INT >= 34) { // Build.VERSION_CODES.UPSIDE_DOWN_CAKE
            startForeground(NOTIFICATION_ID, buildNotification(dbHelper.getTodayMessageCount()), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(dbHelper.getTodayMessageCount()))
        }
        startObservingNotifications()
        
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.addPrimaryClipChangedListener(clipboardListener)
            android.util.Log.i("CastPigeon", "已注册后台剪贴板监听器")
        } catch (e: Exception) {
            android.util.Log.e("CastPigeon", "注册剪贴板监听器失败", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 更新一次通知
        updateNotification()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isObserving = false
        try {
            unregisterReceiver(clipboardSyncReceiver)
        } catch (e: Exception) { }
        try {
            registeredPrivilegedClipboard?.unregisterClipboardCallback(privilegedClipboardCallback)
            registeredPrivilegedClipboard = null
        } catch (e: Exception) { }
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.removePrimaryClipChangedListener(clipboardListener)
        } catch (e: Exception) { }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startObservingNotifications() {
        if (isObserving) return
        isObserving = true
        
        serviceScope.launch {
            PrivilegeManager.bindStatus.collect { status ->
                if (status == PrivilegeManager.BindStatus.Connected) {
                    registerPrivilegedClipboardCallbackIfNeeded()
                } else {
                    try {
                        registeredPrivilegedClipboard?.unregisterClipboardCallback(privilegedClipboardCallback)
                    } catch (e: Exception) {
                        android.util.Log.w("CastPigeon", "注销失效的特权剪贴板回调失败", e)
                    }
                    registeredPrivilegedClipboard = null
                }
            }
        }

        serviceScope.launch {
            NotificationRepository.messageFlow.collect { message ->
                val stateMachine = AppConnectionManager.stateMachine
                val role = stateMachine.role.value
                val workMode = stateMachine.workMode.value
                val connectionState = stateMachine.state.value
                
                // 写入数据库
                dbHelper.insertMessage(message)
                updateNotification()

                // 如果符合发送条件，则通过蓝牙发送
                if (role == DeviceRole.Sender && workMode == WorkMode.Working && connectionState == ConnectionState.Transferring) {
                    try {
                        val jsonStr = encodeNotificationForBle(message) ?: run {
                            android.util.Log.w("CastPigeon", "通知过长且无法压缩到 BLE 安全范围，已跳过: ${message.title}")
                            return@collect
                        }
                        android.util.Log.i("CastPigeon", "发送 BLE 通知: bytes=${jsonStr.encodeToByteArray().size}, title=${message.title}")
                        AppConnectionManager.crypto.computeSharedSecret(AppConnectionManager.crypto.getPublicKeyBytes())
                        AppConnectionManager.blePeripheral.sendNotificationData(jsonStr.encodeToByteArray())
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

        // 监听来自 Mac 的直接消息 (例如剪贴板)
        val handleMessage: (String) -> Unit = { msg ->
            if (msg.startsWith("CLIP|")) {
                val text = msg.substring(5)
                lastSyncedText = text // 防止回环触发
                
                val handled = writeClipboardDirectly(text, "Mac 剪贴板同步")
                    || writeClipboardViaPrivilege(text, "Mac 剪贴板同步")

                if (handled) {
                    showClipboardNotification(text)
                }
                
                if (!handled) {
                    android.util.Log.e("CastPigeon", "收到 Mac 剪贴板但直接写入失败，未拉起空白页面")
                    showClipboardNotification(text)
                }
            } else if (msg.startsWith("CAP|")) {
                handlePeerCapability(msg)
                sendLocalCapabilityOverBle("收到对端能力信息后回送")
            } else {
                AppConnectionManager.lastReceivedMessage.value = msg
            }
        }
        AppConnectionManager.bleCentral.onMessageReceived = handleMessage
        AppConnectionManager.blePeripheral.onMessageReceived = handleMessage

        // 监听连接状态以实现断线自动重连
        serviceScope.launch {
            AppConnectionManager.stateMachine.state.collect { state ->
                val workMode = AppConnectionManager.stateMachine.workMode.value
                val role = AppConnectionManager.stateMachine.role.value

                if (state == ConnectionState.Transferring) {
                    sendLocalCapabilityOverBle("BLE 连接就绪")
                    flushPendingClipboardToMacIfReady("BLE 连接就绪")
                }
                
                // 如果当前处于工作模式但由于断线回退到了 Idle，Android端应重新开启广播
                if (workMode == WorkMode.Working && state == ConnectionState.Idle && role == DeviceRole.Sender) {
                    try {
                        val deviceHash = getDeviceHash()
                        AppConnectionManager.blePeripheral.startAdvertising(workMode, deviceHash) { newState, name ->
                            AppConnectionManager.stateMachine.transitionTo(newState, name)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    @android.annotation.SuppressLint("HardwareIds")
    private fun getDeviceHash(): ByteArray {
        val androidId = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "unknown"
        val bytes = java.security.MessageDigest.getInstance("SHA-256").digest(androidId.toByteArray())
        return bytes.copyOfRange(0, 4)
    }

    private fun showClipboardNotification(text: String) {
        val copyIntent = Intent("com.suseoaa.castpigeon.ACTION_COPY_CLIPBOARD").apply {
            setPackage(packageName)
            putExtra("EXTRA_TEXT", text)
            putExtra("EXTRA_NOTIF_ID", 1002)
        }
        val copyPendingIntent = android.app.PendingIntent.getBroadcast(
            this, 1, copyIntent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val preview = if (text.length > 30) text.take(30) + "..." else text
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.suseoaa.castpigeon.R.mipmap.ic_launcher_round)
            .setContentTitle("收到来自 Mac 的剪贴板")
            .setContentText(preview)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .addAction(0, "📋 点击复制", copyPendingIntent)
            .build()
            
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(1002, notif)
    }

    private fun encodeNotificationForBle(message: com.suseoaa.castpigeon.shared.NotificationMessage): String? {
        fun encode(candidate: com.suseoaa.castpigeon.shared.NotificationMessage): String {
            return Json.encodeToString(candidate)
        }

        fun fits(json: String): Boolean = json.encodeToByteArray().size <= MAX_BLE_NOTIFICATION_BYTES

        var candidate = message.copy(iconBase64 = null)
        var json = encode(candidate)
        if (fits(json)) return json

        var content = candidate.content
        while (content.isNotEmpty()) {
            content = content.dropLast(1)
            candidate = candidate.copy(content = content)
            json = encode(candidate)
            if (fits(json)) return json
        }

        var title = candidate.title
        while (title.isNotEmpty()) {
            title = title.dropLast(1)
            candidate = candidate.copy(title = title)
            json = encode(candidate)
            if (fits(json)) return json
        }

        return if (fits(json)) json else null
    }

    private fun sendLocalCapabilityOverBle(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastCapabilitySentAt < 2_000) return
        lastCapabilitySentAt = now

        val ip = getLocalIpv4Address()
        val filePort = LanFileTransferManager.serverPort.value
        val deviceHash = getDeviceHash().joinToString("") { "%02X".format(it) }
        val deviceName = android.provider.Settings.Global.getString(contentResolver, android.provider.Settings.Global.DEVICE_NAME)
            ?: Build.MODEL
            ?: "Android"
        val payload = listOf("CAP", deviceName, deviceHash, ip.orEmpty(), filePort.toString(), "Android")
            .joinToString("|")

        try {
            if (AppConnectionManager.stateMachine.role.value == DeviceRole.Sender) {
                AppConnectionManager.blePeripheral.sendNotificationData(payload.encodeToByteArray())
            } else {
                AppConnectionManager.bleCentral.sendMessage(payload)
            }
            android.util.Log.i("CastPigeon", "$reason 已发送能力信息: $payload")
        } catch (e: Exception) {
            android.util.Log.e("CastPigeon", "$reason 发送能力信息失败", e)
        }
    }

    private fun handlePeerCapability(payload: String) {
        val parts = payload.split("|")
        if (parts.size < 6) return
        val deviceName = parts[1]
        val hash = parts[2]
        val ip = parts[3].takeIf { it.isNotBlank() } ?: return
        val port = parts[4].toIntOrNull()
        val deviceType = parts[5].ifBlank { "Unknown" }
        UdpDiscovery.upsertDiscoveredDevice(
            UdpDevice(
                deviceName = deviceName,
                role = "Peer",
                hash = hash,
                ipAddress = ip,
                filePort = port,
                deviceType = deviceType
            )
        )
        android.util.Log.i("CastPigeon", "收到 BLE 能力信息并更新在线设备: $payload")
    }

    private fun getLocalIpv4Address(): String? {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networks = connectivityManager.allNetworks
        val sortedNetworks = networks.sortedByDescending { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) 1 else 0
        }
        for (network in sortedNetworks) {
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: continue
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            val properties = connectivityManager.getLinkProperties(network) ?: continue
            val interfaceName = properties.interfaceName.orEmpty()
            if (listOf("tun", "utun", "rmnet", "lo").any { interfaceName.startsWith(it) }) continue
            for (address: LinkAddress in properties.linkAddresses) {
                val inetAddress = address.address
                if (inetAddress is Inet4Address && !inetAddress.isLoopbackAddress) {
                    return inetAddress.hostAddress
                }
            }
        }
        return null
    }

    private fun updateNotification() {
        val count = dbHelper.getTodayMessageCount()
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification(count))
    }

    private fun buildNotification(count: Int): Notification {
        val syncIntent = Intent("com.suseoaa.castpigeon.ACTION_SYNC_CLIPBOARD").apply {
            setPackage(packageName)
        }
        val syncPendingIntent = android.app.PendingIntent.getBroadcast(
            this, 0, syncIntent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.suseoaa.castpigeon.R.mipmap.ic_launcher_round)
            .setContentTitle("CastPigeon 正在运行")
            .setContentText("今日已收到 $count 条消息")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(0, "✈️ 推送剪贴板至 Mac", syncPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "CastPigeon 后台保活服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "维持蓝牙连接并在后台转发消息"
        }
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }
}
