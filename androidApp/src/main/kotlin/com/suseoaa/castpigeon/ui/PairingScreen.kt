package com.suseoaa.castpigeon.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suseoaa.castpigeon.network.ConnectionState
import com.suseoaa.castpigeon.network.DiscoveredDevice
import com.suseoaa.castpigeon.network.LogEntry
import com.suseoaa.castpigeon.network.NetworkService
import com.suseoaa.castpigeon.network.NetworkServiceDelegate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen() {
    val context = LocalContext.current
    val service = remember { NetworkService(context) }
    val listState = rememberLazyListState()
    val scrollState = rememberScrollState()

    var state by remember { mutableStateOf(ConnectionState.IDLE) }
    var devices by remember { mutableStateOf<List<DiscoveredDevice>>(emptyList()) }
    var messages by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    var sendText by remember { mutableStateOf("") }
    var manualIP by remember { mutableStateOf("") }
    var manualPort by remember { mutableStateOf("9876") }
    var simApp by remember { mutableStateOf("WeChat") }
    var simTitle by remember { mutableStateOf("New Message") }
    var simContent by remember { mutableStateOf("Hello from Android!") }
    var isScanning by remember { mutableStateOf(false) }
    var pairRequestFrom by remember { mutableStateOf<String?>(null) }
    var cardsExpanded by remember { mutableStateOf(true) }

    DisposableEffect(Unit) {
        service.delegate = object : NetworkServiceDelegate {
            override fun onStateChanged(s: ConnectionState) { state = s }
            override fun onDeviceDiscovered(d: DiscoveredDevice) { devices = devices.filter { it.id != d.id } + d }
            override fun onDeviceRemoved(d: DiscoveredDevice) { devices = devices.filter { it.id != d.id } }
            override fun onMessageReceived(e: LogEntry) { messages = messages + e }
            override fun onPairRequestReceived(from: String) { pairRequestFrom = from }
        }
        onDispose { service.stopAll() }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    val connected = state == ConnectionState.CONNECTED || state == ConnectionState.PAIRED

    if (pairRequestFrom != null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Pairing Request") },
            text = { Text("$pairRequestFrom wants to pair with you.") },
            confirmButton = { TextButton(onClick = { service.acceptPairRequest(); pairRequestFrom = null }) { Text("Accept") } },
            dismissButton = { TextButton(onClick = { service.rejectPairRequest(); pairRequestFrom = null }) { Text("Reject") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CastPigeon", fontWeight = FontWeight.Bold) },
                actions = {
                    TextButton(onClick = { cardsExpanded = !cardsExpanded }) {
                        Text(if (cardsExpanded) "Collapse" else "Expand", fontSize = 12.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 10.dp)) {
            // Cards area - collapsible + scrollable
            if (cardsExpanded) {
                Column(
                    modifier = Modifier
                        .heightIn(max = 240.dp)
                        .verticalScroll(scrollState)
                ) {
                    // Status & Scan
                    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(
                                    when (state) {
                                        ConnectionState.PAIRED -> Color.Green
                                        ConnectionState.CONNECTED -> Color.Blue
                                        ConnectionState.BROWSING, ConnectionState.BROADCASTING -> Color(0xFFFFA500)
                                        ConnectionState.CONNECTING -> Color.Yellow
                                        else -> Color.Gray
                                    }
                                ))
                                Spacer(Modifier.width(6.dp))
                                Text(state.label, fontSize = 13.sp)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(onClick = {
                                    if (isScanning) { service.stopAll(); isScanning = false }
                                    else { service.startBrowsing(); service.startBroadcasting(); service.startServer(); isScanning = true }
                                }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                                    Text(if (isScanning) "Stop" else "Start Scan", fontSize = 13.sp)
                                }
                                if (!isScanning && connected) {
                                    OutlinedButton(onClick = { service.stopAll(); isScanning = false }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) { Text("Disconnect", fontSize = 13.sp) }
                                }
                                if (connected) {
                                    OutlinedButton(onClick = { service.sendPing() }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) { Text("Ping", fontSize = 13.sp) }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))

                    // Devices
                    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Discovered Devices", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            if (!isScanning && devices.isEmpty()) Text("Press 'Start Scan'", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else if (isScanning && devices.isEmpty()) Text("Scanning...", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            devices.forEach { dev ->
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(dev.name, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                        Text("${dev.host}:${dev.port}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    OutlinedButton(onClick = { service.connectToHost(dev.host, dev.port) }, enabled = !connected, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) { Text("Connect", fontSize = 12.sp) }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))

                    // Manual IP
                    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Manual Connect", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                OutlinedTextField(value = manualIP, onValueChange = { manualIP = it }, label = { Text("IP") }, singleLine = true, modifier = Modifier.weight(1f), textStyle = LocalTextStyle.current.copy(fontSize = 13.sp))
                                OutlinedTextField(value = manualPort, onValueChange = { manualPort = it }, label = { Text("Port") }, singleLine = true, modifier = Modifier.width(70.dp), textStyle = LocalTextStyle.current.copy(fontSize = 13.sp))
                                OutlinedButton(onClick = { service.connectToHost(manualIP.trim(), manualPort.toIntOrNull() ?: 9876) }, enabled = manualIP.isNotBlank(), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) { Text("Connect", fontSize = 12.sp) }
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))

                    // Simulate
                    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Simulate Notification", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            OutlinedTextField(value = simApp, onValueChange = { simApp = it }, label = { Text("App Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), textStyle = LocalTextStyle.current.copy(fontSize = 13.sp))
                            OutlinedTextField(value = simTitle, onValueChange = { simTitle = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth(), textStyle = LocalTextStyle.current.copy(fontSize = 13.sp))
                            OutlinedTextField(value = simContent, onValueChange = { simContent = it }, label = { Text("Content") }, singleLine = true, modifier = Modifier.fillMaxWidth(), textStyle = LocalTextStyle.current.copy(fontSize = 13.sp))
                            Button(onClick = { service.sendSimulatedNotification(simApp, simTitle, simContent) }, enabled = connected, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 6.dp)) { Text("Send Notification", fontSize = 13.sp) }
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text("Messages", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }

            Spacer(Modifier.height(4.dp))

            // Messages (always visible, takes remaining space)
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(6.dp)).padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (messages.isEmpty()) item { Text("Start scan and connect to a device", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(16.dp)) }
                items(messages) { msg ->
                    if (msg.isSystem) Text(msg.text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(4.dp))
                    else Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (msg.direction == LogEntry.Direction.RECEIVED) Arrangement.Start else Arrangement.End) {
                        Surface(shape = RoundedCornerShape(6.dp), color = if (msg.direction == LogEntry.Direction.RECEIVED) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.widthIn(max = 260.dp)) {
                            Text("[${msg.sender}] ${msg.text}", modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 13.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // Input
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(value = sendText, onValueChange = { sendText = it }, label = { Text("Type a message...") }, singleLine = true, modifier = Modifier.weight(1f), textStyle = LocalTextStyle.current.copy(fontSize = 13.sp))
                IconButton(onClick = { service.sendMessage(sendText); sendText = "" }, enabled = sendText.isNotBlank() && connected) { Text("Send", color = if (sendText.isNotBlank() && connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) }
            }
        }
    }
}
