package chat.josepe.dev.data.network.mesh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GossipRouterTest {

    private class FakeClock(var now: Long = 1_000_000L) : MeshClock {
        override fun nowMs(): Long = now
    }

    @Test
    fun weightedDijkstraPrefersLanOverWifiAndBle() = runBlocking {
        val clock = FakeClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val router = GossipRouter(scope = scope, clock = clock)
        router.myNodeId = "node_me"

        val blePacket = GossipPacket(
            packetId = "p_ble",
            senderNodeId = "node_target",
            hopCount = 0,
            timestamp = clock.nowMs(),
            transportType = "BLE",
        )
        assertTrue(router.onPacketReceived(blePacket, fromEndpointId = "ep_ble"))
        assertEquals("ep_ble", router.getNextHop("node_target"))

        val wifiPacket = GossipPacket(
            packetId = "p_wifi",
            senderNodeId = "node_target",
            hopCount = 0,
            timestamp = clock.nowMs(),
            transportType = "WIFI",
        )
        assertTrue(router.onPacketReceived(wifiPacket, fromEndpointId = "ep_wifi"))
        assertEquals("ep_wifi", router.getNextHop("node_target"))

        val lanPacket = GossipPacket(
            packetId = "p_lan",
            senderNodeId = "node_target",
            hopCount = 0,
            timestamp = clock.nowMs(),
            transportType = "LAN",
        )
        assertTrue(router.onPacketReceived(lanPacket, fromEndpointId = "ep_lan"))
        assertEquals("ep_lan", router.getNextHop("node_target"))

        // Subsequent worse BLE route must NOT overwrite better LAN route
        val lateBle = blePacket.copy(packetId = "p_ble_2")
        assertTrue(router.onPacketReceived(lateBle, fromEndpointId = "ep_ble"))
        assertEquals("ep_lan", router.getNextHop("node_target"))
        scope.cancel()
    }

    @Test
    fun dropsDuplicateOwnExpiredAndMaxHopPackets() = runBlocking {
        val clock = FakeClock(1_000_000L)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val router = GossipRouter(scope = scope, clock = clock)
        router.myNodeId = "node_me"

        val valid = GossipPacket(
            packetId = "pkt_1",
            senderNodeId = "node_a",
            hopCount = 0,
            maxHops = 4,
            timestamp = clock.nowMs(),
            ttlMs = 60_000L,
            transportType = "LAN",
        )
        assertTrue(router.onPacketReceived(valid, "ep_a"))
        assertFalse(router.onPacketReceived(valid, "ep_a")) // LRU dedup

        val own = valid.copy(packetId = "pkt_own", senderNodeId = "node_me")
        assertFalse(router.onPacketReceived(own, "ep_a"))

        val tooManyHops = valid.copy(packetId = "pkt_hops", hopCount = 4)
        assertFalse(router.onPacketReceived(tooManyHops, "ep_a"))

        val expired = valid.copy(packetId = "pkt_exp", timestamp = clock.nowMs() - 60_001L)
        assertFalse(router.onPacketReceived(expired, "ep_a"))
        scope.cancel()
    }

    @Test
    fun splitHorizonExcludesRoutesLearnedFromTargetNeighbor() = runBlocking {
        val clock = FakeClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val router = GossipRouter(scope = scope, clock = clock)
        router.myNodeId = "node_me"
        router.getConnectedNeighbors = { listOf("ep_a", "ep_b") }

        val sentByEndpoint = mutableMapOf<String, GossipPacket>()
        router.onBroadcastPacketToPeers = { packet, targets ->
            targets.forEach { sentByEndpoint[it] = packet }
        }

        // Learn route to node_c via ep_a
        router.onPacketReceived(
            GossipPacket(
                packetId = "pkt_learn",
                senderNodeId = "node_a",
                hopCount = 0,
                timestamp = clock.nowMs(),
                transportType = "LAN",
                knownRoutes = listOf("node_c"),
            ),
            fromEndpointId = "ep_a",
        )

        sentByEndpoint.clear()
        router.broadcastDiscoverySync()

        val packetForA = sentByEndpoint["ep_a"]
        val packetForB = sentByEndpoint["ep_b"]
        assertTrue(packetForA != null && packetForB != null)
        assertFalse("node_c" in packetForA.knownRoutes)
        assertTrue("node_c" in packetForB.knownRoutes)
        scope.cancel()
    }

    @Test
    fun quarantineDropsPacketsAndMarkEndpointLostRepairsRoutes() = runBlocking {
        val clock = FakeClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val router = GossipRouter(scope = scope, clock = clock)
        router.myNodeId = "node_me"

        router.quarantineNode("node_bad")
        assertFalse(
            router.onPacketReceived(
                GossipPacket(packetId = "bad_pkt", senderNodeId = "node_bad", timestamp = clock.nowMs()),
                fromEndpointId = "ep_bad",
            )
        )

        assertTrue(
            router.onPacketReceived(
                GossipPacket(
                    packetId = "good_pkt",
                    senderNodeId = "node_good",
                    timestamp = clock.nowMs(),
                    knownRoutes = listOf("node_behind"),
                ),
                fromEndpointId = "ep_good",
            )
        )
        assertEquals("ep_good", router.getNextHop("node_good"))
        assertEquals("ep_good", router.getNextHop("node_behind"))

        router.markEndpointLost("ep_good")
        assertNull(router.getNextHop("node_good"))
        assertNull(router.getNextHop("node_behind"))

        // Stale route purge using fake clock
        assertTrue(
            router.onPacketReceived(
                GossipPacket(packetId = "good_pkt_2", senderNodeId = "node_temp", timestamp = clock.nowMs()),
                fromEndpointId = "ep_temp",
            )
        )
        clock.now += 200_000L
        router.purgeStaleRoutes()
        assertNull(router.getNextHop("node_temp"))
        scope.cancel()
    }
}
