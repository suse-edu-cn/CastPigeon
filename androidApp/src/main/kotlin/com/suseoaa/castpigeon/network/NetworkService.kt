package com.suseoaa.castpigeon.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.yourcompany.notilinker.shared.PeerMessage
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

data class DiscoveredDevice(
    val id: String, val name: String, val host: String, val port: Int
)

enum class ConnectionState(val label: String) {
    IDLE("Ready"), BROWSING("Browsing..."), BROADCASTING("Broadcasting..."),
    CONNECTING("Connecting..."), CONNECTED("Connected"), PAIRED("Paired")
}

data class LogEntry(
    val text: String, val sender: String, val direction: Direction,
    val isSystem: Boolean = false, val timestamp: Long = System.currentTimeMillis()
) {
    enum class Direction { RECEIVED, SENT, SYSTEM }
}

interface NetworkServiceDelegate {
    fun onStateChanged(state: ConnectionState)
    fun onDeviceDiscovered(device: DiscoveredDevice)
    fun onDeviceRemoved(device: DiscoveredDevice)
    fun onMessageReceived(entry: LogEntry)
    fun onPairRequestReceived(from: String)
}

class NetworkService(private val context: Context) {
    companion object {
        @Volatile var instance: NetworkService? = null
            private set
    }
    var delegate: NetworkServiceDelegate? = null
    init { instance = this }

    private val nsdManager: NsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val serviceType = "_castpigeon._tcp"
    private val serviceName = android.os.Build.MODEL ?: "Android Device"
    private val port = 9876
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var writer: OutputStreamWriter? = null
    private var reader: BufferedReader? = null
    private var serverJob: Job? = null
    private var clientJob: Job? = null
    private var pendingPairFrom: String? = null
    private val discovered = mutableMapOf<String, DiscoveredDevice>()

    var state: ConnectionState = ConnectionState.IDLE
        private set

    private fun setState(s: ConnectionState) { state = s; delegate?.onStateChanged(s) }

