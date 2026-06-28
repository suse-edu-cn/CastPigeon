package com.castpigeon.core.network

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.BoundDatagramSocket
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.utils.io.core.buildPacket
import io.ktor.utils.io.core.readText
import io.ktor.utils.io.core.writeText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.concurrent.Volatile
import kotlin.random.Random

@Serializable
data class UdpDevice(
    val deviceName: String,
    val role: String,
    val hash: String,
    val ipAddress: String,
    val filePort: Int? = null,
    val deviceType: String = "Unknown",
    val prefixLength: Int? = null,
    val gateway: String? = null,
    val networkId: String? = null,
    val lanReachable: Boolean = false,
    val lastSeen: Long = 0L
)

@Serializable
data class PinDisplayInfo(
    val pin: String,
    val requestingDevice: UdpDevice
)

object UdpDiscovery {
    private const val PORT = 48500
    private const val BINDING_PIN_TTL_MILLIS = 120_000L

    private var listeningJob: Job? = null
    private var broadcastingJob: Job? = null
    private var discoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val outboundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _discoveredDevices = MutableStateFlow<Set<UdpDevice>>(emptySet())
    val discoveredDevices: StateFlow<Set<UdpDevice>> = _discoveredDevices

    private val _pairingSuccessEvent = MutableSharedFlow<UdpDevice>(extraBufferCapacity = 8)
    val pairingSuccessEvent: SharedFlow<UdpDevice> = _pairingSuccessEvent

    private val _pinInputEvent = MutableSharedFlow<UdpDevice>(extraBufferCapacity = 8)
    val pinInputEvent: SharedFlow<UdpDevice> = _pinInputEvent

    private val _pinDisplayEvent = MutableSharedFlow<PinDisplayInfo>(extraBufferCapacity = 8)
    val pinDisplayEvent: SharedFlow<PinDisplayInfo> = _pinDisplayEvent

    @Volatile private var myPairingHash: String? = null
    @Volatile private var myRole: String? = null
    @Volatile private var myName: String? = null
    @Volatile private var isPairingOpen: Boolean = false
    @Volatile private var trustedPeerHashes: Set<String> = emptySet()
    @Volatile private var currentBroadcastMessage: String? = null
    @Volatile private var currentExpectedPin: String? = null
    @Volatile private var currentPairingTargetHash: String? = null
    @Volatile private var currentPairingPinCreatedAtMillis: Long = 0L
    @Volatile private var pendingBindingTarget: UdpDevice? = null
    @Volatile private var pendingBindingTargetIp: String? = null

    fun startListening() {
        if (listeningJob?.isActive == true) return
        UdpPlatformSupport.acquireMulticastLock()
        ensureScope()

        listeningJob = discoveryScope.launch {
            val selectorManager = SelectorManager(Dispatchers.Default)
            var serverSocket: BoundDatagramSocket? = null
            try {
                serverSocket = aSocket(selectorManager).udp().bind(InetSocketAddress("0.0.0.0", PORT)) {
                    reuseAddress = true
                    broadcast = true
                }
                while (isActive) {
                    val datagram = serverSocket.receive()
                    handlePacket(datagram.packet.readText(), sanitizeEndpointAddress(datagram.address.toString()))
                }
            } catch (throwable: Throwable) {
                if (throwable !is CancellationException) {
                    println("UdpDiscovery: UDP listener stopped: ${throwable.message}")
                }
            } finally {
                serverSocket?.close()
                selectorManager.close()
            }
        }
    }

    fun startBroadcasting(
        role: String,
        deviceName: String,
        hash: String,
        filePort: Int? = null,
        deviceType: String = "Desktop",
        pairingMode: Boolean = true,
        trustedHashes: Set<String> = emptySet()
    ) {
        if (myPairingHash != hash || myRole != role || myName != deviceName || !pairingMode) {
            clearPairingPinState()
        }
        myPairingHash = hash
        myRole = role
        myName = deviceName
        isPairingOpen = pairingMode
        trustedPeerHashes = trustedHashes.map { it.uppercase() }.toSet()
        currentBroadcastMessage = "CP_PAIR|$role|$deviceName|$hash|${filePort ?: 0}|$deviceType"

        startListening()
        if (broadcastingJob?.isActive == true) return
        ensureScope()

        broadcastingJob = discoveryScope.launch {
            val selectorManager = SelectorManager(Dispatchers.Default)
            var socket: BoundDatagramSocket? = null
            try {
                socket = aSocket(selectorManager).udp().bind {
                    broadcast = true
                }
                while (isActive) {
                    val message = currentBroadcastMessage ?: continue
                    UdpPlatformSupport.broadcastTargets().forEach { targetIp ->
                        socket.sendText(message, targetIp)
                    }
                    delay(1_000)
                }
            } catch (throwable: Throwable) {
                if (throwable !is CancellationException) {
                    println("UdpDiscovery: UDP broadcaster stopped: ${throwable.message}")
                }
            } finally {
                socket?.close()
                selectorManager.close()
            }
        }
    }

