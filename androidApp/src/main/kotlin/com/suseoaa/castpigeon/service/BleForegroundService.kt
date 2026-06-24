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
import android.widget.Toast

class BleForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "CastPigeonBleChannel"
        private const val NOTIFICATION_ID = 1001
        
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

    private val clipboardSyncReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.suseoaa.castpigeon.ACTION_SYNC_CLIPBOARD") {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                if (!text.isNullOrEmpty()) {
                    val payload = "CLIP|$text"
                    try {
                        AppConnectionManager.blePeripheral.sendNotificationData(payload.encodeToByteArray())
                        Toast.makeText(this@BleForegroundService, "已推送到 Mac", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                } else {
                    Toast.makeText(this@BleForegroundService, "剪贴板为空或无法访问", Toast.LENGTH_SHORT).show()
                }
            } else if (intent?.action == "com.suseoaa.castpigeon.ACTION_COPY_CLIPBOARD") {
                val text = intent.getStringExtra("EXTRA_TEXT") ?: return
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("CastPigeon", text)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this@BleForegroundService, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
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
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startObservingNotifications() {
        if (isObserving) return
        isObserving = true
        
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
                        val jsonStr = Json.encodeToString(message)
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
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                try {
                    val clip = ClipData.newPlainText("CastPigeon", text)
                    clipboard.setPrimaryClip(clip)
                    showClipboardNotification(text)
                } catch (e: Exception) {
                    showClipboardNotification(text)
                }
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

    private fun updateNotification() {
        val count = dbHelper.getTodayMessageCount()
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification(count))
    }

    private fun buildNotification(count: Int): Notification {
        val syncIntent = Intent(this, com.suseoaa.castpigeon.ui.TransparentClipboardActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val syncPendingIntent = android.app.PendingIntent.getActivity(
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
