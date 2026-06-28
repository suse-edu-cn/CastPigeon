package com.castpigeon.core.network

internal actual object UdpPlatformSupport {
    actual fun broadcastTargets(): Set<String> = setOf("255.255.255.255")
    actual fun acquireMulticastLock() = Unit
    actual fun releaseMulticastLock() = Unit
}
