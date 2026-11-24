package chat.josepe.dev.data.network.mesh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

data class RouteEntry(
    val destinationNodeId: String,
    val nextHopEndpointId: String,
    val hops: Int,
    val metricCost: Int = 1,
    val transport: MeshTransport,
    val lastSeen: Long,
    val socialMode: MeshSocialMode,
    val nickname: String?,
    val moodEmoji: String?,
    val bio: String?,
)

data class GossipNode(
    val nodeId: String,
    val nextHopEndpointId: String,
    val hops: Int,
    val metricCost: Int,
    val transport: MeshTransport,
    val socialMode: MeshSocialMode,
    val nickname: String?,
    val moodEmoji: String?,
    val bio: String?,
    val latencyMs: Long,
    val lastSeen: Long,
)

/**
 * Lightweight, thread-safe LRU set for cross-platform message deduplication.
 */
class MeshLruDeduplicator(
    private val maxSize: Int = 2048,
    private val ttlMs: Long = 5 * 60_000L,
    private val clock: MeshClock = SystemMeshClock,
) {
    private val entries = mutableMapOf<String, Long>()
    private val mutex = Mutex()

    suspend fun putIfAbsent(id: String): Boolean = mutex.withLock {
        val now = clock.nowMs()
        val iter = entries.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            if (e.value <= now) iter.remove()
            else break
        }

        val expiry = entries[id]
        if (expiry != null && expiry > now) return@withLock false

        if (entries.size >= maxSize) {
            val oldest = entries.keys.iterator()
            if (oldest.hasNext()) {
                oldest.next()
                oldest.remove()
            }
        }
        entries.remove(id)
        entries[id] = now + ttlMs
        true
    }

    suspend fun contains(id: String): Boolean = mutex.withLock {
        val expiry = entries[id] ?: return@withLock false
        expiry > clock.nowMs()
    }

    suspend fun clear() = mutex.withLock {
        entries.clear()
    }
}

/**
 * Handles Gossip protocol routing, weighted Dijkstra cost calculation,
 * probabilistic fanout (k=2-3), loop mitigation, and local repair for Relay Mesh Network.
 */
