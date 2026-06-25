package com.suseoaa.castpigeon.service

import android.content.Context
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.suseoaa.castpigeon.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

object LanFileTransferManager {
    private const val TAG = "CastPigeonFile"
    private const val DEFAULT_PORT = 48601
    private const val NOTIFICATION_CHANNEL_ID = "castpigeon_file_transfer"
    private const val NOTIFICATION_ID = 2002

    enum class TransferDirection { Sending, Receiving }
    enum class TransferPhase { InProgress, Success, Failed }

    data class TransferStatus(
        val fileName: String,
        val peerLabel: String,
        val direction: TransferDirection,
        val phase: TransferPhase,
        val bytesTransferred: Long,
        val totalBytes: Long?,
        val detail: String? = null
    ) {
        val progressFraction: Float?
            get() = totalBytes?.takeIf { it > 0 }?.let { bytesTransferred.toFloat() / it.toFloat() }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null

    private val _serverPort = MutableStateFlow(DEFAULT_PORT)
    val serverPort: StateFlow<Int> = _serverPort
    private val _transferStatus = MutableStateFlow<TransferStatus?>(null)
    val transferStatus: StateFlow<TransferStatus?> = _transferStatus

    fun startServer(context: Context) {
        if (serverSocket != null) return

        scope.launch {
            var port = DEFAULT_PORT
            while (serverSocket == null && port < DEFAULT_PORT + 20) {
                try {
                    serverSocket = ServerSocket(port)
                    _serverPort.value = port
                    Log.i(TAG, "LAN 文件接收服务已启动: port=$port")
                } catch (_: Exception) {
                    port += 1
                }
            }

            val socket = serverSocket ?: run {
                Log.e(TAG, "LAN 文件接收服务启动失败")
                return@launch
            }

            while (!socket.isClosed) {
                try {
                    val client = socket.accept()
                    launch { handleClient(context.applicationContext, client) }
                } catch (e: Exception) {
                    if (!socket.isClosed) Log.e(TAG, "接收文件连接失败", e)
                }
            }
        }
    }

    fun stopServer() {
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
    }

    suspend fun sendFile(context: Context, targetIp: String, targetPort: Int, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val fileName = queryDisplayName(context, uri) ?: "CastPigeon-${System.currentTimeMillis()}"
        val totalBytes = queryFileSize(context, uri)
        updateTransferStatus(
            context = context,
            status = TransferStatus(
                fileName = fileName,
                peerLabel = "$targetIp:$targetPort",
                direction = TransferDirection.Sending,
                phase = TransferPhase.InProgress,
                bytesTransferred = 0L,
                totalBytes = totalBytes
            )
        )
        val encodedName = URLEncoder.encode(fileName, Charsets.UTF_8.name())
        val url = URL("http://$targetIp:$targetPort/upload?name=$encodedName")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 60_000
            setRequestProperty("Content-Type", "application/octet-stream")
            setRequestProperty("X-CastPigeon-Filename", encodedName)
        }

        return@withContext try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "无法打开文件输入流" }
                BufferedOutputStream(connection.outputStream).use { output ->
                    copyWithProgress(input, output, totalBytes) { sentBytes ->
                        updateTransferStatus(
                            context = context,
                            status = TransferStatus(
                                fileName = fileName,
                                peerLabel = "$targetIp:$targetPort",
                                direction = TransferDirection.Sending,
                                phase = TransferPhase.InProgress,
                                bytesTransferred = sentBytes,
                                totalBytes = totalBytes
                            )
                        )
                    }
                }
            }
            val code = connection.responseCode
            Log.i(TAG, "文件发送完成: $fileName -> $targetIp:$targetPort, code=$code")
            updateTransferStatus(
                context = context,
                status = TransferStatus(
                    fileName = fileName,
                    peerLabel = "$targetIp:$targetPort",
                    direction = TransferDirection.Sending,
                    phase = if (code in 200..299) TransferPhase.Success else TransferPhase.Failed,
                    bytesTransferred = totalBytes ?: 0L,
                    totalBytes = totalBytes,
                    detail = "HTTP $code"
                )
            )
            code in 200..299
        } catch (e: Exception) {
            Log.e(TAG, "文件发送失败: $fileName -> $targetIp:$targetPort", e)
            updateTransferStatus(
                context = context,
                status = TransferStatus(
                    fileName = fileName,
                    peerLabel = "$targetIp:$targetPort",
                    direction = TransferDirection.Sending,
                    phase = TransferPhase.Failed,
                    bytesTransferred = 0L,
                    totalBytes = totalBytes,
                    detail = e.message
                )
            )
            false
        } finally {
            connection.disconnect()
        }
    }

    private fun handleClient(context: Context, client: java.net.Socket) {
        client.use { socket ->
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            val requestLine = readAsciiLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2 || parts[0] != "POST" || !parts[1].startsWith("/upload")) {
                writeResponse(output, 404, "Not Found")
                return
            }

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readAsciiLine(input) ?: return
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator > 0) {
                    headers[line.substring(0, separator).lowercase(Locale.US)] = line.substring(separator + 1).trim()
                }
            }

            val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L
            val nameFromHeader = headers["x-castpigeon-filename"]?.let {
                URLDecoder.decode(it, Charsets.UTF_8.name())
            }
            val fileName = sanitizeFileName(nameFromHeader ?: parseNameFromPath(parts[1]) ?: "CastPigeon-${System.currentTimeMillis()}")
            val target = createDownloadTarget(context, fileName)
            updateTransferStatus(
                context = context,
                status = TransferStatus(
                    fileName = fileName,
                    peerLabel = socket.inetAddress?.hostAddress ?: "未知设备",
                    direction = TransferDirection.Receiving,
                    phase = TransferPhase.InProgress,
                    bytesTransferred = 0L,
                    totalBytes = contentLength.takeIf { it > 0 }
                )
            )

            try {
                target.output.use { fileOutput ->
                    copyFixed(input, fileOutput, contentLength) { receivedBytes ->
                        updateTransferStatus(
                            context = context,
                            status = TransferStatus(
                                fileName = fileName,
                                peerLabel = socket.inetAddress?.hostAddress ?: "未知设备",
                                direction = TransferDirection.Receiving,
                                phase = TransferPhase.InProgress,
                                bytesTransferred = receivedBytes,
                                totalBytes = contentLength.takeIf { it > 0 }
                            )
                        )
                    }
                }
                Log.i(TAG, "文件接收完成: ${target.description}, bytes=$contentLength")
                updateTransferStatus(
                    context = context,
                    status = TransferStatus(
                        fileName = fileName,
                        peerLabel = socket.inetAddress?.hostAddress ?: "未知设备",
                        direction = TransferDirection.Receiving,
                        phase = TransferPhase.Success,
                        bytesTransferred = contentLength,
                        totalBytes = contentLength.takeIf { it > 0 },
                        detail = target.description
                    )
                )
                writeResponse(output, 200, "OK")
            } catch (e: Exception) {
                target.cleanup()
                Log.e(TAG, "文件接收失败", e)
                updateTransferStatus(
                    context = context,
                    status = TransferStatus(
                        fileName = fileName,
                        peerLabel = socket.inetAddress?.hostAddress ?: "未知设备",
                        direction = TransferDirection.Receiving,
                        phase = TransferPhase.Failed,
                        bytesTransferred = 0L,
                        totalBytes = contentLength.takeIf { it > 0 },
                        detail = e.message
                    )
                )
                writeResponse(output, 500, "Failed")
            }
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return uri.lastPathSegment
    }

    private fun queryFileSize(context: Context, uri: Uri): Long? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && !cursor.isNull(index)) return cursor.getLong(index)
            }
        }
        return null
    }

    private fun parseNameFromPath(path: String): String? {
        val marker = "name="
        val index = path.indexOf(marker)
        if (index < 0) return null
        val raw = path.substring(index + marker.length).substringBefore('&')
        return URLDecoder.decode(raw, Charsets.UTF_8.name())
    }

    private data class DownloadTarget(
        val output: OutputStream,
        val description: String,
        val cleanup: () -> Unit = {}
    )

    private fun createDownloadTarget(context: Context, fileName: String): DownloadTarget {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("无法创建 Downloads 文件")
            val output = resolver.openOutputStream(uri) ?: error("无法打开 Downloads 输出流")
            return DownloadTarget(
                output = object : OutputStream() {
                    override fun write(b: Int) = output.write(b)
                    override fun write(b: ByteArray) = output.write(b)
                    override fun write(b: ByteArray, off: Int, len: Int) = output.write(b, off, len)
                    override fun flush() = output.flush()
                    override fun close() {
                        output.close()
                        ContentValues().apply {
                            put(MediaStore.Downloads.IS_PENDING, 0)
                        }.also { resolver.update(uri, it, null, null) }
                    }
                },
                description = uri.toString(),
                cleanup = { resolver.delete(uri, null, null) }
            )
        }

        val file = uniqueDownloadFile(context, fileName)
        return DownloadTarget(file.outputStream(), file.absolutePath)
    }

    private fun uniqueDownloadFile(context: Context, fileName: String): File {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            ?: context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        downloads.mkdirs()

        var candidate = File(downloads, fileName)
        val base = candidate.nameWithoutExtension
        val ext = candidate.extension.takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
        var index = 1
        while (candidate.exists()) {
            candidate = File(downloads, "$base ($index)$ext")
            index += 1
        }
        return candidate
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "CastPigeon-${System.currentTimeMillis()}" }
    }

    private fun readAsciiLine(input: BufferedInputStream): String? {
        val bytes = mutableListOf<Byte>()
        while (true) {
            val value = input.read()
            if (value == -1) return if (bytes.isEmpty()) null else bytes.toByteArray().toString(Charsets.US_ASCII)
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes.add(value.toByte())
        }
        return bytes.toByteArray().toString(Charsets.US_ASCII)
    }

    private fun copyFixed(
        input: BufferedInputStream,
        output: java.io.OutputStream,
        bytesToCopy: Long,
        onProgress: (Long) -> Unit
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var remaining = bytesToCopy
        var copied = 0L
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read == -1) break
            output.write(buffer, 0, read)
            remaining -= read
            copied += read
            onProgress(copied)
        }
    }

    private fun copyWithProgress(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        totalBytes: Long?,
        onProgress: (Long) -> Unit
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            output.write(buffer, 0, read)
            copied += read
            onProgress(copied)
        }
        if (totalBytes != null && copied < totalBytes) {
            onProgress(totalBytes)
        }
    }

    private fun writeResponse(output: BufferedOutputStream, code: Int, body: String) {
        val status = if (code == 200) "OK" else "Error"
        val bytes = body.toByteArray()
        output.write("HTTP/1.1 $code $status\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        output.write(bytes)
        output.flush()
    }

    private fun updateTransferStatus(context: Context, status: TransferStatus) {
        _transferStatus.value = status
        showTransferNotification(context, status)
    }

    private fun showTransferNotification(context: Context, status: TransferStatus) {
        ensureNotificationChannel(context)
        val manager = NotificationManagerCompat.from(context)
        val titlePrefix = when (status.direction) {
            TransferDirection.Sending -> "正在发送"
            TransferDirection.Receiving -> "正在接收"
        }
        val completePrefix = when (status.direction) {
            TransferDirection.Sending -> "发送"
            TransferDirection.Receiving -> "接收"
        }
        val title = when (status.phase) {
            TransferPhase.InProgress -> "$titlePrefix ${status.fileName}"
            TransferPhase.Success -> "${completePrefix}成功"
            TransferPhase.Failed -> "${completePrefix}失败"
        }
        val content = when (status.phase) {
            TransferPhase.InProgress -> "${status.peerLabel} · ${formatProgress(status)}"
            TransferPhase.Success -> "${status.fileName} 已完成"
            TransferPhase.Failed -> status.detail ?: "${status.fileName} 未完成"
        }
        val builder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(if (status.phase == TransferPhase.Success) android.R.drawable.stat_sys_upload_done else android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setAutoCancel(status.phase != TransferPhase.InProgress)
            .setOngoing(status.phase == TransferPhase.InProgress)

        if (status.phase == TransferPhase.InProgress) {
            val total = status.totalBytes?.takeIf { it > 0 }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
            val current = status.bytesTransferred.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            if (total != null) {
                builder.setProgress(total, current, false)
            } else {
                builder.setProgress(0, 0, true)
            }
        } else {
            builder.setProgress(0, 0, false)
        }
        manager.notify(NOTIFICATION_ID, builder.build())
    }

    private fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val existing = manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID)
        if (existing != null) return
        manager.createNotificationChannel(
            android.app.NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "文件传输",
                android.app.NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun formatProgress(status: TransferStatus): String {
        val total = status.totalBytes
        return if (total != null && total > 0) {
            val percent = ((status.bytesTransferred * 100) / total).toInt().coerceIn(0, 100)
            "$percent%"
        } else {
            "${status.bytesTransferred} B"
        }
    }
}
