@file:Suppress("UNUSED_VARIABLE", "UNUSED_PARAMETER", "USELESS_CAST", "RedundantRequireNotNullCall", "RemoveRedundantQualifierName", "UNUSED_IMPORT", "CanBeVal")
package com.suseoaa.castpigeon.ui

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import android.content.Intent
import com.suseoaa.castpigeon.shared.*
import com.suseoaa.castpigeon.shared.network.*
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.Date
import com.suseoaa.castpigeon.AppManager
import com.suseoaa.castpigeon.shared.crypto.Crypto
import kotlinx.serialization.encodeToString
import com.suseoaa.castpigeon.service.AppConnectionManager
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

// 底部导航项
enum class AppTab(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Dashboard("状态看板", Icons.Default.Home),
    History("历史记录", Icons.Default.History),
    Settings("同步设置", Icons.Default.Settings)
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
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(initialPage = AppTab.entries.indexOf(currentTab), pageCount = { AppTab.entries.size })
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
        val androidId = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "unknown"
        val bytes = MessageDigest.getInstance("SHA-256").digest(androidId.toByteArray())
        bytes.copyOfRange(0, 4)
    }

    // 本地持久化信任的Mac列表
    val prefs = remember { context.getSharedPreferences("CastPigeonPrefs", Context.MODE_PRIVATE) }
    val boundMacs = remember { 
        mutableStateListOf<String>().apply { 
            addAll(prefs.getStringSet("BoundMacs", emptySet()) ?: emptySet()) 
        } 
    }
    
    val myName = remember { android.provider.Settings.Global.getString(context.contentResolver, android.provider.Settings.Global.DEVICE_NAME) ?: "Android Device" }
    val myHashStr = remember(deviceHash) { deviceHash.joinToString("") { "%02X".format(it) } }

    var pinDisplayInfo by remember { mutableStateOf<PinDisplayInfo?>(null) }
    var pinInputDevice by remember { mutableStateOf<UdpDevice?>(null) }

    LaunchedEffect(Unit) {
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

    LaunchedEffect(workMode, role) {
        if (workMode == WorkMode.Pairing && role == DeviceRole.Sender) {
            UdpDiscovery.pairingSuccessEvent.collect { boundDevice ->
                val entry = "${boundDevice.deviceName}|${boundDevice.hash}"
                val newSet = boundMacs.toSet() + entry
                prefs.edit { putStringSet("BoundMacs", newSet) }
                if (!boundMacs.contains(entry)) boundMacs.add(entry)
                UdpDiscovery.stop()
                stateMachine.setWorkMode(WorkMode.Idle)
                pinDisplayInfo = null
                pinInputDevice = null
                android.widget.Toast.makeText(context, "已成功被 ${boundDevice.deviceName} 绑定！", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    val receivedMockMessage by com.suseoaa.castpigeon.service.AppConnectionManager.lastReceivedMessage.collectAsState()

    // 监听全局通知事件并发送 (已迁移至 BleForegroundService，此处移除以避免重复)

    // 权限请求启动器
    val permissionsToRequest = arrayOf(
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.POST_NOTIFICATIONS
    )
    
    fun hasAllPermissions(): Boolean {
        return permissionsToRequest.all {
            androidx.core.content.ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }
    
    // 触发动作状态
    var pendingAction by remember { mutableStateOf<WorkMode?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        android.util.Log.i("CastPigeonUI", "权限请求结果: allGranted=$allGranted, permissions=$permissions")
        if (allGranted && pendingAction != null) {
            val targetMode = pendingAction!!
            pendingAction = null
            startBluetoothAction(stateMachine, blePeripheral, bleCentral, role, targetMode, deviceHash, boundMacs, myName)
        } else if (!allGranted) {
            android.widget.Toast.makeText(context, "需要蓝牙相关权限才能工作！", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // Role 持久化与自动启动
    LaunchedEffect(Unit) {
        val lastRoleStr = prefs.getString("LastRole", DeviceRole.Sender.name) ?: DeviceRole.Sender.name
        try {
            stateMachine.setRole(DeviceRole.valueOf(lastRoleStr))
        } catch (_: Exception) {}

        // 每次启动都无条件检查并请求所有必需权限
        // 首次安装或重装后权限被撤销时均会弹出权限申请对话框
        if (boundMacs.isNotEmpty()) {
            pendingAction = WorkMode.Working
        }
        permissionLauncher.launch(permissionsToRequest)
    }

    LaunchedEffect(role) {
        prefs.edit { putString("LastRole", role.name) }
    }

    // 处理握手配对弹窗(Android作为Peripheral接收配对请求时)
    if (connectionState == ConnectionState.PairingRequest && role == DeviceRole.Sender) {
        val macName = pairingDeviceName ?: "Unknown Device"
        if (workMode == WorkMode.Working && boundMacs.any { it.startsWith("$macName|") }) {
            LaunchedEffect(macName) {
                stateMachine.transitionTo(ConnectionState.Transferring)
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
                        val entry = "$macName|"
                        val newSet = boundMacs.toSet() + entry
                        prefs.edit { putStringSet("BoundMacs", newSet) }
                        if (!boundMacs.contains(entry)) boundMacs.add(entry)
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
                    Text(info.pin, fontSize = 36.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = { pinDisplayInfo = null; UdpDiscovery.stop(); stateMachine.setWorkMode(WorkMode.Idle) }) { Text("取消") }
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
                TextButton(onClick = { pinInputDevice = null; UdpDiscovery.stop(); stateMachine.setWorkMode(WorkMode.Idle) }) { Text("取消") }
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
                Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
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
                    myName = myName,
                    prefs = prefs,
                    hazeState = hazeState
                )
                        AppTab.History -> HistoryScreen()
                        AppTab.Settings -> SettingsContent()
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
    boundMacs: MutableList<String>,
    myName: String,
    prefs: android.content.SharedPreferences,
    hazeState: dev.chrisbanes.haze.HazeState
) {
    val receivedMockMessage by AppConnectionManager.lastReceivedMessage.collectAsState()
    val isSystemDark = androidx.compose.foundation.isSystemInDarkTheme()
    
    val infiniteTransition = rememberInfiniteTransition(label = "breathing")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathingAlpha"
    )

    val statusColor = when (connectionState) {
        ConnectionState.Idle -> Color.Gray
        ConnectionState.Transferring -> Color(0xFF4CAF50)
        else -> Color(0xFFFFC107)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .hazeEffect(
                    state = hazeState,
                    style = HazeStyle(backgroundColor = if (isSystemDark) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.05f) else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.6f), tint = dev.chrisbanes.haze.HazeTint(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.1f)), blurRadius = 30.dp)
                )
                .background(if (isSystemDark) Color.White.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.3f))
                .padding(24.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(statusColor.copy(alpha = alpha))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when (connectionState) {
                            ConnectionState.Idle -> "系统待机中"
                            ConnectionState.AdvertisingOrScanning -> if (role == DeviceRole.Sender) "等待 Mac 连接" else "寻找 Mac 中"
                            ConnectionState.Connecting -> "正在建立加密通道"
                            ConnectionState.Transferring -> "已连接 Mac - 静默监控中"
                            else -> "未知状态"
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                
                val switchColors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.primary,
                    checkedTrackColor = Color.White,
                    uncheckedThumbColor = Color.Gray,
                    uncheckedTrackColor = Color.White.copy(alpha = 0.8f),
                    uncheckedBorderColor = Color.Transparent,
                    checkedBorderColor = Color.Transparent
                )

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                    Text("服务状态", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.width(16.dp))
                    Switch(
                        checked = workMode != WorkMode.Idle,
                        onCheckedChange = { checked ->
                            if (checked) {
                                if (boundMacs.isEmpty()) {
                                    startBluetoothAction(stateMachine, blePeripheral, bleCentral, role, WorkMode.Pairing, deviceHash, boundMacs, myName)
                                } else {
                                    startBluetoothAction(stateMachine, blePeripheral, bleCentral, role, WorkMode.Working, deviceHash, boundMacs, myName)
                                }
                            } else {
                                UdpDiscovery.stop()
                                blePeripheral.stopAdvertising()
                                blePeripheral.disconnectCurrentDevice()
                                bleCentral.stopScanning()
                                bleCentral.disconnect()
                                stateMachine.setWorkMode(WorkMode.Idle)
                                val ctx = com.suseoaa.castpigeon.shared.BleContextHolder.applicationContext
                                if (ctx != null) com.suseoaa.castpigeon.service.BleForegroundService.stop(ctx)
                            }
                        },
                        colors = switchColors
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (workMode == WorkMode.Pairing) {
            val udpDevices by UdpDiscovery.discoveredDevices.collectAsState()
            Text("在线设备", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Start))
            Spacer(modifier = Modifier.height(8.dp))
            if (udpDevices.isNotEmpty()) {
                LazyColumn {
                    items(udpDevices.toList()) { device ->
                        ElevatedCard(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            onClick = {
                                UdpDiscovery.requestBinding(device.hash, device.deviceName, role.name, device.ipAddress)
                            }
                        ) {
                            Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column {
                                    Text(device.deviceName, fontWeight = FontWeight.Bold)
                                    Text("IP: ${device.ipAddress}", fontSize = 12.sp, color = Color.Gray)
                                }
                                Icon(Icons.Default.Computer, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        } else if (connectionState == ConnectionState.Transferring) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
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

@Composable
fun SettingsContent() {
    val apps by AppManager.appList.collectAsState()
    val context = LocalContext.current
    val isPrivileged by com.suseoaa.castpigeon.service.PrivilegeManager.isPrivileged.collectAsState()
    
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 24.dp, vertical = 16.dp)) {
            Text("控制台", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text("高级实验室", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("真·后台剪贴板", fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(8.dp))
                            if (isPrivileged) {
                                Icon(Icons.Default.Settings, contentDescription = null, tint = Color(0xFF4CAF50), modifier = Modifier.size(16.dp))
                            }
                        }
                        Text(
                            text = if (isPrivileged) "已获得最高权限，极致静默" else "点击开启纯静默剪贴板同步",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                    val bindStatus by com.suseoaa.castpigeon.service.PrivilegeManager.bindStatus.collectAsState()
                    Switch(
                        checked = isPrivileged,
                        enabled = bindStatus != com.suseoaa.castpigeon.service.PrivilegeManager.BindStatus.Binding,
                        onCheckedChange = { checked ->
                            if (checked && !isPrivileged) {
                                val started = com.suseoaa.castpigeon.service.PrivilegeManager.executeAppOpsCommand(context)
                                if (started) {
                                    android.widget.Toast.makeText(context, "正在连接 Root 守护进程，请稍候（10s 内）…", android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    android.widget.Toast.makeText(
                                        context,
                                        "Root 不可用！请在 KSU/APatch/Magisk 管理器中先授予本应用 Root 权限，然后重试",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                }
                            } else if (!checked && isPrivileged) {
                                com.suseoaa.castpigeon.service.PrivilegeManager.disable()
                                android.widget.Toast.makeText(context, "已关闭真·静默模式", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                    // 绑定状态提示文字
                    if (bindStatus == com.suseoaa.castpigeon.service.PrivilegeManager.BindStatus.Binding) {
                        androidx.compose.material3.Text(
                            "连接中…",
                            fontSize = 10.sp,
                            color = Color(0xFFFF9800)
                        )
                    } else if (bindStatus == com.suseoaa.castpigeon.service.PrivilegeManager.BindStatus.Failed && !isPrivileged) {
                        androidx.compose.material3.Text(
                            "连接失败，请在管理器中授权",
                            fontSize = 10.sp,
                            color = Color(0xFFE53935)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text("应用同步设置", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 24.dp))
        Spacer(modifier = Modifier.height(8.dp))
        
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        if (apps.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 80.dp)) {
                items(apps, key = { it.packageName }) { app ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(app.appName, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1)
                            Text(app.packageName, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        Switch(
                            checked = app.isSelected,
                            onCheckedChange = { checked ->
                                AppManager.updateAppSelection(app.packageName, checked)
                            }
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                }
            }
        }
    }
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
    
    if (mode == WorkMode.Pairing) {
        val hashStr = deviceHash.joinToString("") { "%02X".format(it) }
        if (role == DeviceRole.Sender) {
            UdpDiscovery.startBroadcasting(role.name, androidName, hashStr)
        } else {
            UdpDiscovery.startListening()
        }
    } else {
        // 启动前台服务
        val context = com.suseoaa.castpigeon.shared.BleContextHolder.applicationContext
        if (context != null) {
            com.suseoaa.castpigeon.service.BleForegroundService.start(context)
        }
        
        if (role == DeviceRole.Sender) {
            try {
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
                    val hashStr = it.substringAfter("|", "")
                    if (hashStr.isNotEmpty()) hashStr.chunked(2).map { hex -> hex.toInt(16).toByte() }.toByteArray() else null
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