    fun requestBinding(targetHash: String, targetDeviceName: String, targetRole: String, targetIp: String) {
        val role = myRole ?: return
        val name = myName ?: return
        val hash = myPairingHash ?: return
        if (targetHash == hash) return

        pendingBindingTarget = UdpDevice(targetDeviceName, targetRole, targetHash, targetIp)
        pendingBindingTargetIp = targetIp
        sendUdpMessage("CP_BIND_REQUEST|$targetHash|$role|$name|$hash", targetIp)
        _pinInputEvent.tryEmit(UdpDevice(targetDeviceName, targetRole, targetHash, targetIp))
    }

    fun verifyBinding(targetHash: String, pin: String, targetIp: String? = null) {
        val role = myRole ?: return
        val name = myName ?: return
        val hash = myPairingHash ?: return
        val destinationIp = targetIp?.takeIf { it.isNotBlank() } ?: pendingBindingTargetIp
        sendUdpMessage("CP_BIND_VERIFY|$targetHash|$role|$name|$hash|$pin", destinationIp)
    }

    fun upsertDiscoveredDevice(device: UdpDevice) {
        if (device.hash == myPairingHash) return
        _discoveredDevices.update { devices ->
            devices.filterNot { it.hash == device.hash }.toSet() + device
        }
    }

    fun clearDiscoveredDevices() {
        _discoveredDevices.value = emptySet()
    }

    fun stop() {
        discoveryScope.cancel()
        listeningJob = null
        broadcastingJob = null
        myPairingHash = null
        myRole = null
        myName = null
        isPairingOpen = false
        trustedPeerHashes = emptySet()
        currentBroadcastMessage = null
        clearPairingPinState()
        pendingBindingTarget = null
        pendingBindingTargetIp = null
        _discoveredDevices.value = emptySet()
        UdpPlatformSupport.releaseMulticastLock()
    }

    private fun handlePacket(message: String, ipAddress: String) {
        val parts = message.split("|")
        when {
            parts.size >= 4 && parts[0] == "CP_PAIR" -> handlePairPacket(parts, ipAddress)
            parts.size >= 5 && parts[0] == "CP_BIND_REQUEST" -> handleBindRequest(parts, ipAddress)
            parts.size >= 6 && parts[0] == "CP_BIND_VERIFY" -> handleBindVerify(parts, ipAddress)
            parts.size >= 3 && parts[0] == "CP_BIND_SUCCESS" -> handleBindSuccess(parts, ipAddress)
        }
    }

    private fun handlePairPacket(parts: List<String>, ipAddress: String) {
        val role = parts[1]
        val name = parts[2]
        val hash = parts[3]
        if (hash == myPairingHash) return
        if (!isPairingOpen && !isTrusted(hash)) return

        upsertDiscoveredDevice(
            UdpDevice(
                deviceName = name,
                role = role,
                hash = hash,
                ipAddress = ipAddress,
                filePort = parts.getOrNull(4)?.toIntOrNull()?.takeIf { it > 0 },
                deviceType = parts.getOrNull(5) ?: "Unknown",
                lastSeen = kotlin.time.Clock.System.now().toEpochMilliseconds()
            )
        )
    }

    private fun handleBindRequest(parts: List<String>, ipAddress: String) {
        if (!isPairingOpen) return
        val targetHash = parts[1]
        if (targetHash != myPairingHash) return

        val requester = UdpDevice(
            deviceName = parts[3],
            role = parts[2],
            hash = parts[4],
            ipAddress = ipAddress
        )
        if (requester.hash == myPairingHash) return

        val (pin, reused) = pinForBindRequest(requester.hash)
        println("UdpDiscovery: bind request requester=${requester.hash} ip=$ipAddress reusedPin=$reused")
        _pinDisplayEvent.tryEmit(PinDisplayInfo(pin, requester))
    }

    private fun handleBindVerify(parts: List<String>, ipAddress: String) {
        if (!isPairingOpen) return
        val targetHash = parts[1]
        if (targetHash != myPairingHash) return

        val requester = UdpDevice(
            deviceName = parts[3],
            role = parts[2],
            hash = parts[4],
            ipAddress = ipAddress
        )
        if (requester.hash == myPairingHash) return

        val receivedPin = parts[5]
        if (isExpectedPairingPin(requester.hash, receivedPin)) {
            clearPairingPinState()
            sendUdpMessage("CP_BIND_SUCCESS|${requester.hash}|$myPairingHash", ipAddress)
            _pairingSuccessEvent.tryEmit(requester)
        } else {
            println("UdpDiscovery: bind verify failed requester=${requester.hash} expectedTarget=$currentPairingTargetHash activePin=${currentExpectedPin != null}")
        }
    }

