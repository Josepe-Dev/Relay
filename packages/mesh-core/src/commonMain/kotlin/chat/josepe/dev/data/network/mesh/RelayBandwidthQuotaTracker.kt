package chat.josepe.dev.data.network.mesh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Tracks daily relay bandwidth usage and enforces file size and daily quota limits
 * for volunteer relay nodes in the mesh network.
 */
class RelayBandwidthQuotaTracker(
    var maxDailyQuotaBytes: Long = 100L * 1024L * 1024L, // 100 MB per day
    var maxSingleFileSizeBytes: Long = 5L * 1024L * 1024L, // 5 MB max per file
    private val clock: MeshClock = SystemMeshClock,
) {
    private val mutex = Mutex()
    private var lastResetDay: Long = currentDayEpoch()
    private val _bytesRelayedToday = MutableStateFlow(0L)
    val bytesRelayedTodayFlow: StateFlow<Long> = _bytesRelayedToday.asStateFlow()

    val remainingDailyQuotaBytes: Long
        get() = (maxDailyQuotaBytes - _bytesRelayedToday.value).coerceAtLeast(0L)

    val remainingDailyQuotaMb: Int
        get() = (remainingDailyQuotaBytes / (1024L * 1024L)).toInt()

    fun updateDailyQuotaMb(mb: Int) {
        maxDailyQuotaBytes = mb.toLong() * 1024L * 1024L
    }

    suspend fun canRelayFile(fileSizeBytes: Long): Pair<Boolean, RelayNackReason?> = mutex.withLock {
        checkDayReset()
        if (fileSizeBytes > maxSingleFileSizeBytes) {
            return false to RelayNackReason.FILE_TOO_LARGE
        }
        if (_bytesRelayedToday.value + fileSizeBytes > maxDailyQuotaBytes) {
            return false to RelayNackReason.QUOTA_EXCEEDED
        }
        return true to null
    }

    suspend fun recordRelayedBytes(bytes: Long) = mutex.withLock {
        checkDayReset()
        _bytesRelayedToday.value += bytes
    }

    suspend fun getBytesRelayedToday(): Long = mutex.withLock {
        checkDayReset()
        _bytesRelayedToday.value
    }

    private fun checkDayReset() {
        val today = currentDayEpoch()
        if (today != lastResetDay) {
            _bytesRelayedToday.value = 0L
            lastResetDay = today
        }
    }

    private fun currentDayEpoch(): Long = clock.nowMs() / (1000L * 60L * 60L * 24L)
}
