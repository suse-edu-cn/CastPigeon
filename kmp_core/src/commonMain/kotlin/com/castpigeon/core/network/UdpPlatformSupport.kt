package com.castpigeon.core.network

internal expect object UdpPlatformSupport {
    fun broadcastTargets(): Set<String>
    fun acquireMulticastLock()
    fun releaseMulticastLock()
}
