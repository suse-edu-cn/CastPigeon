package com.castpigeon.core

enum class DeviceRole(val code: Int) {
    Sender(0),
    Receiver(1);

    companion object {
        fun fromCode(code: Int): DeviceRole = entries.firstOrNull { it.code == code } ?: Sender
    }
}

enum class WorkMode(val code: Int) {
    Idle(0),
    Pairing(1),
    Working(2);

    companion object {
        fun fromCode(code: Int): WorkMode = entries.firstOrNull { it.code == code } ?: Idle
    }
}

enum class ConnectionState(val code: Int) {
    Idle(0),
    AdvertisingOrScanning(1),
    Connecting(2),
    PairingRequest(3),
    Transferring(4),
    Disconnecting(5);

    companion object {
        fun fromCode(code: Int): ConnectionState = entries.firstOrNull { it.code == code } ?: Idle
    }
}
