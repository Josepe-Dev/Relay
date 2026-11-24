package chat.josepe.dev.data.network.mesh

/**
 * Monitors hardware battery state and user charging preferences to safeguard
 * devices acting as volunteer relay nodes in the mesh.
 */
interface BatteryGuardian {
    /** Whether battery and charging conditions permit relaying heavy files (>1MB). */
    fun canRelayFile(): Boolean

    /** Whether battery is below critical threshold (<= 20-25%). */
    fun isBatteryLow(): Boolean

    /** Whether the device is currently plugged into a charger. */
    fun isCharging(): Boolean

    /** Current battery percentage from 0.0 to 1.0 (1.0 = 100%). */
    val batteryLevel: Float
}
