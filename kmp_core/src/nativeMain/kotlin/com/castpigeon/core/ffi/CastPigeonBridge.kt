@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.castpigeon.core.ffi

import com.castpigeon.core.ConnectionState
import com.castpigeon.core.ConnectionStateMachine
import com.castpigeon.core.DeviceRole
import com.castpigeon.core.WorkMode
import com.castpigeon.core.ffi.dartapi.castpigeon_dart_initialize_api_dl
import com.castpigeon.core.ffi.dartapi.castpigeon_dart_post_integer
import com.castpigeon.core.network.UdpDevice
import com.castpigeon.core.network.UdpDiscovery
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.posix.free
import platform.posix.gethostname
import platform.posix.strdup
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.CName
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned

private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
private val stateMachine = ConnectionStateMachine(bridgeScope)
private val json = Json { encodeDefaults = true }
private val localIdentity = LocalIdentity.create()

private var statePort: Long = 0L
private var isDartApiInitialized = false
private var localDeviceName = localIdentity.deviceName
private var localDeviceHash = localIdentity.deviceHash
private var pendingPinDisplay: PinDisplayDto? = null
private var pendingPinInputDevice: UdpDevice? = null
private var showSystemApps = false

private val boundDevices = mutableListOf<BoundDeviceDto>()
private val notificationShareEnabledHashes = mutableSetOf<String>()
private val notificationShareDisabledHashes = mutableSetOf<String>()

@Serializable
private data class CastPigeonSnapshotDto(
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
    val onlineDevices: List<UdpDevice>,
    val boundDevices: List<BoundDeviceDto>,
    val transferStatus: TransferStatusDto?,
    val latestReceivedMessage: String?,
    val pinDisplay: PinDisplayDto?,
    val pinInputDevice: UdpDevice?,
    val historyMessages: List<HistoryMessageDto>,
    val clipboardItems: List<ClipboardHistoryDto>,
    val installedApps: List<AppSyncDto>,
    val showSystemApps: Boolean,
    val privilege: PrivilegeStateDto,
    val update: UpdateStateDto
)

@Serializable
private data class BoundDeviceDto(
    val name: String,
    val hash: String,
    val deviceType: String,
    val lastIp: String?,
    val filePort: Int?,
    val notificationSharingEnabled: Boolean
)

@Serializable
private data class TransferStatusDto(
    val fileName: String,
    val peerLabel: String,
    val direction: String,
    val phase: String,
    val bytesTransferred: Long,
    val totalBytes: Long?,
    val detail: String?
)

@Serializable
private data class HistoryMessageDto(
    val id: String,
    val appName: String,
    val title: String,
    val content: String,
    val timestamp: Long
)

@Serializable
private data class ClipboardHistoryDto(
    val id: Long,
    val content: String,
    val direction: String,
    val timestamp: Long
)

@Serializable
private data class AppSyncDto(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean,
    val isSelected: Boolean
)

@Serializable
private data class PrivilegeStateDto(
    val isPrivileged: Boolean,
    val mode: String,
    val activeBackend: String,
    val bindStatus: String
)

@Serializable
private data class UpdateStateDto(
    val currentVersion: String,
    val message: String,
    val latestRelease: ReleaseDto?,
    val historyReleases: List<ReleaseDto>
)

@Serializable
private data class ReleaseDto(
    val tagName: String,
    val versionName: String,
    val title: String,
    val body: String,
    val assetName: String
)

@Serializable
private data class PinDisplayDto(
    val pin: String,
    val requestingDevice: UdpDevice
)

private data class LocalIdentity(
    val deviceName: String,
    val deviceHash: String
) {
    companion object {
        fun create(): LocalIdentity {
            val hostName = readHostName().ifBlank { "Mac" }
            val deviceName = hostName.removeSuffix(".local").ifBlank { "Mac" }
            return LocalIdentity(
                deviceName = deviceName,
                deviceHash = "MAC_${stableHash(deviceName)}"
            )
        }

        private fun readHostName(): String {
            val buffer = ByteArray(256)
            val ok = buffer.usePinned { pinned ->
                gethostname(pinned.addressOf(0), buffer.size.convert()) == 0
            }
            if (!ok) return ""
            val end = buffer.indexOf(0).takeIf { it >= 0 } ?: buffer.size
            return buffer.decodeToString(endIndex = end).trim()
        }

        private fun stableHash(value: String): String {
            var hash = 0x811c9dc5u
            value.encodeToByteArray().forEach { byte ->
                hash = hash xor byte.toUByte().toUInt()
                hash *= 0x01000193u
            }
            return hash.toString(16).uppercase().padStart(8, '0')
        }
    }
}

