package com.suseoaa.castpigeon

import android.content.Context
import android.util.Log

object BoundDeviceStore {
    private const val PREFS_NAME = "CastPigeonPrefs"
    private const val BOUND_MACS_KEY = "BoundMacs"
    private val hashRegex = Regex("^[0-9A-Fa-f]{4,8}$")

    data class Entry(
        val name: String,
        val hash: String?,
        val deviceType: String,
        val lastIp: String?,
        val filePort: String?,
        val rawValue: String
    )

    fun getEntries(context: Context): Set<String> {
        return prefs(context).getStringSet(BOUND_MACS_KEY, emptySet()).orEmpty()
    }

    fun getHashes(context: Context): Set<String> {
        return getEntries(context).mapNotNull { parse(it).hash?.uppercase() }.toSet()
    }

    fun authorizeOrMigratePeer(context: Context, peerName: String, peerHash: String): Boolean {
        val normalizedHash = peerHash.uppercase()
        if (!hashRegex.matches(normalizedHash)) return false

        val entries = getEntries(context)
        if (entries.any { parse(it).hash?.equals(normalizedHash, ignoreCase = true) == true }) {
            return true
        }

        val cleanPeerName = peerName.trim()
        if (cleanPeerName.isEmpty()) return false

        val matchedRaw = entries.firstOrNull { raw ->
            val entry = parse(raw)
            val maybeMac = entry.deviceType.equals("Mac", ignoreCase = true) ||
                entry.deviceType.equals("Unknown", ignoreCase = true)
            maybeMac && entry.name == cleanPeerName
        } ?: return false

        val oldEntry = parse(matchedRaw)
        val migrated = listOf(
            oldEntry.name,
            normalizedHash,
            oldEntry.deviceType.ifBlank { "Mac" },
            oldEntry.lastIp.orEmpty(),
            oldEntry.filePort.orEmpty()
        ).joinToString("|")

        val updated = entries.toMutableSet().apply {
            remove(matchedRaw)
            add(migrated)
        }
        prefs(context).edit().putStringSet(BOUND_MACS_KEY, updated).apply()
        Log.w(
            "CastPigeon",
            "已迁移同名 Mac 绑定 Hash: name=$cleanPeerName old=${oldEntry.hash ?: "none"} new=$normalizedHash"
        )
        return true
    }

    fun updateNetworkInfo(
        context: Context,
        peerHash: String,
        peerName: String,
        deviceType: String,
        ip: String,
        filePort: Int?
    ) {
        val normalizedHash = peerHash.uppercase()
        if (!hashRegex.matches(normalizedHash) || ip.isBlank()) return

        val entries = getEntries(context)
        val matchedRaw = entries.firstOrNull { raw ->
            parse(raw).hash?.equals(normalizedHash, ignoreCase = true) == true
        } ?: return

        val oldEntry = parse(matchedRaw)
        val updated = listOf(
            oldEntry.name.ifBlank { peerName.ifBlank { "已绑定设备" } },
            normalizedHash,
            oldEntry.deviceType.takeUnless { it.equals("Unknown", ignoreCase = true) }
                ?: deviceType.ifBlank { "Unknown" },
            ip,
            filePort?.toString().orEmpty()
        ).joinToString("|")

        if (updated == matchedRaw) return

        val newEntries = entries.toMutableSet().apply {
            remove(matchedRaw)
            add(updated)
        }
        prefs(context).edit().putStringSet(BOUND_MACS_KEY, newEntries).apply()
        Log.i("CastPigeon", "已更新绑定设备 LAN 信息: name=${oldEntry.name}, hash=$normalizedHash, ip=$ip, port=$filePort")
    }

    fun parse(raw: String): Entry {
        val parts = raw.split("|")
        return if (parts.size >= 2) {
            Entry(
                name = parts[0].ifBlank { "已绑定设备" },
                hash = parts[1].ifBlank { null },
                deviceType = parts.getOrNull(2)?.ifBlank { "Unknown" } ?: "Unknown",
                lastIp = parts.getOrNull(3)?.ifBlank { null },
                filePort = parts.getOrNull(4)?.ifBlank { null },
                rawValue = raw
            )
        } else {
            Entry(
                name = raw.ifBlank { "已绑定设备" },
                hash = raw.takeIf { hashRegex.matches(it) }?.uppercase(),
                deviceType = "Unknown",
                lastIp = null,
                filePort = null,
                rawValue = raw
            )
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
