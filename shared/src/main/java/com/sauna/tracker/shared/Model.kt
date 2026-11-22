package com.sauna.tracker.shared

import kotlinx.serialization.Serializable

/** One heart-rate reading. [t] = milliseconds since session start. */
@Serializable
data class HrSample(val t: Long, val bpm: Int)

/** A single sauna round. */
@Serializable
data class Session(
    val id: String,
    val startedAt: Long,
    val durationMs: Long,
    val timeLimitMin: Int,
    val hrLimit: Int,
    val samples: List<HrSample>,
    /** True if the round was not stopped normally (watch overheated, battery died, app killed). */
    val interrupted: Boolean = false,
) {
    val maxBpm: Int get() = SessionStats.max(samples)
    val avgBpm: Int get() = SessionStats.avg(samples)
    val timeAboveLimitMs: Long get() = SessionStats.timeAtOrAbove(samples, hrLimit, durationMs)
}

data class Settings(
    val timeLimitMin: Int = DEFAULT_TIME_LIMIT_MIN,
    val hrLimit: Int = DEFAULT_HR_LIMIT,
    val updatedAt: Long = 0L,
) {
    companion object {
        const val DEFAULT_TIME_LIMIT_MIN = 15
        const val DEFAULT_HR_LIMIT = 140
        val TIME_RANGE = 1..60
        val HR_RANGE = 80..200
    }
}

object SessionStats {
    private const val MAX_GAP_MS = 15_000L

    fun max(samples: List<HrSample>): Int = samples.maxOfOrNull { it.bpm } ?: 0

    /** Time-weighted average, so irregular sampling does not skew the result. */
    fun avg(samples: List<HrSample>): Int {
        if (samples.isEmpty()) return 0
        if (samples.size == 1) return samples[0].bpm
        var weighted = 0.0
        var total = 0L
        for (i in 0 until samples.size - 1) {
            val dt = (samples[i + 1].t - samples[i].t).coerceIn(0, MAX_GAP_MS)
            weighted += samples[i].bpm * dt.toDouble()
            total += dt
        }
        return if (total == 0L) samples.map { it.bpm }.average().toInt()
        else Math.round(weighted / total).toInt()
    }

    /** Total time the heart rate was at or above [limit]. */
    fun timeAtOrAbove(samples: List<HrSample>, limit: Int, durationMs: Long): Long {
        var total = 0L
        for (i in samples.indices) {
            if (samples[i].bpm < limit) continue
            val end = if (i + 1 < samples.size) samples[i + 1].t else durationMs
            total += (end - samples[i].t).coerceIn(0, MAX_GAP_MS)
        }
        return total
    }
}

fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