@OptIn(ExperimentalNativeApi::class)
@CName("castpigeon_initialize")
fun castpigeonInitialize(): Int {
    observeState()
    observeDiscoveryEvents()
    return 1
}

@OptIn(ExperimentalNativeApi::class)
@CName("castpigeon_initialize_dart_api")
fun initializeDartApi(data: COpaquePointer?): Int {
    isDartApiInitialized = data != null && castpigeon_dart_initialize_api_dl(data) == 0L
    return if (isDartApiInitialized) {
        1
    } else {
        0
    }
}

@OptIn(ExperimentalNativeApi::class)
@CName("castpigeon_register_state_port")
fun registerStatePort(port: Long): Int {
    statePort = port
    postStateCode(stateMachine.state.value.code)
    return 1
}

@OptIn(ExperimentalNativeApi::class)
@CName("castpigeon_get_connection_state")
fun getConnectionState(): Int = stateMachine.state.value.code

@OptIn(ExperimentalNativeApi::class)
@CName("castpigeon_set_role")
fun setRole(roleCode: Int): Int {
    stateMachine.setRole(DeviceRole.fromCode(roleCode))
    return 1
}

@OptIn(ExperimentalNativeApi::class)
@CName("castpigeon_set_work_mode")
fun setWorkMode(workModeCode: Int): Int {
    stateMachine.setWorkMode(WorkMode.fromCode(workModeCode))
    return 1
}

@OptIn(ExperimentalNativeApi::class)
@CName("castpigeon_start_discovery")
fun startDiscovery(): Int {
    return startPairing()
}

@OptIn(ExperimentalNativeApi::class)
@CName("castpigeon_start_pairing")
fun startPairing(): Int {
    stateMachine.setWorkMode(WorkMode.Pairing)
    stateMachine.transitionTo(ConnectionState.AdvertisingOrScanning)
    UdpDiscovery.startBroadcasting(
        role = stateMachine.role.value.name,
        deviceName = localDeviceName,
        hash = localDeviceHash,
        deviceType = "KMP",
        pairingMode = true
    )
    return 1
}

@OptIn(ExperimentalNativeApi::class)
@CName("castpigeon_start_working")
fun startWorking(): Int {
    stateMachine.setWorkMode(WorkMode.Working)
    stateMachine.transitionTo(ConnectionState.AdvertisingOrScanning)
    UdpDiscovery.startBroadcasting(
        role = stateMachine.role.value.name,
        deviceName = localDeviceName,
        hash = localDeviceHash,
        deviceType = "KMP",
        pairingMode = false,
        trustedHashes = boundDevices.map { it.hash }.toSet()
    )
    return 1
}

@OptIn(ExperimentalNativeApi::class)
@CName("castpigeon_stop_discovery")
fun stopDiscovery(): Int {
    UdpDiscovery.stop()
    stateMachine.setWorkMode(WorkMode.Idle)
    stateMachine.transitionTo(ConnectionState.Idle)
    return 1
}

@OptIn(ExperimentalNativeApi::class)
@CName("castpigeon_device_list_json")
fun deviceListJson(): CPointer<ByteVar>? {
    val payload = json.encodeToString(UdpDiscovery.discoveredDevices.value.sortedBy { it.deviceName })
    return payload.toNativeUtf8Copy()
}

@OptIn(ExperimentalNativeApi::class)
@CName("castpigeon_snapshot_json")
fun snapshotJson(): CPointer<ByteVar>? {
    return json.encodeToString(snapshot()).toNativeUtf8Copy()
}

@OptIn(ExperimentalNativeApi::class)
@CName("castpigeon_request_binding")
fun requestBinding(
    targetHash: CPointer<ByteVar>?,
    targetDeviceName: CPointer<ByteVar>?,
    targetRole: CPointer<ByteVar>?,
    targetIp: CPointer<ByteVar>?
): Int {
    UdpDiscovery.requestBinding(
        targetHash = targetHash.toKStringOrEmpty(),
        targetDeviceName = targetDeviceName.toKStringOrEmpty(),
        targetRole = targetRole.toKStringOrEmpty(),
        targetIp = targetIp.toKStringOrEmpty()
    )
    return 1
}

