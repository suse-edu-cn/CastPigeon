package com.suseoaa.castpigeon

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import com.suseoaa.castpigeon.db.MessageDatabaseHelper
import com.suseoaa.castpigeon.service.ActivePrivilegeBackend
import com.suseoaa.castpigeon.service.AppConnectionManager
import com.suseoaa.castpigeon.service.BleForegroundService
import com.suseoaa.castpigeon.service.LanFileTransferManager
import com.suseoaa.castpigeon.service.PrivilegeManager
import com.suseoaa.castpigeon.service.PrivilegeMode
import com.suseoaa.castpigeon.shared.BleCentral
import com.suseoaa.castpigeon.shared.BleContextHolder
import com.suseoaa.castpigeon.shared.BlePeripheral
import com.suseoaa.castpigeon.shared.ConnectionState
import com.suseoaa.castpigeon.shared.ConnectionStateMachine
import com.suseoaa.castpigeon.shared.DeviceRole
import com.suseoaa.castpigeon.shared.NotificationMessage
import com.suseoaa.castpigeon.shared.WorkMode
import com.suseoaa.castpigeon.shared.network.PinDisplayInfo
import com.suseoaa.castpigeon.shared.network.UdpDevice
import com.suseoaa.castpigeon.shared.network.UdpDiscovery
import com.suseoaa.castpigeon.update.AppUpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.Date

