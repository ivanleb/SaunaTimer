package com.sauna.tracker.wear

import android.content.Context
import android.util.Log
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.ExerciseTrackedStatus
import com.sauna.tracker.shared.SyncHub
import com.sauna.tracker.shared.db.SaunaDb
import com.sauna.tracker.shared.db.finish
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Finishes rounds that were cut off (watch overheated and shut down, battery died, app killed).
 * Everything recorded up to the last periodic save is kept; the round is marked "interrupted"
 * and queued for the phone.
 */
object Recovery {
    private val mutex = Mutex()

    suspend fun run(context: Context) = mutex.withLock {
        val ctx = context.applicationContext
        val dao = SaunaDb.get(ctx).sessions()
        val live = LiveSession.state.value
        val orphans = dao.inProgress().filter { it.id != live.sessionId }

        if (orphans.isNotEmpty() && !live.running) endLeftoverExercise(ctx)
        orphans.forEach {
            Log.i(TAG, "Recovering interrupted round ${it.id}")
            dao.finish(it.id, durationMs = null, interrupted = true)
        }
        SyncHub.get(ctx).sendAllUnsynced()
    }

    /** Health Services keeps an exercise running after our process dies; stop it to save battery. */
    private suspend fun endLeftoverExercise(ctx: Context) {
        try {
            val client = HealthServices.getClient(ctx).exerciseClient
            val info = client.getCurrentExerciseInfoAsync().await()
            if (info.exerciseTrackedStatus == ExerciseTrackedStatus.OWNED_EXERCISE_IN_PROGRESS) {
                client.endExerciseAsync().await()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not end leftover exercise", e)
        }
    }

    private const val TAG = "Recovery"
}