@OptIn(ExperimentalNativeApi::class)
@CName("castpigeon_verify_binding")
fun verifyBinding(targetHash: CPointer<ByteVar>?, pin: CPointer<ByteVar>?, targetIp: CPointer<ByteVar>?): Int {
    UdpDiscovery.verifyBinding(
        targetHash.toKStringOrEmpty(),
        pin.toKStringOrEmpty(),
        targetIp.toKStringOrEmpty()
    )
    pendingPinInputDevice = null
    return 1
}

@OptIn(ExperimentalNativeApi::class)
@CName("castpigeon_cancel_pairing_prompt")
fun cancelPairingPrompt(): Int {
    pendingPinDisplay = null
    pendingPinInputDevice = null
    if (stateMachine.workMode.value == WorkMode.Pairing) {
        UdpDiscovery.stop()
        stateMachine.setWorkMode(WorkMode.Idle)
    }
    return 1
}

@OptIn(ExperimentalNativeApi::class)
@CName("castpigeon_remove_bound_device")
fun removeBoundDevice(hash: CPointer<ByteVar>?): Int {
    val normalized = hash.toKStringOrEmpty().uppercase()
    boundDevices.removeAll { it.hash.uppercase() == normalized }
    notificationShareEnabledHashes.remove(normalized)
    notificationShareDisabledHashes.remove(normalized)
    return 1
}

@OptIn(ExperimentalNativeApi::class)
@CName("castpigeon_set_notification_sharing")
fun setNotificationSharing(hash: CPointer<ByteVar>?, enabled: Int): Int {
    val normalized = hash.toKStringOrEmpty().uppercase()
    if (normalized.isBlank()) return 0
    notificationShareEnabledHashes.remove(normalized)
    notificationShareDisabledHashes.remove(normalized)
    if (enabled == 1) {
        notificationShareEnabledHashes.add(normalized)
    } else {
        notificationShareDisabledHashes.add(normalized)
    }
    rewriteBoundDeviceNotificationState(normalized)
    return 1
}

@OptIn(ExperimentalNativeApi::class)
@CName("castpigeon_set_show_system_apps")
fun setShowSystemApps(show: Int): Int {
    showSystemApps = show == 1
    return 1
}

@OptIn(ExperimentalNativeApi::class)
@CName("castpigeon_set_app_sync_enabled")
fun setAppSyncEnabled(packageName: CPointer<ByteVar>?, enabled: Int): Int {
    val normalizedPackageName = packageName.toKStringOrEmpty()
    return if (normalizedPackageName.isBlank()) 0 else 1
}

@OptIn(ExperimentalNativeApi::class)
@CName("free_string_pointer")
fun freeStringPointer(pointer: CPointer<ByteVar>?) {
    if (pointer != null) {
        free(pointer)
    }
}

private var isObservingState = false
private var isObservingDiscoveryEvents = false

private fun observeState() {
    if (isObservingState) return
    isObservingState = true
    bridgeScope.launch {
        stateMachine.state
            .map { it.code }
            .collect { code -> postStateCode(code) }
    }
}

private fun observeDiscoveryEvents() {
    if (isObservingDiscoveryEvents) return
    isObservingDiscoveryEvents = true
    bridgeScope.launch {
        UdpDiscovery.pinDisplayEvent.collect { info ->
            pendingPinDisplay = PinDisplayDto(info.pin, info.requestingDevice)
            postStateCode(stateMachine.state.value.code)
        }
    }
    bridgeScope.launch {
        UdpDiscovery.pinInputEvent.collect { device ->
            pendingPinInputDevice = device
            postStateCode(stateMachine.state.value.code)
        }
    }
    bridgeScope.launch {
        UdpDiscovery.pairingSuccessEvent.collect { device ->
            upsertBoundDevice(device)
            pendingPinDisplay = null
            pendingPinInputDevice = null
            UdpDiscovery.stop()
            stateMachine.setWorkMode(WorkMode.Idle)
        }
    }
    bridgeScope.launch {
        UdpDiscovery.discoveredDevices.collect {
            postStateCode(stateMachine.state.value.code)
        }
    }
}

private fun postStateCode(code: Int) {
    if (statePort == 0L || !isDartApiInitialized) return
    castpigeon_dart_post_integer(statePort, code.toLong())
}

