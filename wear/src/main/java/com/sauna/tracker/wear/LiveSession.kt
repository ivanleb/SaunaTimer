package com.sauna.tracker.wear

import com.sauna.tracker.shared.HrSample
import com.sauna.tracker.shared.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class LiveState(
    val running: Boolean = false,
    val sessionId: String? = null,
    val startElapsed: Long = 0L,
    val elapsedMs: Long = 0L,
    val timeLimitMin: Int = Settings.DEFAULT_TIME_LIMIT_MIN,
    val hrLimit: Int = Settings.DEFAULT_HR_LIMIT,
    /** Latest reading if it is recent, otherwise null. */
    val bpm: Int? = null,
    val samples: List<HrSample> = emptyList(),
    val sensorError: Boolean = false,
    /** Set when a round has just been stopped, so the UI can show its summary. */
    val lastFinishedId: String? = null,
)

/** In-memory state of the round being recorded, shared by [SessionService] and the UI. */
object LiveSession {
    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state

    fun update(block: (LiveState) -> LiveState) = _state.update(block)
}
