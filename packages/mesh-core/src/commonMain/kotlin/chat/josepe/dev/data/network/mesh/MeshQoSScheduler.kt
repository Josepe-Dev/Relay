package chat.josepe.dev.data.network.mesh

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class MeshPacketPriority { HIGH, LOW }

data class PrioritizedMeshPacket(
    val id: String,
    val priority: MeshPacketPriority,
    val envelope: MeshEnvelope,
    val chunkData: MeshChunkData? = null,
    val isTextOrControl: Boolean = true,
)

/**
 * Strict 2-level Quality of Service (QoS) scheduler for intermediate relay nodes.
 * High priority: ACKs, text messages, presence, and Link-State packets.
 * Low priority: Binary file chunks.
 * Rule: High-priority text and control frames immediately preempt low-priority file chunks.
 */
class MeshQoSScheduler {
    private val highPriorityQueue = Channel<PrioritizedMeshPacket>(Channel.UNLIMITED)
    private val lowPriorityQueue = Channel<PrioritizedMeshPacket>(Channel.UNLIMITED)
    private val mutex = Mutex()

    suspend fun enqueue(packet: PrioritizedMeshPacket) {
        when (packet.priority) {
            MeshPacketPriority.HIGH -> highPriorityQueue.send(packet)
            MeshPacketPriority.LOW -> lowPriorityQueue.send(packet)
        }
    }

    /** Dequeues the next packet, strictly checking the High Queue before the Low Queue. */
    suspend fun dequeueNext(): PrioritizedMeshPacket? = mutex.withLock {
        val high = highPriorityQueue.tryReceive().getOrNull()
        if (high != null) return high
        return lowPriorityQueue.tryReceive().getOrNull()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun hasPendingHighPriority(): Boolean {
        return !highPriorityQueue.isEmpty
    }
}