private fun snapshot(): CastPigeonSnapshotDto {
    val state = stateMachine.state.value
    val role = stateMachine.role.value
    val workMode = stateMachine.workMode.value
    val trustedHashes = boundDevices.map { it.hash.uppercase() }.toSet()
    val onlineDevices = UdpDiscovery.discoveredDevices.value
        .filterNot { it.hash.equals(localDeviceHash, ignoreCase = true) }
        .filter {
            workMode == WorkMode.Pairing ||
                it.lanReachable ||
                it.filePort != null ||
                trustedHashes.contains(it.hash.uppercase())
        }
        .groupBy { it.hash.uppercase() }
        .mapNotNull { (_, devices) -> devices.maxByOrNull { it.lastSeen } }
        .sortedWith(compareBy<UdpDevice> { it.deviceName.lowercase() }.thenBy { it.hash })

    return CastPigeonSnapshotDto(
        connectionStateCode = state.code,
        roleCode = role.code,
        workModeCode = workMode.code,
        connectionStateLabel = state.label(),
        roleLabel = role.label(),
        workModeLabel = workMode.label(),
        pairingDeviceName = stateMachine.pairingDeviceName.value,
        connectedDeviceName = stateMachine.connectedDeviceName.value,
        localDeviceName = localDeviceName,
        localDeviceHash = localDeviceHash,
        onlineDevices = onlineDevices,
        boundDevices = boundDevices.sortedWith(compareBy<BoundDeviceDto> { it.name.lowercase() }.thenBy { it.hash }),
        transferStatus = null,
        latestReceivedMessage = null,
        pinDisplay = pendingPinDisplay,
        pinInputDevice = pendingPinInputDevice,
        historyMessages = emptyList(),
        clipboardItems = emptyList(),
        installedApps = emptyList(),
        showSystemApps = showSystemApps,
        privilege = PrivilegeStateDto(
            isPrivileged = false,
            mode = "Default",
            activeBackend = "None",
            bindStatus = "Idle"
        ),
        update = UpdateStateDto(
            currentVersion = "1.0.0",
            message = "当前没有可用更新",
            latestRelease = null,
            historyReleases = emptyList()
        )
    )
}

private fun upsertBoundDevice(device: UdpDevice) {
    if (device.hash.equals(localDeviceHash, ignoreCase = true)) return
    val hash = device.hash.uppercase()
    boundDevices.removeAll { it.hash.uppercase() == hash }
    boundDevices.add(
        BoundDeviceDto(
            name = device.deviceName.ifBlank { "Bound Device" },
            hash = device.hash,
            deviceType = device.deviceType.ifBlank { "Unknown" },
            lastIp = device.ipAddress.ifBlank { null },
            filePort = device.filePort,
            notificationSharingEnabled = notificationShareState(hash, defaultEnabled = true)
        )
    )
}

private fun rewriteBoundDeviceNotificationState(hash: String) {
    val index = boundDevices.indexOfFirst { it.hash.equals(hash, ignoreCase = true) }
    if (index < 0) return
    val current = boundDevices[index]
    boundDevices[index] = current.copy(
        notificationSharingEnabled = notificationShareState(hash, defaultEnabled = true)
    )
}

private fun notificationShareState(hash: String, defaultEnabled: Boolean): Boolean {
    val normalized = hash.uppercase()
    return when {
        notificationShareDisabledHashes.contains(normalized) -> false
        notificationShareEnabledHashes.contains(normalized) -> true
        else -> defaultEnabled
    }
}

private fun ConnectionState.label(): String = when (this) {
    ConnectionState.Idle -> "系统待机中"
    ConnectionState.AdvertisingOrScanning -> "正在发现设备"
    ConnectionState.Connecting -> "正在建立加密通道"
    ConnectionState.PairingRequest -> "等待配对确认"
    ConnectionState.Transferring -> "已连接 · 静默同步中"
    ConnectionState.Disconnecting -> "正在断开连接"
}

private fun DeviceRole.label(): String = when (this) {
    DeviceRole.Sender -> "发送端"
    DeviceRole.Receiver -> "接收端"
}

private fun WorkMode.label(): String = when (this) {
    WorkMode.Idle -> "未启动"
    WorkMode.Pairing -> "配对模式"
    WorkMode.Working -> "工作模式"
}

private fun CPointer<ByteVar>?.toKStringOrEmpty(): String = this?.toKString().orEmpty()

private fun String.toNativeUtf8Copy(): CPointer<ByteVar> {
    return strdup(this)?.reinterpret()
        ?: error("Unable to allocate native string")
}
