import re

with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/ui/MainScreen.kt', 'r') as f:
    content = f.read()

# Replace DashboardContent
dashboard_start = content.find("@Composable\nfun DashboardContent")
settings_start = content.find("@Composable\nfun SettingsContent")

if dashboard_start != -1 and settings_start != -1:
    before_dashboard = content[:dashboard_start]
    
    new_dashboard = """@Composable
fun DashboardContent(
    stateMachine: ConnectionStateMachine,
    blePeripheral: BlePeripheral,
    bleCentral: BleCentral,
    connectionState: ConnectionState,
    role: DeviceRole,
    workMode: WorkMode,
    pairingDeviceName: String,
    connectedDeviceName: String,
    deviceHash: ByteArray,
    boundMacs: MutableList<String>,
    myName: String,
    prefs: android.content.SharedPreferences,
    hazeState: dev.chrisbanes.haze.HazeState
) {
    val receivedMockMessage by AppConnectionManager.lastReceivedMessage.collectAsState()
    val isSystemDark = androidx.compose.foundation.isSystemInDarkTheme()
    
    // 呼吸灯动画
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

    // 状态呼吸灯颜色
    val statusColor = when (connectionState) {
        ConnectionState.Idle -> Color.Gray
        ConnectionState.Transferring -> Color(0xFF4CAF50) // Green
        else -> Color(0xFFFFC107) // Yellow for pairing/advertising
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 主状态毛玻璃卡片
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .dev.chrisbanes.haze.hazeChild(
                    state = hazeState,
                    style = dev.chrisbanes.haze.HazeStyle(
                        tint = if (isSystemDark) Color.White.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.6f),
                        blurRadius = 30.dp
                    )
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
                            ConnectionState.Pairing -> "正在寻找发送端"
                            ConnectionState.AdvertisingOrScanning -> if (role == DeviceRole.Sender) "等待 Mac 连接" else "寻找 Mac 中"
                            ConnectionState.Connecting -> "正在建立加密通道"
                            ConnectionState.Transferring -> "已连接 Mac - 静默监控中"
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                
                // 自定义 Switch，白色背景
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

        // 设备列表/测试消息
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
                                        title = "测试推送",
                                        content = "这是一条模拟通知：${Date()}",
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
"""

    settings_end = content.find("private fun startBluetoothAction", settings_start)
    if settings_end != -1:
        new_settings = """@Composable
fun SettingsContent() {
    val apps by AppManager.appList.collectAsState()
    val context = LocalContext.current
    val isPrivileged by com.suseoaa.castpigeon.service.PrivilegeManager.isPrivileged.collectAsState()
    
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 24.dp, vertical = 16.dp)) {
            Text("控制台", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        
        // 高级实验室提权设置
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
                            text = if (isPrivileged) "已获得 Root/Shizuku 权限，极致静默" else "点击开启纯静默剪贴板同步",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                    Switch(
                        checked = isPrivileged,
                        onCheckedChange = { checked ->
                            if (checked && !isPrivileged) {
                                val success = com.suseoaa.castpigeon.service.PrivilegeManager.executeAppOpsCommand()
                                if (success) {
                                    android.widget.Toast.makeText(context, "提权成功！已开启真·静默模式", android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    android.widget.Toast.makeText(context, "提权失败，请检查是否已授权 Root 或启动 Shizuku", android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    )
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
"""
        after_settings = content[settings_end:]
        
        with open('/Users/vincent/Desktop/CastPigeon/androidApp/src/main/kotlin/com/suseoaa/castpigeon/ui/MainScreen.kt', 'w') as f:
            f.write(before_dashboard + new_dashboard + "\n" + new_settings + "\n" + after_settings)
        print("Patched MainScreen.kt")
else:
    print("Could not find boundaries")
