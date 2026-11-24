package chat.josepe.dev.data.network.mesh

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RelayBandwidthQuotaTrackerTest {

    private class FakeClock(var now: Long) : MeshClock {
        override fun nowMs(): Long = now
    }

    @Test
    fun enforcesSingleFileLimitDailyQuotaAndDayRollover() = runBlocking {
        val dayMs = 24L * 60L * 60L * 1000L
        val clock = FakeClock(dayMs * 100)
        val tracker = RelayBandwidthQuotaTracker(
            maxDailyQuotaBytes = 10L * 1024L * 1024L,
            maxSingleFileSizeBytes = 4L * 1024L * 1024L,
            clock = clock,
        )

        val (tooLargeAllowed, tooLargeReason) = tracker.canRelayFile(5L * 1024L * 1024L)
        assertFalse(tooLargeAllowed)
        assertEquals(RelayNackReason.FILE_TOO_LARGE, tooLargeReason)

        assertTrue(tracker.canRelayFile(4L * 1024L * 1024L).first)
        tracker.recordRelayedBytes(8L * 1024L * 1024L)
        assertEquals(2, tracker.remainingDailyQuotaMb)

        val (quotaOk, quotaReason) = tracker.canRelayFile(3L * 1024L * 1024L)
        assertFalse(quotaOk)
        assertEquals(RelayNackReason.QUOTA_EXCEEDED, quotaReason)

        // Advance clock by 1 full UTC day -> quota resets
        clock.now += dayMs
        assertEquals(0L, tracker.getBytesRelayedToday())
        assertTrue(tracker.canRelayFile(3L * 1024L * 1024L).first)
    }
}
