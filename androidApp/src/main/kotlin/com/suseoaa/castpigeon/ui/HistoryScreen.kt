package com.suseoaa.castpigeon.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suseoaa.castpigeon.db.ClipboardHistoryItem
import com.suseoaa.castpigeon.db.MessageDatabaseHelper
import com.suseoaa.castpigeon.service.BleForegroundService
import com.suseoaa.castpigeon.shared.NotificationMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import android.graphics.BitmapFactory

private enum class HistoryTab(val title: String) {
    Messages("消息"),
    Clipboard("粘贴板")
}

private data class HistoryUiState(
    val messages: List<NotificationMessage> = emptyList(),
    val clipboardItems: List<ClipboardHistoryItem> = emptyList(),
    val isLoading: Boolean = true
)

@Composable
fun HistoryScreen() {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(HistoryTab.Messages) }
    var uiState by remember { mutableStateOf(HistoryUiState()) }

    LaunchedEffect(Unit) {
        MessageDatabaseHelper.historyChanges
            .onStart { emit(Unit) }
            .collectLatest {
                val state = withContext(Dispatchers.IO) {
                    val dbHelper = MessageDatabaseHelper(context.applicationContext)
                    HistoryUiState(
                        messages = dbHelper.getAllMessages(),
                        clipboardItems = dbHelper.getAllClipboardHistory(),
                        isLoading = false
                    )
                }
                uiState = state
            }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Box(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 24.dp, vertical = 16.dp)) {
            Text("发送历史记录", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        TabRow(selectedTabIndex = HistoryTab.entries.indexOf(selectedTab)) {
            HistoryTab.entries.forEach { tab ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = { selectedTab = tab },
                    text = { Text(tab.title) }
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))

        if (uiState.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (selectedTab == HistoryTab.Messages && uiState.messages.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无发送记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (selectedTab == HistoryTab.Clipboard && uiState.clipboardItems.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无粘贴板记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                if (selectedTab == HistoryTab.Messages) {
                    items(uiState.messages, key = { it.id }) { msg ->
                        HistoryItemCard(msg)
                    }
                } else {
                    items(uiState.clipboardItems, key = { it.id }) { item ->
                        ClipboardHistoryCard(item)
                    }
                }
            }
        }
    }
}

@Composable
fun ClipboardHistoryCard(item: ClipboardHistoryItem) {
    val context = LocalContext.current
    val sdf = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }
    val timeStr = sdf.format(Date(item.timestamp))
    val directionText = when (item.direction) {
        "sent_to_mac" -> "发送到 Mac"
        "received_from_mac" -> "来自 Mac"
        else -> "粘贴板"
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        directionText,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(timeStr, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    item.content,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            IconButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    BleForegroundService.isInternalClipboardWrite = true
                    clipboard.setPrimaryClip(ClipData.newPlainText("CastPigeon", item.content))
                    Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                }
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "复制粘贴板内容",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun HistoryItemCard(msg: NotificationMessage) {
    val context = LocalContext.current
    val sdf = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }
    val timeStr = sdf.format(Date(msg.timestamp))

    // 尝试加载本地图标缓存
    val iconBitmap = remember(msg.appName) {
        val iconDir = context.getDir("Icons", Context.MODE_PRIVATE)
        val iconFile = File(iconDir, "${msg.appName}.png")
        if (iconFile.exists()) {
            BitmapFactory.decodeFile(iconFile.absolutePath)?.asImageBitmap()
        } else null
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            // 图标展示
            if (iconBitmap != null) {
                Image(
                    bitmap = iconBitmap,
                    contentDescription = msg.appName,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp))
                )
            } else {
                Box(
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text(msg.appName.take(1), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // 内容展示
            Column(modifier = Modifier.weight(1f)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(msg.appName, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                    Text(timeStr, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.height(4.dp))
                if (msg.title.isNotBlank()) {
                    Text(msg.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (msg.content.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(msg.content, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
