package com.sauna.tracker.shared

enum class Alert { TIME_LIMIT, HR_LIMIT }

/**
 * Decides when to vibrate.
 * - Time limit: once, when elapsed time reaches the limit.
 * - HR limit: as soon as bpm reaches the limit, then every [hrRepeatMs] while it stays there.
 *   Dropping below the limit resets it, so the next crossing alerts immediately.
 */
class AlertPolicy(
    private val timeLimitMs: Long,
    private val hrLimit: Int,
    private val hrRepeatMs: Long = 30_000L,
) {
    private var timeAlerted = false
    private var lastHrAlertAt: Long? = null

    /** @param bpm latest fresh reading, or null if there is no recent reading. */
    fun onTick(elapsedMs: Long, bpm: Int?): List<Alert> {
        val alerts = mutableListOf<Alert>()
        if (!timeAlerted && elapsedMs >= timeLimitMs) {
            timeAlerted = true
            alerts += Alert.TIME_LIMIT
        }
        if (bpm != null) {
            if (bpm >= hrLimit) {
                val last = lastHrAlertAt
                if (last == null || elapsedMs - last >= hrRepeatMs) {
                    lastHrAlertAt = elapsedMs
                    alerts += Alert.HR_LIMIT
                }
            } else {
                lastHrAlertAt = null
            }
        }
        return alerts
    }
}
