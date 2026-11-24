package chat.josepe.dev.data.network.mesh

import kotlinx.serialization.Serializable

/**
 * Wire model for the mesh. This is the SAME E2EE envelope we publish to the remote backend
 */
@Serializable
data class MeshEnvelope(
    val messageId: String,
    val chatId: String,
    val chatType: String,
    val senderId: String,
    val targetId: String,
    val type: String,
    val ciphertext: String?,
    val iv: String?,
    val keyVersion: Int? = null,
    val replyToId: String? = null,
    val replyToSenderId: String? = null,
    val timestamp: Long = SystemMeshClock.nowMs(),
    val isAttachment: Boolean = false,
    val attachmentPayloadId: Long = 0L,
    val attachmentName: String? = null,
    val attachmentMime: String? = null,
    val attachmentSize: Long = 0L,
    val walkie: Boolean = false,
    val hopCount: Int = 1,
    val senderPublicKey: String? = null,
    val senderHandle: String? = null,
)

@Serializable
enum class MeshPayloadKind {
    IDENTITY, ENVELOPE, ATTACHMENT_META, PING, PONG, CALL_SIGNAL, PRESENCE,
    UWB_CONFIG, UWB_REPLY, WEBRTC_SIGNAL,
    GOSSIP_SYNC, PROFILE_REQUEST, PROFILE_RESPONSE, MESH_PING,
    FILE_DESCRIPTOR, CHUNK_REQUEST, CHUNK_DATA, RELAY_PROBE, RELAY_PROBE_RESPONSE,
    LINK_PING, LINK_PONG,
    MESSAGE_ACTION,
    MOOD_UPDATE,
    GOODBYE
}

@Serializable
data class MeshGoodbye(val uid: String)

@Serializable
enum class MeshActionType {
    EDIT, DELETE, REACTION
}

@Serializable
data class MeshMessageAction(
    val actionType: MeshActionType,
    val messageId: String,
    val chatId: String,
    val senderId: String,
    val targetId: String? = null,
    val newCiphertext: String? = null,
    val newIv: String? = null,
    val reactionEmoji: String? = null,
    val isReactionRemoved: Boolean = false,
    val timestamp: Long = SystemMeshClock.nowMs(),
    val hopCount: Int = 1,
)

@Serializable
data class MeshFileDescriptor(
    val fileId: String,
    val fileName: String,
    val totalSize: Long,
    val chunkSize: Int = 65536, // 64 KB
    val totalChunks: Int,
    val sha256: String,
    val chunkHashes: List<String> = emptyList(),
    val ephemeralKey: String? = null,
    val iv: String? = null,
    val senderUid: String,
    val targetUid: String? = null,
    val hopCount: Int = 0,
)

@Serializable
data class MeshChunkRequest(
    val fileId: String,
    val chunkIndex: Int,
    val requesterUid: String,
    val hopCount: Int = 0,
)

@Serializable
data class MeshChunkData(
    val fileId: String,
    val chunkIndex: Int,
    val dataBase64: String,
    val sha256: String,
    val targetUid: String? = null,
    val hopCount: Int = 0,
)

@Serializable
enum class RelayNackReason {
    LOW_BATTERY,
    QUOTA_EXCEEDED,
    FILE_TOO_LARGE,
    METERED_CELLULAR,
    DISABLED
}

@Serializable
data class RelayProbe(
    val fileId: String,
    val fileSize: Long,
    val mimeType: String? = null,
)

@Serializable
data class RelayProbeResponse(
    val fileId: String,
    val accepted: Boolean,
    val reason: RelayNackReason? = null,
    val remainingQuotaMb: Int? = null,
)

@Serializable
enum class MeshSocialMode {
    INCOGNITO,
    PUBLIC
}