    // ----- Bonjour Discovery -----
    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(t: String) {}
        override fun onDiscoveryStopped(t: String) {}
        override fun onStartDiscoveryFailed(t: String, c: Int) {}
        override fun onStopDiscoveryFailed(t: String, c: Int) {}
        override fun onServiceFound(info: NsdServiceInfo) { nsdManager.resolveService(info, resolveListener) }
        override fun onServiceLost(info: NsdServiceInfo) {
            discovered.remove(info.serviceName)?.let { delegate?.onDeviceRemoved(it) }
        }
    }
    private val resolveListener = object : NsdManager.ResolveListener {
        override fun onResolveFailed(i: NsdServiceInfo, c: Int) {}
        override fun onServiceResolved(info: NsdServiceInfo) {
            val host = info.host?.hostAddress ?: return
            val d = DiscoveredDevice(info.serviceName, info.serviceName, host, info.port)
            discovered[info.serviceName] = d; delegate?.onDeviceDiscovered(d)
        }
    }
    fun startBrowsing() { nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener) }
    fun stopBrowsing() { try { nsdManager.stopServiceDiscovery(discoveryListener) } catch (_: Exception) {} }

    // ----- Bonjour Advertising -----
    private val regListener = object : NsdManager.RegistrationListener {
        override fun onRegistrationFailed(i: NsdServiceInfo, c: Int) {}
        override fun onUnregistrationFailed(i: NsdServiceInfo, c: Int) {}
        override fun onServiceRegistered(i: NsdServiceInfo) {}
        override fun onServiceUnregistered(i: NsdServiceInfo) {}
    }
    fun startBroadcasting() {
        val info = NsdServiceInfo().apply {
            serviceName = this@NetworkService.serviceName; serviceType = this@NetworkService.serviceType; port = this@NetworkService.port
        }
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, regListener)
    }

    // ----- TCP Server -----
    fun startServer() {
        serverJob?.cancel()
        serverJob = scope.launch {
            val job = coroutineContext[Job]!!
            try {
                serverSocket = ServerSocket(port)
                setState(ConnectionState.BROADCASTING)
                while (job.isActive) {
                    val s = serverSocket!!.accept()
                    handleServerConnection(s)
                }
            } catch (_: Exception) {}
        }
    }
    private fun handleServerConnection(socket: Socket) {
        clientSocket?.close(); clientSocket = socket
        reader = BufferedReader(InputStreamReader(socket.getInputStream()))
        writer = OutputStreamWriter(socket.getOutputStream())
        setState(ConnectionState.CONNECTED)
        clientJob?.cancel(); clientJob = scope.launch { val job = coroutineContext[Job]!!; readLoop(job) }
    }

    // ----- TCP Client -----
    fun connectToHost(host: String, port: Int) {
        disconnect(); setState(ConnectionState.CONNECTING)
        clientJob?.cancel()
        clientJob = scope.launch {
            val job = coroutineContext[Job]!!
            try {
                val s = Socket(); s.connect(InetSocketAddress(host, port), 5000)
                clientSocket = s; reader = BufferedReader(InputStreamReader(s.getInputStream()))
                writer = OutputStreamWriter(s.getOutputStream())
                setState(ConnectionState.CONNECTED)
                sendJson("""{"type":"pair_request","deviceName":"$serviceName","deviceId":"${android.os.Build.MODEL}","platform":"Android"}""")
                readLoop(job)
            } catch (_: Exception) {
                if (job.isActive) { setState(ConnectionState.IDLE) }
            }
        }
    }

    // ----- Pair Accept / Reject -----
    fun acceptPairRequest() {
        sendJson("""{"type":"pair_accept","deviceName":"$serviceName","platform":"Android"}""")
        setState(ConnectionState.PAIRED)
        delegate?.onMessageReceived(LogEntry("Pairing accepted", "System", LogEntry.Direction.SYSTEM, isSystem = true))
        pendingPairFrom = null
    }

    fun rejectPairRequest() {
        sendJson("""{"type":"pair_reject","reason":"User declined"}""")
        disconnect()
        pendingPairFrom = null
    }

    // ----- Read -----
    private suspend fun readLoop(job: Job) {
        try {
            var line: String?
            while (job.isActive) {
                line = reader?.readLine() ?: break
                if (line.isNotBlank()) {
                    val msg = PeerMessage.fromJson(line)
                    withContext(Dispatchers.Main) { handleMessage(msg) }
                }
            }
        } catch (_: Exception) {}
        finally {
            withContext(Dispatchers.Main) {
                delegate?.onMessageReceived(LogEntry("Disconnected", "System", LogEntry.Direction.SYSTEM, isSystem = true))
                setState(ConnectionState.IDLE)
            }
        }
    }

    private fun handleMessage(msg: PeerMessage) {
        when (msg.type) {
            "pair_request" -> {
                val dn = msg.deviceName ?: "Unknown"
                delegate?.onMessageReceived(LogEntry("Pair request from $dn", dn, LogEntry.Direction.RECEIVED))
                pendingPairFrom = dn
                delegate?.onPairRequestReceived(dn)
            }
            "pair_accept" -> {
                delegate?.onMessageReceived(LogEntry("Paired with ${msg.deviceName ?: "Unknown"}", "System", LogEntry.Direction.SYSTEM, isSystem = true))
                setState(ConnectionState.PAIRED)
            }
            "pair_reject" -> {
                delegate?.onMessageReceived(LogEntry("Pairing rejected: ${msg.reason ?: ""}", "System", LogEntry.Direction.SYSTEM, isSystem = true))
                disconnect()
            }
            "notification" -> {
                val app = msg.appName ?: ""; val t = msg.title ?: ""; val c = msg.content ?: ""
                delegate?.onMessageReceived(LogEntry("$t: $c", app, LogEntry.Direction.RECEIVED))
            }
            "test_message" -> delegate?.onMessageReceived(LogEntry(msg.message ?: "", msg.deviceName ?: "Remote", LogEntry.Direction.RECEIVED))
            "ping" -> sendJson("""{"type":"pong"}""")
            "pong" -> delegate?.onMessageReceived(LogEntry("Pong received", "System", LogEntry.Direction.SYSTEM, isSystem = true))
        }
    }

    // ----- Send -----
    fun sendMessage(text: String) {
        sendJson(PeerMessage.toJson(PeerMessage(type = "test_message", message = text)))
        delegate?.onMessageReceived(LogEntry(text, "Me", LogEntry.Direction.SENT))
    }
    fun sendSimulatedNotification(appName: String, title: String, content: String) {
        val msg = PeerMessage(type = "notification", id = java.util.UUID.randomUUID().toString(),
            appName = appName, title = title, content = content, timestamp = System.currentTimeMillis())
        sendJson(PeerMessage.toJson(msg))
        delegate?.onMessageReceived(LogEntry("$title: $content", appName, LogEntry.Direction.SENT))
    }
    fun sendNotification(appName: String, title: String, content: String) {
        val msg = PeerMessage(type = "notification", id = java.util.UUID.randomUUID().toString(),
            appName = appName, title = title, content = content, timestamp = System.currentTimeMillis())
        sendJson(PeerMessage.toJson(msg))
    }

    fun sendPing() { sendJson("""{"type":"ping"}""") }

    private fun sendJson(json: String) {
        try { writer?.write("$json\n"); writer?.flush() } catch (_: Exception) {}
    }

    fun disconnect() {
        clientJob?.cancel()
        try { writer?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { clientSocket?.close() } catch (_: Exception) {}
        clientSocket = null; writer = null; reader = null
    }

    fun stopAll() {
        disconnect(); serverJob?.cancel()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null; stopBrowsing(); setState(ConnectionState.IDLE)
    }
}
