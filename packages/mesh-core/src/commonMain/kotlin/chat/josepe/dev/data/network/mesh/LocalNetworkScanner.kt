package chat.josepe.dev.data.network.mesh

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
data class BridgedPeer(
    val uid: String,
    val handle: String,
    val hopCount: Int = 2,
    val transport: String = "BLE",
    val publicKey: String? = null,
    val socialMode: String = "incognito",
    val networkName: String? = null,
)

@Serializable
data class LocalNode(
    val id: String,        // Represents the peer's UID (Node ID)
    val name: String,      // Device handle / username
    val ipAddress: String, // LAN IP address
    val port: Int,         // LAN TCP port they are listening on
    val lastSeen: Long = SystemMeshClock.nowMs(),
    val publicKey: String? = null,
    val bridgedPeers: List<BridgedPeer> = emptyList(),
    val relayRemainingMb: Int? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val socialMode: String = "incognito",
    val platform: String = "unknown",
    val deviceName: String? = null,
    val deviceId: String? = null,
    val moodEmoji: String? = null,
    val networkName: String? = null,
)

@Serializable
data class OwnMeshDevice(
    val uid: String,
    val ipAddress: String,
    val port: Int,
    val deviceName: String,
    val platform: String = "unknown",
    val lastSeen: Long = SystemMeshClock.nowMs(),
    val deviceId: String? = null,
)

/** Wire-format for UDP broadcast announcements. */
@Serializable
data class NodeAnnouncement(
    val uid: String,
    val handle: String,
    val port: Int,
    val ip: String,
    val publicKey: String? = null,
    val bridgedPeers: List<BridgedPeer> = emptyList(),
    val relayRemainingMb: Int? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val socialMode: String = "incognito",
    val platform: String = "unknown",
    val deviceName: String? = null,
    val deviceId: String? = null,
    val moodEmoji: String? = null,
    val networkName: String? = null,
    val isLeaving: Boolean = false,
)

/** Extracts the peer UID from an mDNS/NSD service name, preserving `mesh_<id>` underscores. */
fun extractMeshUidFromServiceName(serviceName: String): String? {
    if (!serviceName.startsWith("JosepeChat_")) return null
    val withoutPrefix = serviceName.removePrefix("JosepeChat_")
    if (withoutPrefix.isBlank()) return null
    return if (withoutPrefix.startsWith("mesh_")) {
        val suffix = withoutPrefix.removePrefix("mesh_").substringBefore("_")
        if (suffix.isBlank()) null else "mesh_$suffix"
    } else {
        withoutPrefix.substringBefore("_").takeIf { it.isNotBlank() }
    }
}

/** Extracts the UID from a `${uid}_${ip}` composite key safely even when UID contains `_`. */
fun extractUidFromCompositeKey(mapKey: String): String =
    mapKey.substringBeforeLast("_", mapKey)

interface LocalNetworkScanner {
    val discoveredNodes: Flow<LocalNode>
    val lostNodes: Flow<String> get() = kotlinx.coroutines.flow.emptyFlow()
    fun startScanning()
    fun stopScanning()
    fun announceDevice(
        uid: String,
        handle: String,
        port: Int,
        publicKey: String? = null,
        bridgedPeers: List<BridgedPeer> = emptyList(),
        relayRemainingMb: Int? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        socialMode: String = "incognito",
        platform: String = "unknown",
        deviceName: String? = null,
        deviceId: String? = null,
        moodEmoji: String? = null,
        networkName: String? = null,
    )
}