class GossipRouter(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val clock: MeshClock = SystemMeshClock,
    private val idGenerator: MeshIdGenerator = UuidMeshIdGenerator,
    private val logger: MeshLogger = NoOpMeshLogger,
) {
    private val mutex = Mutex()
    private val routingTable = mutableMapOf<String, RouteEntry>()
    private val seenPackets = MeshLruDeduplicator(maxSize = 2048, clock = clock)

    private val _discoveredNodes = MutableStateFlow<List<GossipNode>>(emptyList())
    val discoveredNodes: StateFlow<List<GossipNode>> = _discoveredNodes.asStateFlow()

    @Volatile
    private var quarantinedNodes: Set<String> = emptySet()

    fun setQuarantinedNodes(nodes: Collection<String>) {
        val snapshot = nodes.toSet()
        quarantinedNodes = snapshot
        scope.launch {
            mutex.withLock {
                routingTable.keys.filter { it in snapshot }.forEach { routingTable.remove(it) }
                updateDiscoveredNodesFlow()
            }
        }
    }

    fun isNodeQuarantined(nodeId: String): Boolean = nodeId in quarantinedNodes

    fun quarantineNode(nodeId: String) {
        quarantinedNodes = quarantinedNodes + nodeId
        scope.launch {
            mutex.withLock {
                routingTable.remove(nodeId)
                updateDiscoveredNodesFlow()
            }
        }
    }

    fun unquarantineNode(nodeId: String) {
        quarantinedNodes = quarantinedNodes - nodeId
    }

    private var gossipJob: Job? = null
    private var purgeJob: Job? = null

    var myNodeId: String = "node_" + idGenerator.nextId().take(8)
    var mySocialMode: MeshSocialMode = MeshSocialMode.INCOGNITO
    var myNickname: String = "Anon"
    var myMoodEmoji: String = "⚡"
    var myBio: String = ""

    /** Provider of currently connected endpoint IDs for Gossip Fanout selection. */
    var getConnectedNeighbors: (() -> List<String>)? = null

    /** Callback invoked when a packet needs to be transmitted over physical radios to specific endpoints. */
    var onBroadcastPacketToPeers: (suspend (packet: GossipPacket, targetEndpoints: List<String>) -> Unit)? = null

    /** Legacy single-target broadcast callback fallback. */
    var onBroadcastPacket: (suspend (packet: GossipPacket, excludeEndpointId: String?) -> Unit)? = null

    companion object {
        /** Link-State Dijkstra Interface Metric Costs */
        fun getTransportCost(transport: MeshTransport): Int = when (transport) {
            MeshTransport.LAN -> 1       // Ethernet / Wi-Fi LAN: high bandwidth, low interference
            MeshTransport.WIFI -> 3      // Wi-Fi Direct: high throughput, moderate radio usage
            MeshTransport.BLE -> 8       // Bluetooth LE: restricted bandwidth, high collision risk
            MeshTransport.UNKNOWN -> 8
        }
    }

    fun start() {
        if (gossipJob?.isActive == true) return

        // 1. Periodically broadcast discovery sync every 12 seconds
        gossipJob = scope.launch {
            while (isActive) {
                delay(12_000L)
                broadcastDiscoverySync()
            }
        }

        // 2. Purge stale routes every 30 seconds (TTL = 3 minutes)
        purgeJob = scope.launch {
            while (isActive) {
                delay(30_000L)
                purgeStaleRoutes()
            }
        }
    }

    fun stop() {
        gossipJob?.cancel()
        purgeJob?.cancel()
        gossipJob = null
        purgeJob = null
    }

    /**
     * Ingests an incoming GossipPacket from an adjacent endpoint.
     * Returns true if packet was valid and processed; false if discarded.
     */
    suspend fun onPacketReceived(packet: GossipPacket, fromEndpointId: String): Boolean {
        val now = clock.nowMs()

        // 0. HOP-DROP QUARANTINE FILTER:
        // Silently drop packets from quarantined nodes immediately in memory:
        // - No LRU cache
        // - No fragments / ACKs
        // - No retransmission (hop canceled)
        if (isNodeQuarantined(packet.senderNodeId) || isNodeQuarantined(fromEndpointId)) {
            logger.log(MeshLogLevel.WARN, "GossipRouter", "HOP-DROP: Silently dropping packet ${packet.packetId} from quarantined node ${packet.senderNodeId}")
            return false
        }

        // 1. Deduplication via LRU filter
        if (!seenPackets.putIfAbsent(packet.packetId)) {
            return false
        }

        // 2. Loop prevention: discard own packets
        if (packet.senderNodeId == myNodeId) {
            return false
        }

        // 3. TTL and Hop limits: drop if hops >= maxHops (max 4) or TTL expired
        if (packet.hopCount >= packet.maxHops || packet.hopCount >= 4) {
            logger.log(MeshLogLevel.DEBUG, "GossipRouter", "Dropping packet ${packet.packetId}: max hops exceeded (${packet.hopCount})")
            return false
        }
        if ((now - packet.timestamp) > packet.ttlMs) {
            logger.log(MeshLogLevel.DEBUG, "GossipRouter", "Dropping packet ${packet.packetId}: TTL expired")
            return false
        }

        // 4. Calculate Link-State Dijkstra Metric Cost
        val transport = when (packet.transportType.uppercase()) {
            "LAN" -> MeshTransport.LAN
            "WIFI" -> MeshTransport.WIFI
            else -> MeshTransport.BLE
        }
        val linkCost = getTransportCost(transport)
        val routeCost = (packet.hopCount + 1) * linkCost

        mutex.withLock {
            val existing = routingTable[packet.senderNodeId]
            val shouldUpdateDirect = existing == null ||
                routeCost < existing.metricCost ||
                (routeCost == existing.metricCost && existing.nextHopEndpointId == fromEndpointId)
            if (shouldUpdateDirect) {
                routingTable[packet.senderNodeId] = RouteEntry(
                    destinationNodeId = packet.senderNodeId,
                    nextHopEndpointId = fromEndpointId,
                    hops = packet.hopCount + 1,
                    metricCost = routeCost,
                    transport = transport,
                    lastSeen = now,
                    socialMode = packet.socialMode,
                    nickname = if (packet.socialMode == MeshSocialMode.PUBLIC) packet.nickname else null,
                    moodEmoji = if (packet.socialMode == MeshSocialMode.PUBLIC) packet.moodEmoji else null,
                    bio = if (packet.socialMode == MeshSocialMode.PUBLIC) packet.bio else null,
                )
            }

            // Update routes to known peers carried in the packet (prevent Equal-Cost flapping & Split-Horizon loops)
            packet.knownRoutes.forEach { peerId ->
                if (peerId != myNodeId && peerId != packet.senderNodeId) {
                    val peerCost = routeCost + linkCost
                    val existingPeer = routingTable[peerId]
                    val shouldUpdatePeer = existingPeer == null ||
                        peerCost < existingPeer.metricCost ||
                        (peerCost == existingPeer.metricCost && existingPeer.nextHopEndpointId == fromEndpointId)
                    if (shouldUpdatePeer) {
                        routingTable[peerId] = RouteEntry(
                            destinationNodeId = peerId,
                            nextHopEndpointId = fromEndpointId,
                            hops = packet.hopCount + 2,
                            metricCost = peerCost,
                            transport = transport,
                            lastSeen = now,
                            socialMode = MeshSocialMode.INCOGNITO,
                            nickname = null,
                            moodEmoji = null,
                            bio = null,
                        )
                    }
                }
            }

            updateDiscoveredNodesFlow()
        }

        // 5. Probabilistic Fanout Gossip (k = 2 or 3 neighbors) to mitigate Broadcast Storms
        val forwardPacket = packet.copy(hopCount = packet.hopCount + 1)
        dispatchFanout(forwardPacket, excludeEndpointId = fromEndpointId, fanoutK = 3)

        return true
    }

    /** Broadcasts our own presence to a sampled subset of neighbors (Fanout Gossip + Split Horizon). */
    suspend fun broadcastDiscoverySync() {
        val now = clock.nowMs()
        val knownIds = mutex.withLock { routingTable.keys.take(20).toList() }

        val packet = GossipPacket(
            packetId = idGenerator.nextId(),
            senderNodeId = myNodeId,
            hopCount = 0,
            maxHops = 4,
            timestamp = now,
            ttlMs = 180_000L,
            transportType = "BLE",
            socialMode = mySocialMode,
            nickname = if (mySocialMode == MeshSocialMode.PUBLIC) myNickname else null,
            moodEmoji = if (mySocialMode == MeshSocialMode.PUBLIC) myMoodEmoji else null,
            bio = if (mySocialMode == MeshSocialMode.PUBLIC) myBio else null,
            knownRoutes = knownIds,
        )

        dispatchFanout(packet, excludeEndpointId = null, fanoutK = 3)
    }

    /** Selects k random neighbors for Gossip fanout and filters advertised routes per neighbor (Split Horizon). */
    private suspend fun dispatchFanout(packet: GossipPacket, excludeEndpointId: String?, fanoutK: Int) {
        val allNeighbors = getConnectedNeighbors?.invoke() ?: emptyList()
        val candidates = allNeighbors.filter { it != excludeEndpointId && it != packet.senderNodeId }
        val selected = if (candidates.size <= fanoutK) candidates else candidates.shuffled().take(fanoutK)

        if (selected.isNotEmpty() && onBroadcastPacketToPeers != null) {
            val poisonMap = mutex.withLock {
                selected.associateWith { neighborId ->
                    routingTable.filterValues { it.nextHopEndpointId == neighborId }.keys
                }
            }
            for (neighborId in selected) {
                val poisonedForNeighbor = poisonMap[neighborId].orEmpty()
                val splitPacket = if (poisonedForNeighbor.isEmpty()) {
                    packet
                } else {
                    packet.copy(knownRoutes = packet.knownRoutes.filterNot { it in poisonedForNeighbor || it == neighborId })
                }
                onBroadcastPacketToPeers?.invoke(splitPacket, listOf(neighborId))
            }
        } else if (onBroadcastPacketToPeers == null) {
            onBroadcastPacket?.invoke(packet, excludeEndpointId)
        }
    }

    /**
     * Local Repair: When an endpoint drops, removes direct routes and re-evaluates
     * alternative paths through other reachable peers.
     */
    suspend fun markEndpointLost(endpointId: String) {
        mutex.withLock {
            val affected = routingTable.filter { it.value.nextHopEndpointId == endpointId }.keys
            if (affected.isNotEmpty()) {
                affected.forEach { routingTable.remove(it) }
                updateDiscoveredNodesFlow()
                logger.log(MeshLogLevel.DEBUG, "GossipRouter", "Local repair: purged ${affected.size} routes associated with dropped endpoint $endpointId")
            }
        }
    }

    suspend fun purgeStaleRoutes() {
        val now = clock.nowMs()
        val ttl = 180_000L // 3 minutes

        mutex.withLock {
            val deadRoutes = routingTable.filter { (now - it.value.lastSeen) > ttl }.keys
            if (deadRoutes.isNotEmpty()) {
                deadRoutes.forEach { routingTable.remove(it) }
                updateDiscoveredNodesFlow()
                logger.log(MeshLogLevel.DEBUG, "GossipRouter", "Purged ${deadRoutes.size} ghost routes")
            }
        }
    }

    fun getNextHop(targetNodeId: String): String? {
        return routingTable[targetNodeId]?.nextHopEndpointId
    }

    private fun updateDiscoveredNodesFlow() {
        _discoveredNodes.value = routingTable.values.map { r ->
            GossipNode(
                nodeId = r.destinationNodeId,
                nextHopEndpointId = r.nextHopEndpointId,
                hops = r.hops,
                metricCost = r.metricCost,
                transport = r.transport,
                socialMode = r.socialMode,
                nickname = r.nickname,
                moodEmoji = r.moodEmoji,
                bio = r.bio,
                latencyMs = (r.metricCost * 20L).coerceAtLeast(10L),
                lastSeen = r.lastSeen,
            )
        }
    }
}