    private fun handleBindSuccess(parts: List<String>, ipAddress: String) {
        val targetHash = parts[1]
        val senderHash = parts[2]
        if (targetHash != myPairingHash) return

        val device = pendingBindingTarget
            ?.takeIf { it.hash == senderHash }
            ?.copy(ipAddress = ipAddress)
            ?: _discoveredDevices.value.find { it.hash == senderHash }

        if (device != null) {
            pendingBindingTarget = null
            pendingBindingTargetIp = null
            _pairingSuccessEvent.tryEmit(device)
        }
    }

    private fun sendUdpMessage(message: String, targetIp: String? = null) {
        outboundScope.launch {
            val selectorManager = SelectorManager(Dispatchers.Default)
            var socket: BoundDatagramSocket? = null
            try {
                socket = aSocket(selectorManager).udp().bind {
                    broadcast = true
                }
                val targetAddresses = buildList {
                    normalizeIpv4Target(targetIp)?.let(::add)
                    if (targetIp.isNullOrBlank()) {
                        addAll(UdpPlatformSupport.broadcastTargets())
                    }
                }
                    .mapNotNull(::normalizeIpv4Target)
                    .distinct()

                repeat(3) {
                    targetAddresses.forEach { target -> socket.sendText(message, target) }
                    delay(200)
                }
            } catch (throwable: Throwable) {
                if (throwable !is CancellationException) {
                    println("UdpDiscovery: UDP message send failed: ${throwable.message}")
                }
            } finally {
                socket?.close()
                selectorManager.close()
            }
        }
    }

    private fun ensureScope() {
        if (!discoveryScope.isActive) {
            discoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        }
    }

    private fun pinForBindRequest(requesterHash: String): Pair<String, Boolean> {
        val nowMillis = currentTimeMillis()
        val existingPin = currentExpectedPin
        if (
            existingPin != null &&
            currentPairingTargetHash == requesterHash &&
            !isPairingPinExpired(nowMillis)
        ) {
            return existingPin to true
        }

        val pin = Random.nextInt(1000, 10_000).toString()
        currentExpectedPin = pin
        currentPairingTargetHash = requesterHash
        currentPairingPinCreatedAtMillis = nowMillis
        return pin to false
    }

    private fun isExpectedPairingPin(requesterHash: String, pin: String): Boolean {
        val nowMillis = currentTimeMillis()
        val matches = currentExpectedPin == pin &&
            currentPairingTargetHash == requesterHash &&
            !isPairingPinExpired(nowMillis)
        if (!matches && currentPairingTargetHash == requesterHash && isPairingPinExpired(nowMillis)) {
            clearPairingPinState()
        }
        return matches
    }

    private fun isPairingPinExpired(nowMillis: Long): Boolean {
        val createdAtMillis = currentPairingPinCreatedAtMillis
        return createdAtMillis <= 0L || nowMillis - createdAtMillis > BINDING_PIN_TTL_MILLIS
    }

    private fun clearPairingPinState() {
        currentExpectedPin = null
        currentPairingTargetHash = null
        currentPairingPinCreatedAtMillis = 0L
    }

    private fun currentTimeMillis(): Long {
        return kotlin.time.Clock.System.now().toEpochMilliseconds()
    }

    private fun sanitizeEndpointAddress(raw: String): String {
        extractIpv4Address(raw)?.let { return it }
        val withoutSlash = raw.removePrefix("/")
        return withoutSlash
            .substringBeforeLast(":", withoutSlash)
            .removeSurrounding("[", "]")
    }

    private fun isTrusted(hash: String): Boolean {
        val trusted = trustedPeerHashes
        return trusted.isNotEmpty() && trusted.contains(hash.uppercase())
    }

    private suspend fun BoundDatagramSocket.sendText(message: String, targetIp: String) {
        runCatching {
            val packet = buildPacket { writeText(message) }
            send(Datagram(packet, InetSocketAddress(targetIp, PORT)))
        }.onFailure { throwable ->
            println("UdpDiscovery: failed to send UDP packet to $targetIp:$PORT: ${throwable.message}")
        }
    }

    private fun normalizeIpv4Target(targetIp: String?): String? {
        val value = targetIp
            ?.trim()
            ?.removePrefix("/")
            ?.removeSurrounding("[", "]")
            ?.substringBefore("%")
            ?.takeIf { it.isNotBlank() }
            ?: return null
        extractIpv4Address(value)?.let { return it }
        if (value.contains(":")) return null
        val segments = value.split(".")
        if (segments.size != 4) return null
        return if (segments.all { segment ->
                segment.isNotEmpty() && segment.toIntOrNull()?.let { it in 0..255 } == true
            }
        ) {
            value
        } else {
            null
        }
    }

    private fun extractIpv4Address(value: String): String? {
        val matches = ipv4Pattern.findAll(value)
        return matches
            .map { it.value }
            .firstOrNull { candidate ->
                candidate.split(".").all { segment ->
                    segment.toIntOrNull()?.let { it in 0..255 } == true
                }
            }
    }

    private val ipv4Pattern = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
}
