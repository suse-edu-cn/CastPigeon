@file:Suppress(
    "UNUSED_VARIABLE",
    "UNUSED_PARAMETER",
    "USELESS_CAST",
    "RedundantRequireNotNullCall",
    "RemoveRedundantQualifierName",
    "UNUSED_IMPORT",
    "CanBeVal"
)

package com.suseoaa.castpigeon.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import android.content.Intent
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.suseoaa.castpigeon.shared.*
import com.suseoaa.castpigeon.shared.network.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.Date
import com.suseoaa.castpigeon.AppManager
import com.suseoaa.castpigeon.BoundDeviceStore
import com.suseoaa.castpigeon.StartupPermissionCoordinator
import com.suseoaa.castpigeon.shared.crypto.Crypto
import kotlinx.serialization.encodeToString
import com.suseoaa.castpigeon.service.AppConnectionManager
import com.suseoaa.castpigeon.service.LanFileTransferManager
import com.suseoaa.castpigeon.update.AppUpdateManager
import androidx.core.content.edit
import top.yukonga.miuix.kmp.theme.MiuixTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Info

// 底部导航项
enum class AppTab(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Dashboard("状态看板", Icons.Default.Home),
    History("历史记录", Icons.Default.History),
    Settings("同步设置", Icons.Default.Settings),
    Info("信息", Icons.Default.Info)
}

data class BoundDeviceEntry(
    val name: String,
    val hash: String? = null,
    val deviceType: String = "Unknown",
    val lastIp: String? = null,
    val filePort: Int? = null,
    val rawValue: String
)