@Serializable
data class GossipPacket(
    val packetId: String,
    val senderNodeId: String,
    val targetNodeId: String? = null,
    val hopCount: Int = 0,
    val maxHops: Int = 4,
    val timestamp: Long = SystemMeshClock.nowMs(),
    val ttlMs: Long = 180_000L,
    val transportType: String = "BLE",
    val socialMode: MeshSocialMode = MeshSocialMode.INCOGNITO,
    val nickname: String? = null,
    val moodEmoji: String? = null,
    val bio: String? = null,
    val knownRoutes: List<String> = emptyList(),
    val relayRemainingMb: Int? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

@Serializable
data class MeshPingRequest(
    val fromNodeId: String,
    val fromNickname: String,
    val fromPublicKey: String,
    val message: String? = null,
    val timestamp: Long = SystemMeshClock.nowMs(),
    val targetNodeId: String? = null,
    val fromProfile: MeshPeerProfile? = null,
    val encryptedProfile: String? = null,
    val iv: String? = null,
    val hopCount: Int = 0,
    val packetId: String = "",
)

@Serializable
data class MeshPingResponse(
    val fromNodeId: String,
    val accepted: Boolean,
    val fromPublicKey: String? = null,
    val fromProfile: MeshPeerProfile? = null,
    val encryptedProfile: String? = null,
    val iv: String? = null,
    val timestamp: Long = SystemMeshClock.nowMs(),
    val targetNodeId: String? = null,
    val hopCount: Int = 0,
    val packetId: String = "",
)

data class IncomingMeshPingRequest(
    val requestId: String = SystemMeshClock.nowMs().toString(),
    val request: MeshPingRequest,
    val endpointIdOrIp: String,
    val transport: MeshTransport = MeshTransport.UNKNOWN,
)

@Serializable
data class MeshPeerProfile(
    val uid: String,
    val username: String,
    val name: String,
    val bio: String? = null,
    val avatarBytesBase64: String? = null,
    val publicKey: String,
    val moodEmoji: String? = null,
    val moodText: String? = null,
    val profileVersion: Long = 1L,
    val socialMode: MeshSocialMode = MeshSocialMode.PUBLIC,
)

@Serializable
enum class MeshPresenceKind { TYPING_START, TYPING_STOP, SEEN, DELIVERED, WALKIE_SPEAKER_ON, WALKIE_SPEAKER_OFF }

@Serializable
data class MeshPresenceSignal(
    val kind: MeshPresenceKind,
    val fromUid: String,
    val chatId: String,
    val messageIds: List<String> = emptyList(),
    val targetUid: String? = null,
    val timestamp: Long = SystemMeshClock.nowMs(),
    val hopCount: Int = 0,
)

@Serializable
data class WebRtcSignal(
    val callId: String,
    val senderId: String,
    val targetId: String,
    val type: String, // "offer", "answer", "candidate"
    val sdp: String? = null,
    val candidate: String? = null,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int = 0
)

enum class MeshTransport { WIFI, BLE, LAN, UNKNOWN }

enum class MeshLinkQuality { FAST, MEDIUM, SLOW, UNKNOWN }

enum class LinkMedium {
    BLE,          // Solo texto E2EE y presencia
    WIFI_DIRECT,  // Llamadas de voz 1 a 1 (Opus) y chunks bajo demanda (Pull)
    WIFI_LAN      // Llamadas WebRTC (Audio y Video HD), grupales y multimedia sin límites
}

data class LinkQuality(
    val endpointId: String,
    val medium: LinkMedium,
    val roundTripTimeMs: Long,
    val canStreamVideo: Boolean,
    val canStreamAudio: Boolean
)

data class MeshNode(
    val endpointId: String,
    val handle: String,
    val uid: String? = null,
    val connected: Boolean = false,
    val hopCount: Int = 1,
    val latencyMs: Long? = null,
    val lastSeen: Long = SystemMeshClock.nowMs(),
    val latitude: Double? = null,
    val longitude: Double? = null,
    val uwbDistance: Float? = null,
    val uwbAzimuth: Float? = null,
    val explicitTransport: MeshTransport? = null,
    val viaNodeId: String? = null,
    val relayRemainingMb: Int? = null,
    val socialMode: MeshSocialMode = MeshSocialMode.INCOGNITO,
    val moodEmoji: String? = null,
    val moodText: String? = null,
    val networkName: String? = null,
) {
    val transport: MeshTransport
        get() = explicitTransport ?: if (endpointId.startsWith("lan:")) MeshTransport.LAN else MeshTransport.BLE

    val linkQuality: MeshLinkQuality
        get() = when (val l = latencyMs) {
            null -> MeshLinkQuality.UNKNOWN
            in 0..5 -> MeshLinkQuality.FAST
            in 6..80 -> MeshLinkQuality.MEDIUM
            else -> MeshLinkQuality.SLOW
        }
}

data class MeshPulse(
    val fromEndpoint: String,
    val toEndpoint: String,
    val at: Long = SystemMeshClock.nowMs(),
)

@Serializable
data class MeshGossipSync(
    val senderUid: String,
    val senderHandle: String,
    val bridgedPeers: List<BridgedPeer> = emptyList(),
    val senderNetworkName: String? = null,
)

interface MeshInbound {
    suspend fun onMeshEnvelope(envelope: MeshEnvelope, receivedFile: String?)
    fun onMeshPeerConnected() {}
    fun onMeshGossipSync(fromEndpoint: String, sync: MeshGossipSync) {}
    fun onMeshProfileResponse(res: MeshPingResponse) {}
}

data class WalkieClip(
    val chatId: String,
    val filePath: String,
)

data class MeshAudioFrame(
    val endpointId: String,
    val pcm: ByteArray,
)