class FlutterCastPigeonBridge(
    private val context: Context,
    private val filePicker: ((UdpDevice) -> Unit)? = null,
    private val updateSink: ((String) -> Unit)? = null
) {
    private val applicationContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    private val stateMachine: ConnectionStateMachine = AppConnectionManager.stateMachine
    private val blePeripheral: BlePeripheral = AppConnectionManager.blePeripheral
    private val bleCentral: BleCentral = AppConnectionManager.bleCentral
    private val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val updateState = MutableStateFlow(UpdateUiState(currentVersion = AppUpdateManager.currentVersionName(applicationContext)))
    private val releaseDownloads = mutableMapOf<String, ReleaseDownloadState>()

    private var pinDisplayInfo: PinDisplayInfo? = null
    private var pinInputDevice: UdpDevice? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        BleContextHolder.applicationContext = applicationContext
        AppManager.init(applicationContext)
        PrivilegeManager.init(applicationContext)
        LanFileTransferManager.startServer(applicationContext)
        normalizeAndPersistBoundDevices()
        restoreRole()
        observePairingEvents()
        observeStateSources()
        autoStartWorkingIfPossible()
        scope.launch {
            delay(300)
            checkUpdate(showNoUpdateToast = false)
            refreshUpdateHistory()
        }
        emitSnapshot()
    }

    fun dispose() {
        scope.cancel()
        ioScope.cancel()
    }

    fun snapshotJson(): String {
        return json.encodeToString(buildSnapshot())
    }

    fun emitSnapshot() {
        updateSink?.invoke(snapshotJson())
    }

    fun onStartupPermissionsReady() {
        autoStartWorkingIfPossible()
        emitSnapshot()
    }

    fun setRole(roleCode: Int): Boolean {
        if (stateMachine.workMode.value != WorkMode.Idle) return false
        stateMachine.setRole(if (roleCode == 1) DeviceRole.Receiver else DeviceRole.Sender)
        prefs.edit().putString("LastRole", stateMachine.role.value.name).apply()
        emitSnapshot()
        return true
    }

    fun startPairing(): Boolean {
        return startBluetoothAction(WorkMode.Pairing)
    }

    fun startWorking(): Boolean {
        return startBluetoothAction(WorkMode.Working)
    }

    fun stop(): Boolean {
        stopBluetoothAction()
        emitSnapshot()
        return true
    }

    fun requestBinding(arguments: Map<String, Any?>): Boolean {
        val hash = arguments["hash"] as? String ?: return false
        val name = arguments["deviceName"] as? String ?: return false
        val role = arguments["role"] as? String ?: return false
        val ipAddress = arguments["ipAddress"] as? String ?: return false
        android.util.Log.i("CastPigeonFlutter", "UDP 发送绑定请求: $name / $hash / $ipAddress")
        UdpDiscovery.requestBinding(hash, name, role, ipAddress)
        emitSnapshot()
        return true
    }

    fun verifyBinding(arguments: Map<String, Any?>): Boolean {
        val targetHash = arguments["targetHash"] as? String ?: return false
        val pin = arguments["pin"] as? String ?: return false
        val targetIp = arguments["ipAddress"] as? String
        android.util.Log.i("CastPigeonFlutter", "UDP 发送配对码验证: target=$targetHash, ip=${targetIp ?: "pending"}")
        UdpDiscovery.verifyBinding(targetHash, pin, targetIp)
        emitSnapshot()
        return true
    }

    fun cancelPairingPrompt(): Boolean {
        pinDisplayInfo = null
        pinInputDevice = null
        UdpDiscovery.stop()
        stateMachine.setWorkMode(WorkMode.Idle)
        emitSnapshot()
        return true
    }

    fun approvePairingRequest(): Boolean {
        val incomingDevice = BoundDeviceStore.parse(stateMachine.pairingDeviceName.value ?: return false)
        upsertBoundDeviceEntry(
            name = incomingDevice.name,
            hash = incomingDevice.hash,
            deviceType = incomingDevice.deviceType,
            lastIp = incomingDevice.lastIp,
            filePort = incomingDevice.filePort?.toIntOrNull()
        )
        stateMachine.transitionTo(ConnectionState.Transferring, stateMachine.pairingDeviceName.value)
        emitSnapshot()
        return true
    }

    fun rejectPairingRequest(): Boolean {
        blePeripheral.disconnectCurrentDevice()
        stateMachine.transitionTo(ConnectionState.AdvertisingOrScanning)
        emitSnapshot()
        return true
    }

    fun removeBoundDevice(hash: String): Boolean {
        val targetHash = hash.uppercase()
        val updated = boundEntriesRaw().filterNot { raw ->
            BoundDeviceStore.parse(raw).hash?.equals(targetHash, ignoreCase = true) == true || raw == hash
        }.toSet()
        saveBoundDeviceEntries(updated)
        if (updated.isEmpty() && stateMachine.workMode.value == WorkMode.Working) {
            stopBluetoothAction()
        }
        emitSnapshot()
        return true
    }

    fun setNotificationSharing(hash: String, enabled: Boolean): Boolean {
        val normalized = hash.uppercase()
        val enabledHashes = prefs.getStringSet(NOTIFICATION_ENABLED_KEY, emptySet()).orEmpty()
            .map { it.uppercase() }
            .toMutableSet()
        val disabledHashes = prefs.getStringSet(NOTIFICATION_DISABLED_KEY, emptySet()).orEmpty()
            .map { it.uppercase() }
            .toMutableSet()
        enabledHashes.remove(normalized)
        disabledHashes.remove(normalized)
        if (enabled) {
            enabledHashes.add(normalized)
        } else {
            disabledHashes.add(normalized)
        }
        prefs.edit()
            .putStringSet(NOTIFICATION_ENABLED_KEY, enabledHashes)
            .putStringSet(NOTIFICATION_DISABLED_KEY, disabledHashes)
            .apply()
        emitSnapshot()
        return true
    }

    fun setShowSystemApps(show: Boolean): Boolean {
        AppManager.setShowSystemApps(show)
        emitSnapshot()
        return true
    }

    fun setAppSyncEnabled(packageName: String, enabled: Boolean): Boolean {
        if (packageName.isBlank()) return false
        AppManager.updateAppSelection(packageName, enabled)
        emitSnapshot()
        return true
    }

    fun sendFile(arguments: Map<String, Any?>): Boolean {
        val device = deviceFromArguments(arguments) ?: return false
        if (device.filePort == null || !device.lanReachable) return false
        val launcher = filePicker ?: return false
        launcher(device)
        emitSnapshot()
        return true
    }

    fun sendPickedFile(deviceArguments: Map<String, Any?>, uri: Uri): Boolean {
        val device = deviceFromArguments(deviceArguments) ?: return false
        val filePort = device.filePort ?: return false
        ioScope.launch {
            val success = LanFileTransferManager.sendFile(applicationContext, device.ipAddress, filePort, uri)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    applicationContext,
                    if (success) "文件已发送给 ${device.deviceName}" else "文件发送失败",
                    Toast.LENGTH_SHORT
                ).show()
                emitSnapshot()
            }
        }
        return true
    }

    fun copyClipboardHistory(content: String): Boolean {
        if (content.isBlank()) return false
        return try {
            BleForegroundService.isInternalClipboardWrite = true
            val clipboard = applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("CastPigeon", content))
            Toast.makeText(applicationContext, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
            emitSnapshot()
            true
        } catch (error: Exception) {
            BleForegroundService.isInternalClipboardWrite = false
            Toast.makeText(applicationContext, "复制失败: ${error.message}", Toast.LENGTH_SHORT).show()
            false
        }
    }

    fun selectPrivilegeMode(activity: Activity?, modeName: String): Boolean {
        return when (modeName) {
            "Default", PrivilegeMode.DEFAULT.name -> {
                PrivilegeManager.disable()
                Toast.makeText(applicationContext, "已切换为默认模式", Toast.LENGTH_SHORT).show()
                emitSnapshot()
                true
            }
            "Shizuku", PrivilegeMode.SHIZUKU.name -> {
                if (!rikka.shizuku.Shizuku.pingBinder()) {
                    Toast.makeText(applicationContext, "Shizuku 服务未运行！请先启动 Shizuku 应用程序", Toast.LENGTH_LONG).show()
                    false
                } else if (rikka.shizuku.Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    if (activity == null) return false
                    Toast.makeText(applicationContext, "正在请求 Shizuku 授权，请在弹窗中允许…", Toast.LENGTH_SHORT).show()
                    rikka.shizuku.Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
                    emitSnapshot()
                    true
                } else {
                    val accepted = PrivilegeManager.executeShizukuCommand(applicationContext)
                    emitSnapshot()
                    accepted
                }
            }
            else -> false
        }
    }

    fun checkUpdate(showNoUpdateToast: Boolean = true): Boolean {
        updateState.value = updateState.value.copy(
            message = "正在检查更新...",
            isChecking = true
        )
        emitSnapshot()
        ioScope.launch {
            val result = AppUpdateManager.checkForUpdate(applicationContext)
            withContext(Dispatchers.Main) {
                updateState.value = result.fold(
                    onSuccess = { info ->
                        if (info == null) {
                            if (showNoUpdateToast) {
                                Toast.makeText(applicationContext, "当前已是最新版本", Toast.LENGTH_SHORT).show()
                            }
                            updateState.value.copy(
                                message = "当前没有可用更新",
                                latestRelease = null,
                                isChecking = false
                            )
                        } else {
                            updateState.value.copy(
                                message = "",
                                latestRelease = info.toSerializableRelease(info.mergedBody),
                                latestOriginalRelease = info.latestRelease,
                                isChecking = false
                            )
                        }
                    },
                    onFailure = { error ->
                        updateState.value.copy(
                            message = error.message ?: "检查更新失败",
                            latestRelease = null,
                            isChecking = false
                        )
                    }
                )
                emitSnapshot()
            }
        }
        return true
    }

    fun refreshUpdateHistory(): Boolean {
        updateState.value = updateState.value.copy(
            message = "正在加载历史更新...",
            isLoadingHistory = true
        )
        emitSnapshot()
        ioScope.launch {
            val result = AppUpdateManager.getHistoryReleases()
            withContext(Dispatchers.Main) {
                updateState.value = result.fold(
                    onSuccess = { releases ->
                        updateState.value.copy(
                            historyReleases = releases.map { it.toSerializableRelease() },
                            historyOriginalReleases = releases,
                            message = if (releases.isEmpty()) "暂无历史版本" else updateState.value.message,
                            isLoadingHistory = false
                        )
                    },
                    onFailure = { error ->
                        updateState.value.copy(
                            message = error.message ?: "获取历史更新失败",
                            isLoadingHistory = false
                        )
                    }
                )
                emitSnapshot()
            }
        }
        return true
    }

    fun downloadRelease(tagName: String): Boolean {
        val release = findRelease(tagName) ?: return false
        val isOtherDownloading = releaseDownloads.values.any { it.progress in 0 until 100 || it.isVerifying }
        if (isOtherDownloading) {
            Toast.makeText(applicationContext, "已有安装包正在下载", Toast.LENGTH_SHORT).show()
            return false
        }
        val downloadId = AppUpdateManager.enqueueApkDownload(applicationContext, release.original)
        releaseDownloads[tagName] = ReleaseDownloadState(downloadId = downloadId, progress = 0)
        emitSnapshot()
        ioScope.launch {
            var progress = 0
            while (progress in 0 until 100) {
                delay(600)
                progress = AppUpdateManager.getDownloadProgress(applicationContext, downloadId)
                releaseDownloads[tagName] = releaseDownloads[tagName]?.copy(progress = progress.coerceAtLeast(0))
                    ?: ReleaseDownloadState(downloadId = downloadId, progress = progress.coerceAtLeast(0))
                withContext(Dispatchers.Main) { emitSnapshot() }
            }
            if (progress >= 100) {
                releaseDownloads[tagName] = releaseDownloads[tagName]?.copy(isVerifying = true)
                    ?: ReleaseDownloadState(downloadId = downloadId, progress = 100, isVerifying = true)
                withContext(Dispatchers.Main) { emitSnapshot() }
                val verified = AppUpdateManager.verifyDownloadedApk(applicationContext, downloadId, release.original.asset.digest)
                releaseDownloads[tagName] = releaseDownloads[tagName]?.copy(
                    progress = 100,
                    isVerifying = false,
                    isVerified = verified,
                    message = if (verified) "下载完成，可以安装" else "安装包校验失败，请重新下载"
                ) ?: ReleaseDownloadState(
                    downloadId = downloadId,
                    progress = 100,
                    isVerified = verified,
                    message = if (verified) "下载完成，可以安装" else "安装包校验失败，请重新下载"
                )
                withContext(Dispatchers.Main) { emitSnapshot() }
            }
        }
        return true
    }

    fun installRelease(tagName: String): Boolean {
        val downloadId = releaseDownloads[tagName]?.downloadId ?: return false
        val started = AppUpdateManager.installDownloadedApk(applicationContext, downloadId)
        if (!started) {
            Toast.makeText(applicationContext, "未找到已下载的安装包", Toast.LENGTH_SHORT).show()
        }
        emitSnapshot()
        return started
    }

    fun appIconBase64(packageName: String): String? {
        val bitmap = AppManager.getAppIconBitmap(applicationContext, packageName) ?: return null
        val output = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
        return android.util.Base64.encodeToString(output.toByteArray(), android.util.Base64.NO_WRAP)
    }

    fun historyIconBase64(appName: String): String? {
        val iconFile = java.io.File(applicationContext.getDir("Icons", Context.MODE_PRIVATE), "$appName.png")
        if (!iconFile.exists()) return null
        return android.util.Base64.encodeToString(iconFile.readBytes(), android.util.Base64.NO_WRAP)
    }

    fun sendTestNotification(): Boolean {
        if (stateMachine.state.value != ConnectionState.Transferring || stateMachine.role.value != DeviceRole.Sender) {
            return false
        }
        return try {
            val notification = NotificationMessage(
                id = "test_${System.currentTimeMillis()}",
                appName = "CastPigeon Test",
                title = "测试通知",
                content = "模拟消息：${Date()}",
                timestamp = System.currentTimeMillis()
            )
            val payload = json.encodeToString(notification)
            blePeripheral.sendNotificationData(payload.encodeToByteArray())
            emitSnapshot()
            true
        } catch (error: Exception) {
            android.util.Log.e("CastPigeonFlutter", "发送测试通知失败", error)
            false
        }
    }

    private fun observeStateSources() {
        scope.launch { stateMachine.state.collectLatest { emitSnapshot() } }
        scope.launch {
            stateMachine.state.collectLatest {
                handleBlePairingRequestIfNeeded()
                emitSnapshot()
            }
        }
        scope.launch { stateMachine.role.collectLatest { prefs.edit().putString("LastRole", it.name).apply(); emitSnapshot() } }
        scope.launch { stateMachine.workMode.collectLatest { emitSnapshot() } }
        scope.launch { stateMachine.pairingDeviceName.collectLatest { emitSnapshot() } }
        scope.launch { stateMachine.connectedDeviceName.collectLatest { emitSnapshot() } }
        scope.launch { AppConnectionManager.lastReceivedMessage.collectLatest { emitSnapshot() } }
        scope.launch { UdpDiscovery.discoveredDevices.collectLatest { emitSnapshot() } }
        scope.launch { LanFileTransferManager.transferStatus.collectLatest { emitSnapshot() } }
        scope.launch { AppManager.appList.collectLatest { emitSnapshot() } }
        scope.launch { AppManager.showSystemApps.collectLatest { emitSnapshot() } }
        scope.launch { PrivilegeManager.privilegeMode.collectLatest { emitSnapshot() } }
        scope.launch { PrivilegeManager.isPrivileged.collectLatest { emitSnapshot() } }
        scope.launch { PrivilegeManager.bindStatus.collectLatest { emitSnapshot() } }
        scope.launch { PrivilegeManager.activeBackend.collectLatest { emitSnapshot() } }
        scope.launch { MessageDatabaseHelper.historyChanges.collectLatest { emitSnapshot() } }
        scope.launch { updateState.collectLatest { emitSnapshot() } }
        scope.launch {
            while (true) {
                delay(1_000)
                emitSnapshot()
            }
        }
    }

    private fun observePairingEvents() {
        scope.launch {
            UdpDiscovery.pinDisplayEvent.collectLatest {
                android.util.Log.i("CastPigeonFlutter", "UDP 收到绑定请求，显示配对码: ${it.requestingDevice.deviceName} / ${it.requestingDevice.hash}")
                pinDisplayInfo = it
                emitSnapshot()
            }
        }
        scope.launch {
            UdpDiscovery.pinInputEvent.collectLatest {
                android.util.Log.i("CastPigeonFlutter", "UDP 等待输入配对码: ${it.deviceName} / ${it.hash} / ${it.ipAddress}")
                pinInputDevice = it
                emitSnapshot()
            }
        }
        scope.launch {
            UdpDiscovery.pairingSuccessEvent.collectLatest { boundDevice ->
                if (boundDevice.hash.equals(localDeviceHashString(), ignoreCase = true)) return@collectLatest
                android.util.Log.i("CastPigeonFlutter", "UDP 配对成功: ${boundDevice.deviceName} / ${boundDevice.hash} / ${boundDevice.ipAddress}")
                upsertBoundDeviceEntry(
                    name = boundDevice.deviceName,
                    hash = boundDevice.hash,
                    deviceType = boundDevice.deviceType,
                    lastIp = boundDevice.ipAddress,
                    filePort = boundDevice.filePort
                )
                UdpDiscovery.stop()
                stateMachine.setWorkMode(WorkMode.Idle)
                pinDisplayInfo = null
                pinInputDevice = null
                Toast.makeText(applicationContext, "已成功绑定 ${boundDevice.deviceName}", Toast.LENGTH_LONG).show()
                emitSnapshot()
            }
        }
    }

    private fun autoStartWorkingIfPossible() {
        if (stateMachine.workMode.value != WorkMode.Idle) return
        if (boundEntriesRaw().isEmpty()) return
        if (StartupPermissionCoordinator.missingRuntimePermissions(applicationContext).isNotEmpty()) {
            Toast.makeText(applicationContext, "请先授予启动阶段请求的权限", Toast.LENGTH_SHORT).show()
            return
        }
        startBluetoothAction(WorkMode.Working)
    }

    private fun handleBlePairingRequestIfNeeded() {
        if (stateMachine.state.value != ConnectionState.PairingRequest) return
        if (stateMachine.role.value != DeviceRole.Sender) return
        val incomingDevice = BoundDeviceStore.parse(stateMachine.pairingDeviceName.value ?: return)
        val incomingHash = incomingDevice.hash
        val isBoundIncomingDevice = boundDeviceEntries().any { entry ->
            (!incomingHash.isNullOrBlank() && entry.hash?.equals(incomingHash, ignoreCase = true) == true) ||
                entry.name == incomingDevice.name
        }
        if (stateMachine.workMode.value == WorkMode.Working && isBoundIncomingDevice) {
            stateMachine.transitionTo(ConnectionState.Transferring, stateMachine.pairingDeviceName.value)
        }
    }

    private fun restoreRole() {
        val roleName = prefs.getString("LastRole", DeviceRole.Sender.name) ?: DeviceRole.Sender.name
        runCatching { DeviceRole.valueOf(roleName) }.getOrNull()?.let { stateMachine.setRole(it) }
    }

    private fun startBluetoothAction(mode: WorkMode): Boolean {
        if (stateMachine.workMode.value != WorkMode.Idle) {
            stopBluetoothAction()
        }
        stateMachine.setWorkMode(mode)
        stateMachine.transitionTo(ConnectionState.AdvertisingOrScanning)

        val trustedHashes = boundEntriesRaw().mapNotNull { BoundDeviceStore.parse(it).hash }.map { it.uppercase() }.toSet()
        blePeripheral.onPeerAuthorizationRequested = { peerName, peerHash ->
            BoundDeviceStore.authorizeOrMigratePeer(applicationContext, peerName, peerHash).also { accepted ->
                if (accepted) {
                    blePeripheral.updateTrustedPeerHashes(BoundDeviceStore.getHashes(applicationContext))
                }
            }
        }

        UdpDiscovery.startBroadcasting(
            role = stateMachine.role.value.name,
            deviceName = localDeviceName(),
            hash = localDeviceHashString(),
            filePort = LanFileTransferManager.serverPort.value,
            deviceType = "Android",
            pairingMode = mode == WorkMode.Pairing,
            trustedHashes = trustedHashes
        )

        if (mode == WorkMode.Working) {
            BleForegroundService.start(applicationContext)
            when (stateMachine.role.value) {
                DeviceRole.Sender -> {
                    runCatching {
                        blePeripheral.updateTrustedPeerHashes(trustedHashes)
                        blePeripheral.startAdvertising(mode, localDeviceHashBytes()) { newState, name ->
                            stateMachine.transitionTo(newState, name)
                        }
                    }.onFailure { error ->
                        android.util.Log.e("CastPigeonFlutter", "startAdvertising 失败", error)
                        stateMachine.transitionTo(ConnectionState.Idle)
                        Toast.makeText(applicationContext, "蓝牙广播启动失败: ${error.message}", Toast.LENGTH_LONG).show()
                        return false
                    }
                }
                DeviceRole.Receiver -> {
                    runCatching {
                        val targetHashes = trustedHashes.map { hash ->
                            hash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                        }.toSet()
                        bleCentral.startScanning(mode, targetHashes) { newState, name ->
                            stateMachine.transitionTo(newState, name)
                        }
                    }.onFailure { error ->
                        android.util.Log.e("CastPigeonFlutter", "startScanning 失败", error)
                        stateMachine.transitionTo(ConnectionState.Idle)
                        Toast.makeText(applicationContext, "蓝牙扫描启动失败: ${error.message}", Toast.LENGTH_LONG).show()
                        return false
                    }
                }
            }
        }

        emitSnapshot()
        return true
    }

    private fun stopBluetoothAction() {
        UdpDiscovery.stop()
        blePeripheral.stopAdvertising()
        blePeripheral.disconnectCurrentDevice()
        bleCentral.stopScanning()
        bleCentral.disconnect()
        stateMachine.setWorkMode(WorkMode.Idle)
        BleForegroundService.stop(applicationContext)
    }

    private fun buildSnapshot(): CastSnapshot {
        val boundDevices = boundDeviceEntries()
        val trustedHashes = boundDevices.mapNotNull { it.hash?.uppercase() }.toSet()
        val onlineDevices = UdpDiscovery.discoveredDevices.value
            .asSequence()
            .filterNot { it.hash.equals(localDeviceHashString(), ignoreCase = true) }
            .filter {
                stateMachine.workMode.value == WorkMode.Pairing ||
                    it.lanReachable ||
                    it.filePort != null ||
                    trustedHashes.contains(it.hash.uppercase())
            }
            .groupBy { it.hash.uppercase() }
            .values
            .mapNotNull { devices -> devices.maxByOrNull { it.lastSeen } }
            .sortedWith(compareBy<UdpDevice> { it.deviceName.lowercase() }.thenBy { it.hash })
            .map { it.toSerializableDevice() }

        val databaseState = loadHistoryState()
        return CastSnapshot(
            connectionStateCode = connectionStateCode(stateMachine.state.value),
            roleCode = if (stateMachine.role.value == DeviceRole.Receiver) 1 else 0,
            workModeCode = workModeCode(stateMachine.workMode.value),
            connectionStateLabel = connectionStateLabel(stateMachine.state.value, stateMachine.role.value),
            roleLabel = if (stateMachine.role.value == DeviceRole.Receiver) "接收端" else "发送端",
            workModeLabel = workModeLabel(stateMachine.workMode.value),
            pairingDeviceName = stateMachine.pairingDeviceName.value,
            connectedDeviceName = stateMachine.connectedDeviceName.value,
            localDeviceName = localDeviceName(),
            localDeviceHash = localDeviceHashString(),
            onlineDevices = onlineDevices,
            boundDevices = boundDevices.map { it.toSerializableBoundDevice() },
            transferStatus = LanFileTransferManager.transferStatus.value?.toSerializableTransferStatus(),
            latestReceivedMessage = AppConnectionManager.lastReceivedMessage.value,
            pinDisplay = pinDisplayInfo?.toSerializablePinDisplay(),
            pinInputDevice = pinInputDevice?.toSerializableDevice(),
            historyMessages = databaseState.messages,
            clipboardItems = databaseState.clipboardItems,
            installedApps = AppManager.appList.value.map { it.toSerializableApp() },
            showSystemApps = AppManager.showSystemApps.value,
            privilege = SerializablePrivilege(
                isPrivileged = PrivilegeManager.isPrivileged.value,
                mode = when (PrivilegeManager.privilegeMode.value) {
                    PrivilegeMode.DEFAULT -> "Default"
                    PrivilegeMode.SHIZUKU -> "Shizuku"
                },
                activeBackend = when (PrivilegeManager.activeBackend.value) {
                    ActivePrivilegeBackend.NONE -> "无"
                    ActivePrivilegeBackend.SHIZUKU -> "Shizuku"
                },
                bindStatus = when (PrivilegeManager.bindStatus.value) {
                    PrivilegeManager.BindStatus.Idle -> "Idle"
                    PrivilegeManager.BindStatus.Binding -> "Binding"
                    PrivilegeManager.BindStatus.Connected -> "Connected"
                    PrivilegeManager.BindStatus.Failed -> "Failed"
                }
            ),
            update = updateState.value.toSerializableUpdate(releaseDownloads)
        )
    }

    private fun loadHistoryState(): HistoryState {
        return runCatching {
            val db = MessageDatabaseHelper(applicationContext)
            HistoryState(
                messages = db.getAllMessages().map {
                    SerializableHistoryMessage(
                        id = it.id,
                        appName = it.appName,
                        title = it.title,
                        content = it.content,
                        timestamp = it.timestamp
                    )
                },
                clipboardItems = db.getAllClipboardHistory().map {
                    SerializableClipboardItem(
                        id = it.id,
                        content = it.content,
                        direction = it.direction,
                        timestamp = it.timestamp
                    )
                }
            )
        }.getOrDefault(HistoryState())
    }

    private fun boundDeviceEntries(): List<BoundDeviceStore.Entry> {
        return normalizeBoundDeviceEntries(boundEntriesRaw())
            .map { BoundDeviceStore.parse(it) }
            .sortedWith(compareBy<BoundDeviceStore.Entry> { it.name.lowercase() }.thenBy { it.hash ?: it.rawValue })
    }

    private fun boundEntriesRaw(): Set<String> {
        return prefs.getStringSet(BOUND_MACS_KEY, emptySet()).orEmpty()
    }

    private fun normalizeAndPersistBoundDevices() {
        saveBoundDeviceEntries(boundEntriesRaw())
    }

    private fun saveBoundDeviceEntries(entries: Collection<String>) {
        prefs.edit().putStringSet(BOUND_MACS_KEY, normalizeBoundDeviceEntries(entries)).apply()
    }

    private fun upsertBoundDeviceEntry(
        name: String,
        hash: String?,
        deviceType: String,
        lastIp: String?,
        filePort: Int?
    ) {
        val cleanName = name.ifBlank { "已绑定设备" }
        val entry = hash?.takeIf { it.isNotBlank() }?.let {
            listOf(
                cleanName,
                it.uppercase(),
                deviceType.ifBlank { "Unknown" },
                lastIp.orEmpty(),
                filePort?.toString().orEmpty()
            ).joinToString("|")
        } ?: cleanName
        val filtered = boundEntriesRaw().filterNot {
            val parsed = BoundDeviceStore.parse(it)
            when {
                !hash.isNullOrBlank() && parsed.hash?.equals(hash, ignoreCase = true) == true -> true
                hash.isNullOrBlank() && parsed.name == cleanName && parsed.hash == null -> true
                else -> false
            }
        } + entry
        saveBoundDeviceEntries(filtered)
    }

    private fun normalizeBoundDeviceEntries(entries: Collection<String>): Set<String> {
        return entries
            .map { BoundDeviceStore.parse(it) }
            .groupBy { it.hash ?: "raw:${it.rawValue}" }
            .values
            .mapNotNull { group ->
                val preferred = group.maxByOrNull { entry ->
                    (if (entry.hash != null) 10 else 0) + if (entry.name.isNotBlank()) 1 else 0
                } ?: return@mapNotNull null
                preferred.hash?.let {
                    listOf(
                        preferred.name,
                        it.uppercase(),
                        preferred.deviceType,
                        preferred.lastIp.orEmpty(),
                        preferred.filePort.orEmpty()
                    ).joinToString("|")
                } ?: preferred.rawValue.takeIf { it.isNotBlank() }
            }
            .toSortedSet()
    }

    private fun notificationSharingEnabled(hash: String?, defaultEnabled: Boolean): Boolean {
        val normalized = hash?.takeIf { it.isNotBlank() }?.uppercase() ?: return false
        val enabled = prefs.getStringSet(NOTIFICATION_ENABLED_KEY, emptySet()).orEmpty().map { it.uppercase() }.toSet()
        val disabled = prefs.getStringSet(NOTIFICATION_DISABLED_KEY, emptySet()).orEmpty().map { it.uppercase() }.toSet()
        return when {
            disabled.contains(normalized) -> false
            enabled.contains(normalized) -> true
            else -> defaultEnabled
        }
    }

    private fun localDeviceHashBytes(): ByteArray {
        val androidId = Settings.Secure.getString(applicationContext.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        return MessageDigest.getInstance("SHA-256").digest(androidId.toByteArray()).copyOfRange(0, 4)
    }

    private fun localDeviceHashString(): String {
        return localDeviceHashBytes().joinToString("") { "%02X".format(it) }
    }

    private fun localDeviceName(): String {
        return Settings.Global.getString(applicationContext.contentResolver, Settings.Global.DEVICE_NAME) ?: "Android Device"
    }

    private fun deviceFromArguments(arguments: Map<String, Any?>): UdpDevice? {
        val name = arguments["deviceName"] as? String ?: return null
        val role = arguments["role"] as? String ?: "Unknown"
        val hash = arguments["hash"] as? String ?: return null
        val ipAddress = arguments["ipAddress"] as? String ?: return null
        val filePort = (arguments["filePort"] as? Number)?.toInt()
        val deviceType = arguments["deviceType"] as? String ?: "Unknown"
        val lanReachable = arguments["lanReachable"] as? Boolean ?: false
        return UdpDevice(
            deviceName = name,
            role = role,
            hash = hash,
            ipAddress = ipAddress,
            filePort = filePort,
            deviceType = deviceType,
            lanReachable = lanReachable
        )
    }

    private fun findRelease(tagName: String): BridgeRelease? {
        val state = updateState.value
        state.latestRelease?.takeIf { it.tagName == tagName }?.let { release ->
            state.latestOriginalRelease?.let { return BridgeRelease(release, it) }
        }
        val index = state.historyReleases.indexOfFirst { it.tagName == tagName }
        if (index >= 0 && index < state.historyOriginalReleases.size) {
            return BridgeRelease(state.historyReleases[index], state.historyOriginalReleases[index])
        }
        return null
    }

    private fun connectionStateCode(state: ConnectionState): Int {
        return when (state) {
            ConnectionState.Idle -> 0
            ConnectionState.AdvertisingOrScanning -> 1
            ConnectionState.Connecting -> 2
            ConnectionState.PairingRequest -> 3
            ConnectionState.Transferring -> 4
            ConnectionState.Disconnecting -> 5
        }
    }

    private fun workModeCode(mode: WorkMode): Int {
        return when (mode) {
            WorkMode.Idle -> 0
            WorkMode.Pairing -> 1
            WorkMode.Working -> 2
        }
    }

    private fun connectionStateLabel(state: ConnectionState, role: DeviceRole): String {
        return when (state) {
            ConnectionState.Idle -> "系统待机中"
            ConnectionState.AdvertisingOrScanning -> if (role == DeviceRole.Sender) "等待连接" else "寻找设备中"
            ConnectionState.Connecting -> "正在建立加密通道"
            ConnectionState.PairingRequest -> "收到配对请求"
            ConnectionState.Transferring -> "已连接 · 静默同步中"
            ConnectionState.Disconnecting -> "正在断开连接"
        }
    }

    private fun workModeLabel(mode: WorkMode): String {
        return when (mode) {
            WorkMode.Idle -> "未启动"
            WorkMode.Pairing -> "配对模式"
            WorkMode.Working -> "工作模式"
        }
    }

    private fun UdpDevice.toSerializableDevice(): SerializableDevice {
        return SerializableDevice(
            deviceName = deviceName,
            role = role,
            hash = hash,
            ipAddress = ipAddress,
            filePort = filePort,
            deviceType = deviceType,
            lanReachable = lanReachable
        )
    }

    private fun PinDisplayInfo.toSerializablePinDisplay(): SerializablePinDisplay {
        return SerializablePinDisplay(
            pin = pin,
            requestingDevice = requestingDevice.toSerializableDevice()
        )
    }

    private fun BoundDeviceStore.Entry.toSerializableBoundDevice(): SerializableBoundDevice {
        val entryHash = hash?.uppercase().orEmpty()
        return SerializableBoundDevice(
            name = name,
            hash = entryHash,
            deviceType = deviceType,
            lastIp = lastIp,
            filePort = filePort?.toIntOrNull(),
            notificationSharingEnabled = notificationSharingEnabled(entryHash, defaultEnabled = true)
        )
    }

    private fun LanFileTransferManager.TransferStatus.toSerializableTransferStatus(): SerializableTransferStatus {
        return SerializableTransferStatus(
            fileName = fileName,
            peerLabel = peerLabel,
            direction = direction.name,
            phase = phase.name,
            bytesTransferred = bytesTransferred,
            totalBytes = totalBytes,
            detail = detail
        )
    }

    private fun AppInfo.toSerializableApp(): SerializableApp {
        return SerializableApp(
            packageName = packageName,
            appName = appName,
            isSystemApp = isSystemApp,
            isSelected = isSelected
        )
    }

    private fun AppUpdateManager.ReleaseInfo.toSerializableRelease(bodyOverride: String? = null): SerializableRelease {
        return SerializableRelease(
            tagName = tagName,
            versionName = versionName,
            title = title,
            body = bodyOverride ?: body,
            assetName = asset.assetName,
            download = releaseDownloads[tagName]?.toSerializableDownload()
        )
    }

    private fun AppUpdateManager.UpdateInfo.toSerializableRelease(bodyOverride: String): SerializableRelease {
        return latestRelease.toSerializableRelease(bodyOverride)
    }

    private fun UpdateUiState.toSerializableUpdate(downloads: Map<String, ReleaseDownloadState>): SerializableUpdate {
        fun SerializableRelease.withDownload(): SerializableRelease {
            return copy(download = downloads[tagName]?.toSerializableDownload())
        }
        return SerializableUpdate(
            currentVersion = currentVersion,
            message = message,
            latestRelease = latestRelease?.withDownload(),
            historyReleases = historyReleases.map { it.withDownload() }
        )
    }

    private fun ReleaseDownloadState.toSerializableDownload(): SerializableReleaseDownload {
        return SerializableReleaseDownload(
            progress = progress,
            isVerifying = isVerifying,
            isVerified = isVerified,
            message = message
        )
    }

    private data class BridgeRelease(
        val serializable: SerializableRelease,
        val original: AppUpdateManager.ReleaseInfo
    )

    private data class HistoryState(
        val messages: List<SerializableHistoryMessage> = emptyList(),
        val clipboardItems: List<SerializableClipboardItem> = emptyList()
    )

    private data class UpdateUiState(
        val currentVersion: String,
        val message: String = "当前没有可用更新",
        val latestRelease: SerializableRelease? = null,
        val latestOriginalRelease: AppUpdateManager.ReleaseInfo? = null,
        val historyReleases: List<SerializableRelease> = emptyList(),
        val historyOriginalReleases: List<AppUpdateManager.ReleaseInfo> = emptyList(),
        val isChecking: Boolean = false,
        val isLoadingHistory: Boolean = false
    )

    private data class ReleaseDownloadState(
        val downloadId: Long? = null,
        val progress: Int = -1,
        val isVerifying: Boolean = false,
        val isVerified: Boolean = false,
        val message: String? = null
    )

    @kotlinx.serialization.Serializable
    private data class CastSnapshot(
        val connectionStateCode: Int,
        val roleCode: Int,
        val workModeCode: Int,
        val connectionStateLabel: String,
        val roleLabel: String,
        val workModeLabel: String,
        val pairingDeviceName: String?,
        val connectedDeviceName: String?,
        val localDeviceName: String,
        val localDeviceHash: String,
        val onlineDevices: List<SerializableDevice>,
        val boundDevices: List<SerializableBoundDevice>,
        val transferStatus: SerializableTransferStatus?,
        val latestReceivedMessage: String?,
        val pinDisplay: SerializablePinDisplay?,
        val pinInputDevice: SerializableDevice?,
        val historyMessages: List<SerializableHistoryMessage>,
        val clipboardItems: List<SerializableClipboardItem>,
        val installedApps: List<SerializableApp>,
        val showSystemApps: Boolean,
        val privilege: SerializablePrivilege,
        val update: SerializableUpdate
    )

    @kotlinx.serialization.Serializable
    private data class SerializableDevice(
        val deviceName: String,
        val role: String,
        val hash: String,
        val ipAddress: String,
        val filePort: Int?,
        val deviceType: String,
        val lanReachable: Boolean
    )

    @kotlinx.serialization.Serializable
    private data class SerializableBoundDevice(
        val name: String,
        val hash: String,
        val deviceType: String,
        val lastIp: String?,
        val filePort: Int?,
        val notificationSharingEnabled: Boolean
    )

    @kotlinx.serialization.Serializable
    private data class SerializableTransferStatus(
        val fileName: String,
        val peerLabel: String,
        val direction: String,
        val phase: String,
        val bytesTransferred: Long,
        val totalBytes: Long?,
        val detail: String?
    )

    @kotlinx.serialization.Serializable
    private data class SerializablePinDisplay(
        val pin: String,
        val requestingDevice: SerializableDevice
    )

    @kotlinx.serialization.Serializable
    private data class SerializableHistoryMessage(
        val id: String,
        val appName: String,
        val title: String,
        val content: String,
        val timestamp: Long
    )

    @kotlinx.serialization.Serializable
    private data class SerializableClipboardItem(
        val id: Long,
        val content: String,
        val direction: String,
        val timestamp: Long
    )

    @kotlinx.serialization.Serializable
    private data class SerializableApp(
        val packageName: String,
        val appName: String,
        val isSystemApp: Boolean,
        val isSelected: Boolean
    )

    @kotlinx.serialization.Serializable
    private data class SerializablePrivilege(
        val isPrivileged: Boolean,
        val mode: String,
        val activeBackend: String,
        val bindStatus: String
    )

    @kotlinx.serialization.Serializable
    private data class SerializableUpdate(
        val currentVersion: String,
        val message: String,
        val latestRelease: SerializableRelease?,
        val historyReleases: List<SerializableRelease>
    )

    @kotlinx.serialization.Serializable
    private data class SerializableRelease(
        val tagName: String,
        val versionName: String,
        val title: String,
        val body: String,
        val assetName: String,
        val download: SerializableReleaseDownload? = null
    )

    @kotlinx.serialization.Serializable
    private data class SerializableReleaseDownload(
        val progress: Int,
        val isVerifying: Boolean,
        val isVerified: Boolean,
        val message: String?
    )

    private companion object {
        private const val PREFS_NAME = "CastPigeonPrefs"
        private const val BOUND_MACS_KEY = "BoundMacs"
        private const val NOTIFICATION_ENABLED_KEY = "NotificationShareEnabledHashes"
        private const val NOTIFICATION_DISABLED_KEY = "NotificationShareDisabledHashes"
        private const val SHIZUKU_PERMISSION_REQUEST_CODE = 1001
    }
}