@Composable
private fun AppIcon(
    packageName: String,
    appName: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var iconBitmap by remember(packageName) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(packageName) {
        iconBitmap = withContext(Dispatchers.IO) {
            AppManager.getAppIconBitmap(context.applicationContext, packageName)
        }
    }

    if (iconBitmap != null) {
        Image(
            bitmap = iconBitmap!!.asImageBitmap(),
            contentDescription = appName,
            modifier = modifier
                .clip(RoundedCornerShape(10.dp))
        )
    } else {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text(
                appName.take(1),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun parseBoundDeviceEntry(raw: String): BoundDeviceEntry {
    val parts = raw.split("|")
    return when {
        parts.size >= 2 -> BoundDeviceEntry(
            name = parts[0].ifBlank { "已绑定设备" },
            hash = parts[1].ifBlank { null },
            deviceType = parts.getOrNull(2)?.ifBlank { "Unknown" } ?: "Unknown",
            lastIp = parts.getOrNull(3)?.ifBlank { null },
            filePort = parts.getOrNull(4)?.toIntOrNull()?.takeIf { it > 0 },
            rawValue = raw
        )
        else -> BoundDeviceEntry(
            name = raw.ifBlank { "已绑定设备" },
            hash = null,
            rawValue = raw
        )
    }
}

private fun normalizeBoundDeviceEntries(entries: Collection<String>): Set<String> {
    return entries
        .map(::parseBoundDeviceEntry)
        .groupBy { it.hash ?: "raw:${it.rawValue}" }
        .values
        .mapNotNull { group ->
            val preferred = group.maxByOrNull { entry ->
                (if (entry.hash != null) 10 else 0) + if (entry.name.isNotBlank()) 1 else 0
	            } ?: return@mapNotNull null
	            preferred.hash?.let {
                    listOf(
                        preferred.name,
                        it,
                        preferred.deviceType,
                        preferred.lastIp.orEmpty(),
                        preferred.filePort?.toString().orEmpty()
                    ).joinToString("|")
                } ?: preferred.rawValue.takeIf { it.isNotBlank() }
	        }
	        .toSortedSet()
}

private fun saveBoundDeviceEntries(
    prefs: android.content.SharedPreferences,
    boundMacs: SnapshotStateList<String>,
    entries: Collection<String>
) {
    val normalized = normalizeBoundDeviceEntries(entries)
    prefs.edit { putStringSet("BoundMacs", normalized) }
    boundMacs.clear()
    boundMacs.addAll(normalized)
}

private fun upsertBoundDeviceEntry(
    prefs: android.content.SharedPreferences,
    boundMacs: SnapshotStateList<String>,
    name: String,
    hash: String? = null,
    deviceType: String = "Unknown",
    lastIp: String? = null,
    filePort: Int? = null
) {
    val cleanName = name.ifBlank { "已绑定设备" }
    val entry = hash?.takeIf { it.isNotBlank() }?.let {
        listOf(
            cleanName,
            it,
            deviceType.ifBlank { "Unknown" },
            lastIp.orEmpty(),
            filePort?.toString().orEmpty()
        ).joinToString("|")
    } ?: cleanName
    val filtered = boundMacs.filterNot {
        val parsed = parseBoundDeviceEntry(it)
        when {
            !hash.isNullOrBlank() && parsed.hash == hash -> true
            hash.isNullOrBlank() && parsed.name == cleanName && parsed.hash == null -> true
            else -> false
        }
    } + entry
    saveBoundDeviceEntries(prefs, boundMacs, filtered)
}

private fun removeBoundDeviceEntry(
    prefs: android.content.SharedPreferences,
    boundMacs: SnapshotStateList<String>,
    target: BoundDeviceEntry
) {
    val filtered = boundMacs.filterNot {
        val parsed = parseBoundDeviceEntry(it)
        when {
            target.hash != null -> parsed.hash == target.hash
            else -> parsed.rawValue == target.rawValue
        }
    }
    saveBoundDeviceEntries(prefs, boundMacs, filtered)
}

private fun normalizedHash(hash: String?): String? = hash?.takeIf { it.isNotBlank() }?.uppercase()

private fun isNotificationSharingEnabled(
    hash: String?,
    defaultEnabled: Boolean,
    enabledHashes: Set<String>,
    disabledHashes: Set<String>
): Boolean {
    val normalized = normalizedHash(hash) ?: return false
    return when {
        disabledHashes.contains(normalized) -> false
        enabledHashes.contains(normalized) -> true
        else -> defaultEnabled
    }
}

private fun updateNotificationSharing(
    prefs: android.content.SharedPreferences,
    enabledHashes: SnapshotStateList<String>,
    disabledHashes: SnapshotStateList<String>,
    hash: String,
    enabled: Boolean
) {
    val normalized = hash.uppercase()
    enabledHashes.removeAll { it.equals(normalized, ignoreCase = true) }
    disabledHashes.removeAll { it.equals(normalized, ignoreCase = true) }
    if (enabled) {
        enabledHashes.add(normalized)
    } else {
        disabledHashes.add(normalized)
    }
    prefs.edit {
        putStringSet("NotificationShareEnabledHashes", enabledHashes.map { it.uppercase() }.toSet())
        putStringSet("NotificationShareDisabledHashes", disabledHashes.map { it.uppercase() }.toSet())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    stateMachine: ConnectionStateMachine = AppConnectionManager.stateMachine,
    blePeripheral: BlePeripheral = AppConnectionManager.blePeripheral,
    bleCentral: BleCentral = AppConnectionManager.bleCentral,
) {
    var currentTab by remember { mutableStateOf(AppTab.Dashboard) }

    // 所有基础状态 (原样保留，不要改动核心业务逻辑)
    val connectionState by stateMachine.state.collectAsState()
    val role by stateMachine.role.collectAsState()
    val workMode by stateMachine.workMode.collectAsState()
    val pairingDeviceName by stateMachine.pairingDeviceName.collectAsState()
    val connectedDeviceName by stateMachine.connectedDeviceName.collectAsState()

    val hazeState = remember { dev.chrisbanes.haze.HazeState() }
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(
        initialPage = AppTab.entries.indexOf(currentTab), pageCount = { AppTab.entries.size })
    val scope = rememberCoroutineScope()
    var scrollJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var isFirstComposition by remember { mutableStateOf(true) }

    LaunchedEffect(pagerState.settledPage) {
        if (isFirstComposition) {
            isFirstComposition = false
            return@LaunchedEffect
        }
        if (scrollJob?.isActive != true) {
            val targetTab = AppTab.entries[pagerState.settledPage]
            if (targetTab != currentTab) {
                currentTab = targetTab
            }
        }
    }

    LaunchedEffect(currentTab) {
        val targetIndex = AppTab.entries.indexOf(currentTab)
        if (pagerState.currentPage != targetIndex) {
            scrollJob?.cancel()
            scrollJob = launch {
                pagerState.animateScrollToPage(targetIndex)
            }
        }
    }

    val context = LocalContext.current as android.content.Context

    // 生成设备唯一标识Hash(取前4字节)
    @android.annotation.SuppressLint("HardwareIds")
    val deviceHash = remember {
        val androidId = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: "unknown"
        val bytes = MessageDigest.getInstance("SHA-256").digest(androidId.toByteArray())
        bytes.copyOfRange(0, 4)
    }

    // 本地持久化信任的Mac列表
    val prefs = remember { context.getSharedPreferences("CastPigeonPrefs", Context.MODE_PRIVATE) }
    val boundMacs = remember {
        mutableStateListOf<String>().apply {
            addAll(normalizeBoundDeviceEntries(prefs.getStringSet("BoundMacs", emptySet()) ?: emptySet()))
        }
    }
    val boundDeviceEntries by remember { derivedStateOf { boundMacs.map(::parseBoundDeviceEntry) } }
    val notificationShareEnabledHashes = remember {
        mutableStateListOf<String>().apply {
            addAll(prefs.getStringSet("NotificationShareEnabledHashes", emptySet()).orEmpty().map { it.uppercase() })
        }
    }
    val notificationShareDisabledHashes = remember {
        mutableStateListOf<String>().apply {
            addAll(prefs.getStringSet("NotificationShareDisabledHashes", emptySet()).orEmpty().map { it.uppercase() })
        }
    }

    val myName = remember {
        android.provider.Settings.Global.getString(
            context.contentResolver,
            android.provider.Settings.Global.DEVICE_NAME
        ) ?: "Android Device"
    }
    val myHashStr = remember(deviceHash) { deviceHash.joinToString("") { "%02X".format(it) } }

    var pinDisplayInfo by remember { mutableStateOf<PinDisplayInfo?>(null) }
    var pinInputDevice by remember { mutableStateOf<UdpDevice?>(null) }

    LaunchedEffect(Unit) {
        saveBoundDeviceEntries(prefs, boundMacs, boundMacs)
        launch {
            UdpDiscovery.pinDisplayEvent.collect { info ->
                pinDisplayInfo = info
            }
        }
        launch {
            UdpDiscovery.pinInputEvent.collect { device ->
                pinInputDevice = device
            }
        }
    }

    LaunchedEffect(workMode) {
        if (workMode == WorkMode.Pairing) {
            UdpDiscovery.pairingSuccessEvent.collect { boundDevice ->
                if (boundDevice.hash == myHashStr) return@collect
	                upsertBoundDeviceEntry(
	                    prefs = prefs,
	                    boundMacs = boundMacs,
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
                android.widget.Toast.makeText(
                    context,
                    "已成功被 ${boundDevice.deviceName} 绑定！",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    val receivedMockMessage by com.suseoaa.castpigeon.service.AppConnectionManager.lastReceivedMessage.collectAsState()

    // 监听全局通知事件并发送 (已迁移至 BleForegroundService，此处移除以避免重复)

    // 触发动作状态
    var pendingAction by remember { mutableStateOf<WorkMode?>(null) }

    // Role 持久化与自动启动
    LaunchedEffect(Unit) {
        val lastRoleStr =
            prefs.getString("LastRole", DeviceRole.Sender.name) ?: DeviceRole.Sender.name
        try {
            stateMachine.setRole(DeviceRole.valueOf(lastRoleStr))
        } catch (_: Exception) {
        }

        if (boundMacs.isNotEmpty()) {
            if (StartupPermissionCoordinator.missingRuntimePermissions(context).isEmpty()) {
                startBluetoothAction(
                    stateMachine,
                    blePeripheral,
                    bleCentral,
                    role,
                    WorkMode.Working,
                    deviceHash,
                    boundMacs,
                    myName
                )
            } else {
                pendingAction = WorkMode.Working
                android.widget.Toast.makeText(
                    context,
                    "请先授予启动阶段请求的权限",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    LaunchedEffect(role) {
        prefs.edit { putString("LastRole", role.name) }
    }

    // 处理握手配对弹窗(Android作为Peripheral接收配对请求时)
    if (connectionState == ConnectionState.PairingRequest && role == DeviceRole.Sender) {
        val incomingDevice = parseBoundDeviceEntry(pairingDeviceName ?: "Unknown Device")
        val macName = incomingDevice.name
        val isBoundIncomingDevice = boundDeviceEntries.any {
            (!incomingDevice.hash.isNullOrBlank() && it.hash == incomingDevice.hash) || it.name == macName
        }
        if (workMode == WorkMode.Working && isBoundIncomingDevice) {
            LaunchedEffect(macName) {
                stateMachine.transitionTo(ConnectionState.Transferring, pairingDeviceName)
            }
        } else {
            AlertDialog(
                onDismissRequest = {
                    blePeripheral.disconnectCurrentDevice()
                    stateMachine.transitionTo(ConnectionState.AdvertisingOrScanning)
                },
                title = { Text("配对请求", fontWeight = FontWeight.Bold) },
                text = { Text("收到来自 [$macName] 的请求，是否允许并绑定该设备？") },
                confirmButton = {
                    Button(onClick = {
	                        upsertBoundDeviceEntry(
	                            prefs = prefs,
	                            boundMacs = boundMacs,
	                            name = incomingDevice.name,
                                hash = incomingDevice.hash,
                                deviceType = incomingDevice.deviceType
	                        )
                        stateMachine.transitionTo(ConnectionState.Transferring)
                    }) {
                        Text("允许并绑定")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        blePeripheral.disconnectCurrentDevice()
                        stateMachine.transitionTo(ConnectionState.AdvertisingOrScanning)
                    }) {
                        Text("拒绝", color = MaterialTheme.colorScheme.error)
                    }
                }
            )
        }
    }

    if (pinDisplayInfo != null) {
        val info = pinDisplayInfo!!
        AlertDialog(
            onDismissRequest = { },
            title = { Text("配对请求", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("${info.requestingDevice.deviceName} 请求绑定。")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("请在对方设备上输入以下配对码：")
                    Text(
                        info.pin,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pinDisplayInfo =
                        null; UdpDiscovery.stop(); stateMachine.setWorkMode(WorkMode.Idle)
                }) { Text("取消") }
            }
        )
    }

    if (pinInputDevice != null) {
        var inputPin by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { },
            title = { Text("输入配对码", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("请输入 ${pinInputDevice!!.deviceName} 上显示的 4 位配对码：")
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = inputPin,
                        onValueChange = { if (it.length <= 4) inputPin = it },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (inputPin.length == 4) {
                        UdpDiscovery.verifyBinding(pinInputDevice!!.hash, inputPin)
                    }
                }) { Text("验证") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pinInputDevice =
                        null; UdpDiscovery.stop(); stateMachine.setWorkMode(WorkMode.Idle)
                }) { Text("取消") }
            }
        )
    }

    MiuixTheme {
        com.suseoaa.castpigeon.ui.component.sukisu.LiquidGlassBackdropWrapper(
            isLiquidGlassTabbarEnabled = true,
            liquidGlassTabbarStyle = 2,
            selectedIndex = { AppTab.entries.indexOf(currentTab) },
            onNavigate = {
                currentTab = AppTab.entries[it]
            },
            onBottomBarHeightChanged = {},
            modifier = Modifier.fillMaxSize()
        ) { backdropModifier ->
            androidx.compose.foundation.pager.HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .then(backdropModifier)
                    .hazeSource(state = hazeState),
                beyondViewportPageCount = AppTab.entries.size - 1,
            ) { page ->
                val tab = AppTab.entries[page]
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    when (tab) {
                        AppTab.Dashboard -> DashboardContent(
                            stateMachine = stateMachine,
                            blePeripheral = blePeripheral,
                            bleCentral = bleCentral,
                            connectionState = connectionState,
                            role = role,
                            workMode = workMode,
                            pairingDeviceName = pairingDeviceName,
                            connectedDeviceName = connectedDeviceName,
                            deviceHash = deviceHash,
                            boundMacs = boundMacs,
                            boundDeviceEntries = boundDeviceEntries,
                            myName = myName,
                            myHashStr = myHashStr,
                            prefs = prefs,
                            notificationShareEnabledHashes = notificationShareEnabledHashes,
                            notificationShareDisabledHashes = notificationShareDisabledHashes,
                            hazeState = hazeState
                        )

                        AppTab.History -> HistoryScreen()
                        AppTab.Settings -> SettingsContent()
                        AppTab.Info -> InfoContent()
                    }
                }
            }
        }
    }
}

@Composable
fun DashboardContent(
    stateMachine: ConnectionStateMachine,
    blePeripheral: BlePeripheral,
    bleCentral: BleCentral,
    connectionState: ConnectionState,
    role: DeviceRole,
    workMode: WorkMode,
    pairingDeviceName: String?,
    connectedDeviceName: String?,
    deviceHash: ByteArray,
    boundMacs: SnapshotStateList<String>,
    boundDeviceEntries: List<BoundDeviceEntry>,
    myName: String,
    myHashStr: String,
    prefs: android.content.SharedPreferences,
    notificationShareEnabledHashes: SnapshotStateList<String>,
    notificationShareDisabledHashes: SnapshotStateList<String>,
    hazeState: dev.chrisbanes.haze.HazeState
) {
    val receivedMockMessage by AppConnectionManager.lastReceivedMessage.collectAsState()
    val context = LocalContext.current
    val transferStatus by LanFileTransferManager.transferStatus.collectAsState()
    var fileTargetDevice by remember { mutableStateOf<UdpDevice?>(null) }
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        val device = fileTargetDevice
        val port = device?.filePort
        if (uri != null && device != null && port != null) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                val success = LanFileTransferManager.sendFile(context, device.ipAddress, port, uri)
                android.widget.Toast.makeText(
                    context,
                    if (success) "文件已发送给 ${device.deviceName}" else "文件发送失败",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
        fileTargetDevice = null
    }
    val isSystemDark = androidx.compose.foundation.isSystemInDarkTheme()

    val statusColor = when (connectionState) {
        ConnectionState.Idle -> Color.Gray
        ConnectionState.Transferring -> Color(0xFF4CAF50)
        else -> Color(0xFFFFC107)
    }
    val statusAlpha = if (connectionState == ConnectionState.Idle) 0.45f else 0.9f

    val statusText = when (connectionState) {
        ConnectionState.Idle -> "系统待机中"
        ConnectionState.AdvertisingOrScanning -> if (role == DeviceRole.Sender) "等待连接" else "寻找设备中"
        ConnectionState.Connecting -> "正在建立加密通道"
        ConnectionState.Transferring -> "已连接 · 静默同步中"
        else -> "未知状态"
    }
    val modeText = when (workMode) {
        WorkMode.Idle -> "未启动"
        WorkMode.Pairing -> "配对模式"
        WorkMode.Working -> "工作模式"
    }
    val switchColors = SwitchDefaults.colors(
        checkedThumbColor = MaterialTheme.colorScheme.primary,
        checkedTrackColor = Color.White,
        uncheckedThumbColor = Color.Gray,
        uncheckedTrackColor = Color.White.copy(alpha = 0.8f),
        uncheckedBorderColor = Color.Transparent,
        checkedBorderColor = Color.Transparent
    )
    val sortedBoundDeviceEntries = boundDeviceEntries
        .sortedWith(compareBy<BoundDeviceEntry> { it.name.lowercase() }.thenBy { it.hash ?: it.rawValue })
    val udpDevices by UdpDiscovery.discoveredDevices.collectAsState()
    val trustedDashboardHashes = remember(boundDeviceEntries) {
        boundDeviceEntries.mapNotNull { it.hash?.uppercase() }.toSet()
    }
    val explicitNotificationShareEnabled = notificationShareEnabledHashes.map { it.uppercase() }.toSet()
    val explicitNotificationShareDisabled = notificationShareDisabledHashes.map { it.uppercase() }.toSet()
    val visibleUdpDevices = remember(udpDevices, myHashStr, workMode, trustedDashboardHashes) {
        udpDevices
            .filterNot { it.hash.equals(myHashStr, ignoreCase = true) }
            .filter {
                workMode == WorkMode.Pairing ||
                    it.lanReachable ||
                    it.filePort != null ||
                    trustedDashboardHashes.contains(it.hash.uppercase())
            }
            .groupBy { it.hash.uppercase() }
            .mapNotNull { (_, devices) -> devices.maxByOrNull { it.lastSeen } }
            .sortedWith(compareBy<UdpDevice> { it.deviceName.lowercase() }.thenBy { it.hash })
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item(key = "status") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .hazeEffect(
                        state = hazeState,
                        style = HazeStyle(
                            backgroundColor = if (isSystemDark) {
                                Color.White.copy(alpha = 0.05f)
                            } else {
                                Color.White.copy(alpha = 0.55f)
                            },
                            tint = HazeTint(Color.White.copy(alpha = 0.08f)),
                            blurRadius = 18.dp
                        )
                    )
                    .background(if (isSystemDark) Color.White.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.28f))
                    .padding(14.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(statusColor.copy(alpha = statusAlpha))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    statusText,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    modeText,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = workMode != WorkMode.Idle,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    if (boundMacs.isEmpty()) {
                                        startBluetoothAction(
                                            stateMachine,
                                            blePeripheral,
                                            bleCentral,
                                            role,
                                            WorkMode.Pairing,
                                            deviceHash,
                                            boundMacs,
                                            myName
                                        )
                                    } else {
                                        startBluetoothAction(
                                            stateMachine,
                                            blePeripheral,
                                            bleCentral,
                                            role,
                                            WorkMode.Working,
                                            deviceHash,
                                            boundMacs,
                                            myName
                                        )
                                    }
                                } else {
                                    UdpDiscovery.stop()
                                    blePeripheral.stopAdvertising()
                                    blePeripheral.disconnectCurrentDevice()
                                    bleCentral.stopScanning()
                                    bleCentral.disconnect()
                                    stateMachine.setWorkMode(WorkMode.Idle)
                                    val ctx = com.suseoaa.castpigeon.shared.BleContextHolder.applicationContext
                                    if (ctx != null) {
                                        com.suseoaa.castpigeon.service.BleForegroundService.stop(ctx)
                                    }
                                }
                            },
                            colors = switchColors
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = role == DeviceRole.Sender,
                            onClick = { if (workMode == WorkMode.Idle) stateMachine.setRole(DeviceRole.Sender) },
                            enabled = workMode == WorkMode.Idle,
                            label = { Text("发送端", fontSize = 12.sp) }
                        )
                        FilterChip(
                            selected = role == DeviceRole.Receiver,
                            onClick = { if (workMode == WorkMode.Idle) stateMachine.setRole(DeviceRole.Receiver) },
                            enabled = workMode == WorkMode.Idle,
                            label = { Text("接收端", fontSize = 12.sp) }
                        )
                    }
                }
            }
        }

        transferStatus?.let { status ->
            item(key = "transfer_status") {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(14.dp)
                            .fillMaxWidth()
                    ) {
                        val phaseText = when (status.phase) {
                            LanFileTransferManager.TransferPhase.InProgress -> if (status.direction == LanFileTransferManager.TransferDirection.Sending) "正在发送文件" else "正在接收文件"
                            LanFileTransferManager.TransferPhase.Success -> if (status.direction == LanFileTransferManager.TransferDirection.Sending) "发送成功" else "接收成功"
                            LanFileTransferManager.TransferPhase.Failed -> if (status.direction == LanFileTransferManager.TransferDirection.Sending) "发送失败" else "接收失败"
                        }
                        Text(phaseText, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(status.fileName, fontSize = 13.sp, maxLines = 1)
                        Text(status.peerLabel, fontSize = 12.sp, color = Color.Gray, maxLines = 1)
                        if (status.phase == LanFileTransferManager.TransferPhase.InProgress) {
                            Spacer(modifier = Modifier.height(8.dp))
                            val progress = status.progressFraction
                            if (progress != null) {
                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("${(progress * 100).toInt()}%", fontSize = 12.sp, color = Color.Gray)
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        } else if (!status.detail.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(status.detail, fontSize = 12.sp, color = Color.Gray)
                        }
                    }
                }
            }
        }

        if (workMode == WorkMode.Pairing || visibleUdpDevices.isNotEmpty()) {
            item(key = "online_header") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("在线设备", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    if (workMode == WorkMode.Pairing && visibleUdpDevices.isEmpty()) {
                        Text("搜索中", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            if (visibleUdpDevices.isNotEmpty()) {
                items(visibleUdpDevices, key = { "online_${it.hash}" }) { device ->
                    val deviceHash = device.hash.uppercase()
                    val isBoundDevice = trustedDashboardHashes.contains(deviceHash)
                    val notificationSharingEnabled = isNotificationSharingEnabled(
                        hash = device.hash,
                        defaultEnabled = isBoundDevice,
                        enabledHashes = explicitNotificationShareEnabled,
                        disabledHashes = explicitNotificationShareDisabled
                    )
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            if (workMode == WorkMode.Pairing) {
                                UdpDiscovery.requestBinding(
                                    device.hash,
                                    device.deviceName,
                                    device.role,
                                    device.ipAddress
                                )
                            }
                        }
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        if (device.deviceType == "Android") Icons.Default.Smartphone else Icons.Default.Computer,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(device.deviceName, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    "${device.deviceType} · ${if (isBoundDevice) "已绑定" else "组内设备"} · ${if (device.lanReachable) "局域网可达" else "等待验证"} · ${device.ipAddress} · 端口 ${device.filePort ?: "不可用"}",
                                    fontSize = 12.sp,
                                    color = Color.Gray,
                                    maxLines = 2
                                )
                            }
                            Column(
                                horizontalAlignment = Alignment.End,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("通知", fontSize = 11.sp, color = Color.Gray)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Switch(
                                        checked = notificationSharingEnabled,
                                        onCheckedChange = { checked ->
                                            updateNotificationSharing(
                                                prefs = prefs,
                                                enabledHashes = notificationShareEnabledHashes,
                                                disabledHashes = notificationShareDisabledHashes,
                                                hash = device.hash,
                                                enabled = checked
                                            )
                                        },
                                        modifier = Modifier.scale(0.78f)
                                    )
                                }
                                if (device.filePort != null && device.lanReachable) {
                                    TextButton(onClick = {
                                        fileTargetDevice = device
                                        filePickerLauncher.launch("*/*")
                                    }) {
                                        Text("发送文件")
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                item(key = "online_loading") {
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("正在发现附近设备…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        item(key = "bound_devices") {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier
                        .padding(14.dp)
                        .fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("已绑定设备", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        TextButton(
                            onClick = {
                                startBluetoothAction(
                                    stateMachine,
                                    blePeripheral,
                                    bleCentral,
                                    role,
                                    WorkMode.Pairing,
                                    deviceHash,
                                    boundMacs,
                                    myName
                                )
                            },
                            enabled = workMode != WorkMode.Pairing
                        ) {
                            Text(if (workMode == WorkMode.Pairing) "配对中" else "绑定新设备")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    if (sortedBoundDeviceEntries.isEmpty()) {
                        Text(
                            "还没有绑定设备，可以先搜索附近设备完成配对。",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    } else {
                        sortedBoundDeviceEntries.forEachIndexed { index, entry ->
                            val entryHash = entry.hash?.uppercase()
                            val notificationSharingEnabled = isNotificationSharingEnabled(
                                hash = entryHash,
                                defaultEnabled = true,
                                enabledHashes = explicitNotificationShareEnabled,
                                disabledHashes = explicitNotificationShareDisabled
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(entry.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
                                    Text(
                                        entry.hash?.let {
                                            listOfNotNull(
                                                "Hash: $it",
                                                entry.deviceType.takeIf { type -> type != "Unknown" },
                                                entry.lastIp
                                            ).joinToString(" · ")
                                        } ?: "旧版绑定记录，请重新配对以获得稳定重连",
                                        fontSize = 12.sp,
                                        color = Color.Gray,
                                        maxLines = 2
                                    )
                                }
                                if (entryHash != null) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(end = 4.dp)
                                    ) {
                                        Text("通知", fontSize = 11.sp, color = Color.Gray)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Switch(
                                            checked = notificationSharingEnabled,
                                            onCheckedChange = { checked ->
                                                updateNotificationSharing(
                                                    prefs = prefs,
                                                    enabledHashes = notificationShareEnabledHashes,
                                                    disabledHashes = notificationShareDisabledHashes,
                                                    hash = entryHash,
                                                    enabled = checked
                                                )
                                            },
                                            modifier = Modifier.scale(0.78f)
                                        )
                                    }
                                }
                                IconButton(onClick = {
                                    removeBoundDeviceEntry(prefs, boundMacs, entry)
                                    if (boundMacs.isEmpty() && workMode == WorkMode.Working) {
                                        UdpDiscovery.stop()
                                        blePeripheral.stopAdvertising()
                                        blePeripheral.disconnectCurrentDevice()
                                        bleCentral.stopScanning()
                                        bleCentral.disconnect()
                                        stateMachine.setWorkMode(WorkMode.Idle)
                                        val ctx = com.suseoaa.castpigeon.shared.BleContextHolder.applicationContext
                                        if (ctx != null) {
                                            com.suseoaa.castpigeon.service.BleForegroundService.stop(ctx)
                                        }
                                    }
                                    android.widget.Toast.makeText(
                                        context,
                                        "已删除 ${entry.name}",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "删除绑定设备",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            if (index < sortedBoundDeviceEntries.lastIndex) {
                                Spacer(modifier = Modifier.height(6.dp))
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                Spacer(modifier = Modifier.height(6.dp))
                            }
                        }
                    }
                }
            }
        }

        item(key = "advanced_lab") {
            AdvancedLabCard()
        }

        if (connectionState == ConnectionState.Transferring) {
            item(key = "message_test") {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(14.dp)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (role == DeviceRole.Sender) {
                            Button(
                                onClick = {
                                    try {
                                        val notif = com.suseoaa.castpigeon.shared.NotificationMessage(
                                            id = "test_${System.currentTimeMillis()}",
                                            appName = "CastPigeon Test",
                                            title = "测试通知",
                                            content = "模拟消息：${Date()}",
                                            timestamp = System.currentTimeMillis()
                                        )
                                        val jsonStr = kotlinx.serialization.json.Json.encodeToString(notif)
                                        blePeripheral.sendNotificationData(jsonStr.encodeToByteArray())
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("发送测试通知") }
                        } else {
                            Text("最新收到消息：", fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(text = receivedMockMessage ?: "暂无")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdvancedLabCard() {
    val context = LocalContext.current
    val isPrivileged by com.suseoaa.castpigeon.service.PrivilegeManager.isPrivileged.collectAsState()
    val activeBackend by com.suseoaa.castpigeon.service.PrivilegeManager.activeBackend.collectAsState()
    val bindStatus by com.suseoaa.castpigeon.service.PrivilegeManager.bindStatus.collectAsState()
    val privilegeMode by com.suseoaa.castpigeon.service.PrivilegeManager.privilegeMode.collectAsState()
    var expanded by remember { mutableStateOf(false) }

    fun selectPrivilegeMode(mode: com.suseoaa.castpigeon.service.PrivilegeMode) {
        expanded = false
        if (mode == com.suseoaa.castpigeon.service.PrivilegeMode.DEFAULT) {
            com.suseoaa.castpigeon.service.PrivilegeManager.disable()
            android.widget.Toast.makeText(
                context,
                "已切换为默认模式",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        } else if (mode == com.suseoaa.castpigeon.service.PrivilegeMode.SHIZUKU) {
            if (!rikka.shizuku.Shizuku.pingBinder()) {
                android.widget.Toast.makeText(
                    context,
                    "Shizuku 服务未运行！请先启动 Shizuku 应用程序",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            } else if (rikka.shizuku.Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                android.widget.Toast.makeText(
                    context,
                    "正在请求 Shizuku 授权，请在弹窗中允许…",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                try {
                    rikka.shizuku.Shizuku.requestPermission(1001)
                } catch (e: Exception) {
                    android.widget.Toast.makeText(
                        context,
                        "请求授权失败: ${e.message}",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            } else {
                com.suseoaa.castpigeon.service.PrivilegeManager.executeShizukuCommand(context)
            }
        }
    }

    val modes = listOf(
        Triple(
            com.suseoaa.castpigeon.service.PrivilegeMode.DEFAULT,
            "默认模式",
            Icons.Default.Close
        ),
        Triple(
            com.suseoaa.castpigeon.service.PrivilegeMode.SHIZUKU,
            "Shizuku",
            Icons.Default.Build
        )
    )
    val selectedMode = modes.firstOrNull { it.first == privilegeMode } ?: modes.first()
    val statusText = when (privilegeMode) {
        com.suseoaa.castpigeon.service.PrivilegeMode.DEFAULT -> "未开启后台提权同步"
        com.suseoaa.castpigeon.service.PrivilegeMode.SHIZUKU -> {
            when (bindStatus) {
                com.suseoaa.castpigeon.service.PrivilegeManager.BindStatus.Binding -> "正在连接 Shizuku…"
                com.suseoaa.castpigeon.service.PrivilegeManager.BindStatus.Connected -> "Shizuku 提权已生效"
                com.suseoaa.castpigeon.service.PrivilegeManager.BindStatus.Failed -> "Shizuku 授权失败"
                else -> "已选择 Shizuku 模式"
            }
        }
    }
    val backendText = when (activeBackend) {
        com.suseoaa.castpigeon.service.ActivePrivilegeBackend.NONE -> "当前实际后端：无"
        com.suseoaa.castpigeon.service.ActivePrivilegeBackend.SHIZUKU -> "当前实际后端：Shizuku"
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
        ) {
            Text(
                "高级实验室",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("真·后台剪贴板", fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.width(8.dp))
                        if (isPrivileged) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF4CAF50),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    Text(
                        text = statusText,
                        fontSize = 12.sp,
                        color = if (isPrivileged) Color(0xFF4CAF50) else Color.Gray
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = backendText,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(12.dp))

            Box(modifier = Modifier.fillMaxWidth()) {
                Surface(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                    tonalElevation = 1.dp,
                    shadowElevation = 0.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            selectedMode.third,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "提权模式",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                selectedMode.second,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .background(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(14.dp)
                        )
                ) {
                    modes.forEach { (mode, label, icon) ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        icon,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = if (mode == privilegeMode) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        label,
                                        fontWeight = if (mode == privilegeMode) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            },
                            onClick = { selectPrivilegeMode(mode) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsContent() {
    val apps by AppManager.appList.collectAsState()
    val showSystemApps by AppManager.showSystemApps.collectAsState()
    var appSearchQuery by remember { mutableStateOf("") }
    val filteredApps = remember(apps, appSearchQuery) {
        val query = appSearchQuery.trim()
        if (query.isEmpty()) {
            apps
        } else {
            apps.filter { app ->
                app.appName.contains(query, ignoreCase = true) ||
                    app.packageName.contains(query, ignoreCase = true)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            Text("控制台", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "应用同步设置",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("显示系统应用", fontWeight = FontWeight.Medium)
                Text(
                    "默认隐藏系统应用，打开后显示完整应用列表",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Switch(
                checked = showSystemApps,
                onCheckedChange = { AppManager.setShowSystemApps(it) }
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = appSearchQuery,
            onValueChange = { appSearchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            singleLine = true,
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null
                )
            },
            trailingIcon = {
                if (appSearchQuery.isNotEmpty()) {
                    IconButton(onClick = { appSearchQuery = "" }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "清空搜索"
                        )
                    }
                }
            },
            placeholder = { Text("搜索应用名称或包名") },
            shape = RoundedCornerShape(16.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))

        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        if (apps.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (filteredApps.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "没有找到匹配的应用",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                items(filteredApps, key = { it.packageName }) { app ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppIcon(
                                packageName = app.packageName,
                                appName = app.appName,
                                modifier = Modifier.size(44.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    app.appName,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 16.sp,
                                    maxLines = 1
                                )
                                Text(
                                    app.packageName,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }
                        Switch(
                            checked = app.isSelected,
                            onCheckedChange = { checked ->
                                AppManager.updateAppSelection(app.packageName, checked)
                            }
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 24.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                }
            }
        }
    }
}

@Composable
fun InfoContent() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var updateInfo by remember { mutableStateOf<AppUpdateManager.UpdateInfo?>(null) }
    var historyReleases by remember { mutableStateOf<List<AppUpdateManager.ReleaseInfo>>(emptyList()) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var historyMessage by remember { mutableStateOf<String?>(null) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var isLoadingHistory by remember { mutableStateOf(false) }
    val downloadStates = remember { mutableStateMapOf<String, ReleaseDownloadState>() }
    val currentVersion = remember { AppUpdateManager.currentVersionName(context) }

    fun checkUpdate(showNoUpdateToast: Boolean) {
        if (isCheckingUpdate) return
        isCheckingUpdate = true
        updateMessage = null
        scope.launch {
            val result = AppUpdateManager.checkForUpdate(context.applicationContext)
            isCheckingUpdate = false
            result
                .onSuccess { info ->
                    updateInfo = info
                    if (info == null && showNoUpdateToast) {
                        android.widget.Toast.makeText(context, "当前已是最新版本", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    if (info == null) {
                        updateMessage = "当前没有可用更新"
                    }
                }
                .onFailure { error ->
                    updateMessage = error.message ?: "检查更新失败"
                }
        }
    }

    fun loadHistory() {
        if (isLoadingHistory) return
        isLoadingHistory = true
        historyMessage = null
        scope.launch {
            AppUpdateManager.getHistoryReleases()
                .onSuccess { releases ->
                    historyReleases = releases
                    historyMessage = if (releases.isEmpty()) "暂无历史版本" else null
                }
                .onFailure { error ->
                    historyMessage = error.message ?: "获取历史更新失败"
                }
            isLoadingHistory = false
        }
    }

    fun startDownload(release: AppUpdateManager.ReleaseInfo) {
        val isOtherDownloading = downloadStates.values.any { it.progress in 0 until 100 || it.isVerifying }
        if (isOtherDownloading) {
            android.widget.Toast.makeText(context, "已有安装包正在下载", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val downloadId = AppUpdateManager.enqueueApkDownload(context.applicationContext, release)
        downloadStates[release.tagName] = ReleaseDownloadState(downloadId = downloadId, progress = 0)

        scope.launch {
            var progress = 0
            while (progress in 0 until 100) {
                kotlinx.coroutines.delay(600)
                progress = AppUpdateManager.getDownloadProgress(context.applicationContext, downloadId)
                downloadStates[release.tagName] = downloadStates[release.tagName]
                    ?.copy(progress = progress.coerceAtLeast(0))
                    ?: ReleaseDownloadState(downloadId = downloadId, progress = progress.coerceAtLeast(0))
            }

            if (progress >= 100) {
                downloadStates[release.tagName] = downloadStates[release.tagName]?.copy(isVerifying = true)
                    ?: ReleaseDownloadState(downloadId = downloadId, progress = 100, isVerifying = true)

                // 下载完成后按 GitHub Release 提供的 digest 做完整性校验，避免代理或网络异常产生坏包。
                val isValid = AppUpdateManager.verifyDownloadedApk(
                    context.applicationContext,
                    downloadId,
                    release.asset.digest
                )
                downloadStates[release.tagName] = downloadStates[release.tagName]?.copy(
                    progress = 100,
                    isVerifying = false,
                    isVerified = isValid,
                    message = if (isValid) "下载完成，可以安装" else "安装包校验失败，请重新下载"
                ) ?: ReleaseDownloadState(
                    downloadId = downloadId,
                    progress = 100,
                    isVerified = isValid,
                    message = if (isValid) "下载完成，可以安装" else "安装包校验失败，请重新下载"
                )
            }
        }
    }

    fun installRelease(release: AppUpdateManager.ReleaseInfo) {
        val downloadId = downloadStates[release.tagName]?.downloadId
        if (downloadId == null) {
            android.widget.Toast.makeText(context, "未找到已下载的安装包", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val started = AppUpdateManager.installDownloadedApk(context.applicationContext, downloadId)
        if (!started) {
            android.widget.Toast.makeText(context, "未找到已下载的安装包", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        checkUpdate(showNoUpdateToast = false)
        loadHistory()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "title") {
            Text("信息", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }

        item(key = "about") {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("CastPigeon", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        "面向 Android 与 macOS 的近场同步工具，用蓝牙与局域网通道完成设备绑定、通知同步、剪贴板同步和文件传输。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "当前版本 $currentVersion",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item(key = "update") {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("自动更新", fontWeight = FontWeight.Medium)
                            Text(
                                "从 GitHub Release 检查 Android 安装包和更新日志",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(
                            onClick = { checkUpdate(showNoUpdateToast = true) },
                            enabled = !isCheckingUpdate
                        ) {
                            Text(if (isCheckingUpdate) "检查中" else "检查更新")
                        }
                    }

                    updateInfo?.let { info ->
                        ReleaseUpdateCard(
                            title = "发现新版本 ${info.latestRelease.versionName}",
                            release = info.latestRelease,
                            markdown = info.mergedBody,
                            downloadState = downloadStates[info.latestRelease.tagName] ?: ReleaseDownloadState(),
                            onDownload = { startDownload(info.latestRelease) },
                            onInstall = { installRelease(info.latestRelease) }
                        )
                        if (info.includedReleases.size > 1) {
                            Text(
                                "已合并 ${info.includedReleases.size} 个版本的更新日志",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } ?: Text(
                        updateMessage ?: if (isCheckingUpdate) "正在检查更新..." else "当前没有可用更新",
                        fontSize = 12.sp,
                        color = updateMessage?.let { MaterialTheme.colorScheme.error }
                            ?: MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item(key = "history-title") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("历史更新", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                TextButton(
                    onClick = { loadHistory() },
                    enabled = !isLoadingHistory
                ) {
                    Text(if (isLoadingHistory) "加载中" else "刷新")
                }
            }
        }

        if (historyMessage != null) {
            item(key = "history-message") {
                Text(
                    historyMessage.orEmpty(),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        items(
            items = historyReleases,
            key = { release -> "release-${release.tagName}" }
        ) { release ->
            ReleaseUpdateCard(
                title = "CastPigeon Android ${release.versionName}",
                release = release,
                markdown = release.body,
                downloadState = downloadStates[release.tagName] ?: ReleaseDownloadState(),
                onDownload = { startDownload(release) },
                onInstall = { installRelease(release) }
            )
        }
    }
}

private data class ReleaseDownloadState(
    val downloadId: Long? = null,
    val progress: Int = -1,
    val isVerifying: Boolean = false,
    val isVerified: Boolean = false,
    val message: String? = null
)

@Composable
private fun ReleaseUpdateCard(
    title: String,
    release: AppUpdateManager.ReleaseInfo,
    markdown: String,
    downloadState: ReleaseDownloadState,
    onDownload: () -> Unit,
    onInstall: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Text(
                release.asset.assetName,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            CastPigeonMarkdown(markdown = markdown)

            if (downloadState.progress in 0 until 100 || downloadState.isVerifying) {
                LinearProgressIndicator(
                    progress = { (downloadState.progress.coerceIn(0, 100)) / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    if (downloadState.isVerifying) "正在校验安装包..." else "${downloadState.progress.coerceIn(0, 100)}%",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            downloadState.message?.let { message ->
                Text(
                    message,
                    fontSize = 12.sp,
                    color = if (downloadState.isVerified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onDownload,
                    enabled = downloadState.progress !in 0 until 100 && !downloadState.isVerifying
                ) {
                    Text(if (downloadState.progress == 100) "重新下载 APK" else "下载 APK")
                }
                if (downloadState.isVerified) {
                    OutlinedButton(onClick = onInstall) {
                        Text("安装")
                    }
                }
            }
        }
    }
}

@Composable
private fun CastPigeonMarkdown(
    markdown: String,
    modifier: Modifier = Modifier
) {
    if (markdown.isBlank()) {
        Text(
            "暂无更新日志",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    // GitHub Release 使用 Markdown 编写，这里保留标题、列表和链接的层级关系。
    Markdown(
        content = markdown,
        modifier = modifier.fillMaxWidth(),
        typography = markdownTypography(
            h1 = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            h2 = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            h3 = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            h4 = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            paragraph = MaterialTheme.typography.bodySmall,
            bullet = MaterialTheme.typography.bodySmall,
            list = MaterialTheme.typography.bodySmall,
            quote = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic)
        ),
        colors = markdownColor(
            text = MaterialTheme.colorScheme.onSurface,
            codeBackground = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
            inlineCodeBackground = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
        )
    )
}

private fun startBluetoothAction(
    stateMachine: ConnectionStateMachine,
    blePeripheral: BlePeripheral,
    bleCentral: BleCentral,
    role: DeviceRole,
    mode: WorkMode,
    deviceHash: ByteArray,
    boundMacs: List<String>,
    androidName: String
) {
    stateMachine.setWorkMode(mode)
    stateMachine.transitionTo(ConnectionState.AdvertisingOrScanning)
    val context = com.suseoaa.castpigeon.shared.BleContextHolder.applicationContext
    val hashStr = deviceHash.joinToString("") { "%02X".format(it) }
    if (context != null) {
        blePeripheral.onPeerAuthorizationRequested = { peerName, peerHash ->
            BoundDeviceStore.authorizeOrMigratePeer(context, peerName, peerHash).also { accepted ->
                if (accepted) {
                    blePeripheral.updateTrustedPeerHashes(BoundDeviceStore.getHashes(context))
                }
            }
        }
    }
	    val filePort = com.suseoaa.castpigeon.service.LanFileTransferManager.serverPort.value
        val trustedHashes = boundMacs.mapNotNull { parseBoundDeviceEntry(it).hash }.toSet()
	    UdpDiscovery.startBroadcasting(
            role.name,
            androidName,
            hashStr,
            filePort,
            "Android",
            pairingMode = mode == WorkMode.Pairing,
            trustedHashes = trustedHashes
        )

    if (mode == WorkMode.Working) {
        // 启动前台服务
        if (context != null) {
            com.suseoaa.castpigeon.service.BleForegroundService.start(context)
        }

	        if (role == DeviceRole.Sender) {
	            try {
                    blePeripheral.updateTrustedPeerHashes(trustedHashes)
	                blePeripheral.startAdvertising(mode, deviceHash) { newState, name ->
	                    stateMachine.transitionTo(newState, name)
	                }
            } catch (e: SecurityException) {
                android.util.Log.e("CastPigeonUI", "蓝牙权限不足，无法开始广播: ${e.message}")
                stateMachine.transitionTo(ConnectionState.Idle)
                android.widget.Toast.makeText(
                    context,
                    "蓝牙权限不足，请在系统设置中授予权限后重试",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                android.util.Log.e("CastPigeonUI", "startAdvertising 失败", e)
                stateMachine.transitionTo(ConnectionState.Idle)
            }
	        } else {
	            try {
	                val targetHashes = boundMacs.mapNotNull {
	                    val hashStr = parseBoundDeviceEntry(it).hash
	                    if (!hashStr.isNullOrEmpty()) hashStr.chunked(2)
	                        .map { hex -> hex.toInt(16).toByte() }.toByteArray() else null
	                }.toSet()
                bleCentral.startScanning(mode, targetHashes) { newState, name ->
                    stateMachine.transitionTo(newState, name)
                }
            } catch (e: SecurityException) {
                android.util.Log.e("CastPigeonUI", "蓝牙权限不足，无法开始扫描: ${e.message}")
                stateMachine.transitionTo(ConnectionState.Idle)
            } catch (e: Exception) {
                android.util.Log.e("CastPigeonUI", "startScanning 失败", e)
                stateMachine.transitionTo(ConnectionState.Idle)
            }
        }
    }
}
