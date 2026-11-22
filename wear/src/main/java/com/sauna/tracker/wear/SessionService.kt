package com.sauna.tracker.wear

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.health.services.client.ExerciseClient
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.BatchingMode
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.sauna.tracker.shared.AlertPolicy
import com.sauna.tracker.shared.HrSample
import com.sauna.tracker.shared.SyncHub
import com.sauna.tracker.shared.db.SampleEntity
import com.sauna.tracker.shared.db.SaunaDb
import com.sauna.tracker.shared.db.SessionEntity
import com.sauna.tracker.shared.db.finish
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Records one sauna round: timer, heart rate (Health Services), vibration alerts.
 * Runs as a foreground service so it keeps going with the screen off.
 *
 * Data is written to the database every [FLUSH_EVERY_S] seconds, so if the watch shuts down
 * (overheating, empty battery) at most a few seconds are lost; [Recovery] finishes the round later.
 */
class SessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val dao by lazy { SaunaDb.get(this).sessions() }
    private var exerciseClient: ExerciseClient? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var tickJob: Job? = null
    private var policy: AlertPolicy? = null
    private var sessionId: String? = null
    private var startElapsed = 0L
    private var stopping = false

    /** Samples received but not yet written to the database. Touched on the main thread only. */
    private val unsaved = mutableListOf<SampleEntity>()
    private var lastSampleElapsed = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_STOP -> stop()
        }
        return START_NOT_STICKY
    }

    private fun start() {
        if (sessionId != null) return
        val id = UUID.randomUUID().toString()
        sessionId = id
        startElapsed = SystemClock.elapsedRealtime()
        val startedAt = System.currentTimeMillis()

        goForeground()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SaunaTracker:round")
            .apply { acquire(MAX_ROUND_MS) }

        LiveSession.update { LiveState(running = true, sessionId = id, startElapsed = startElapsed) }

        scope.launch {
            Recovery.run(this@SessionService) // close any older cut-off round first
            val settings = SyncHub.get(this@SessionService).settingsStore.current()
            // Write the row right away: from now on the round survives a shutdown.
            dao.upsertSession(
                SessionEntity(
                    id = id, startedAt = startedAt, durationMs = 0,
                    timeLimitMin = settings.timeLimitMin, hrLimit = settings.hrLimit,
                    inProgress = true,
                )
            )
            policy = AlertPolicy(settings.timeLimitMin * 60_000L, settings.hrLimit)
            LiveSession.update { it.copy(timeLimitMin = settings.timeLimitMin, hrLimit = settings.hrLimit) }
            startHeartRate()
            tickJob = launch { tickLoop(id) }
        }
    }

    private suspend fun tickLoop(id: String) {
        var tick = 0
        while (scope.isActive) {
            val now = SystemClock.elapsedRealtime()
            val elapsed = now - startElapsed
            val fresh = LiveSession.state.value.bpm?.takeIf { now - lastSampleElapsed <= STALE_MS }
            policy?.onTick(elapsed, fresh)?.forEach { Vibration.play(this, it) }
            LiveSession.update { it.copy(elapsedMs = elapsed, bpm = fresh) }
            if (++tick % FLUSH_EVERY_S == 0) flush(id, elapsed)
            delay(1000 - (SystemClock.elapsedRealtime() - startElapsed) % 1000)
        }
    }

    private suspend fun flush(id: String, elapsed: Long) {
        val batch = unsaved.toList()
        unsaved.clear()
        try {
            withContext(NonCancellable + Dispatchers.IO) { dao.appendProgress(id, elapsed, batch) }
        } catch (e: Exception) {
            Log.e(TAG, "Saving progress failed", e)
            unsaved.addAll(0, batch)
        }
    }

    private suspend fun startHeartRate() {
        try {
            val client = HealthServices.getClient(this).exerciseClient
            exerciseClient = client
            client.setUpdateCallback(ContextCompat.getMainExecutor(this), callback)
            val caps = client.getCapabilitiesAsync().await()
            val type = if (ExerciseType.WORKOUT in caps.supportedExerciseTypes) ExerciseType.WORKOUT
            else caps.supportedExerciseTypes.first()
            val config = ExerciseConfig.builder(type)
                .setDataTypes(setOf(DataType.HEART_RATE_BPM))
                .setIsAutoPauseAndResumeEnabled(false)
                .setIsGpsEnabled(false)
            // Without this, readings arrive in large batches while the screen is off,
            // which would delay the heart-rate alert.
            if (BatchingMode.HEART_RATE_5_SECONDS in caps.supportedBatchingModeOverrides) {
                config.setBatchingModeOverrides(setOf(BatchingMode.HEART_RATE_5_SECONDS))
            }
            client.startExerciseAsync(config.build()).await()
        } catch (e: Exception) {
            Log.e(TAG, "Heart rate start failed", e)
            LiveSession.update { it.copy(sensorError = true) }
        }
    }

    private val callback = object : ExerciseUpdateCallback {
        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            if (sessionId == null) return
            val points = update.latestMetrics.getData(DataType.HEART_RATE_BPM)
            if (points.isEmpty()) return
            val fresh = mutableListOf<HrSample>()
            var lastT = LiveSession.state.value.samples.lastOrNull()?.t ?: -1L
            for (p in points.sortedBy { it.timeDurationFromBoot }) {
                val pointElapsed = p.timeDurationFromBoot.toMillis()
                val t = pointElapsed - startElapsed
                val bpm = p.value.roundToInt()
                if (t <= lastT || t < 0 || bpm !in 30..250) continue
                fresh += HrSample(t, bpm)
                lastT = t
                lastSampleElapsed = maxOf(lastSampleElapsed, pointElapsed)
            }
            if (fresh.isEmpty()) return
            val id = sessionId ?: return
            unsaved += fresh.map { SampleEntity(id, it.t, it.bpm) }
            LiveSession.update { it.copy(samples = it.samples + fresh, bpm = fresh.last().bpm, sensorError = false) }
        }

        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) {}
        override fun onRegistered() {}
        override fun onRegistrationFailed(throwable: Throwable) {
            Log.e(TAG, "Exercise callback registration failed", throwable)
        }
        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {}
    }

    private fun stop() {
        val id = sessionId ?: run { stopSelf(); return }
        if (stopping) return
        stopping = true
        scope.launch {
            tickJob?.cancelAndJoin()
            val elapsed = SystemClock.elapsedRealtime() - startElapsed
            withContext(NonCancellable) {
                try { exerciseClient?.endExerciseAsync()?.await() } catch (e: Exception) { Log.w(TAG, "endExercise", e) }
                try { exerciseClient?.clearUpdateCallbackAsync(callback)?.await() } catch (_: Exception) {}
                flush(id, elapsed)
                withContext(Dispatchers.IO) { dao.finish(id, elapsed, interrupted = false) }
                LiveSession.update { LiveState(lastFinishedId = id) }
                sessionId = null
                appScope.launch { SyncHub.get(this@SessionService).sendSession(id) }
            }
            releaseAndStop()
        }
    }

    private fun releaseAndStop() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // Killed by the system without Stop: save what we have; the round is finished as interrupted.
        val id = sessionId
        if (id != null) {
            val batch = unsaved.toList()
            val elapsed = SystemClock.elapsedRealtime() - startElapsed
            appScope.launch {
                dao.appendProgress(id, elapsed, batch)
                dao.finish(id, elapsed, interrupted = true)
                SyncHub.get(applicationContext).sendSession(id)
            }
            LiveSession.update { LiveState(lastFinishedId = id) }
            exerciseClient?.endExerciseAsync()
        }
        wakeLock?.takeIf { it.isHeld }?.release()
        scope.cancel()
        super.onDestroy()
    }

    private fun goForeground() {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_heart)
            .setContentTitle(getString(R.string.notif_title))
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setOngoing(true)
            .setContentIntent(openApp)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        // Shows the running timer on the watch face and lets the user jump back in.
        OngoingActivity.Builder(applicationContext, NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_heart)
            .setTouchIntent(openApp)
            .setStatus(
                Status.Builder()
                    .addTemplate("#time#")
                    .addPart("time", Status.StopwatchPart(startElapsed))
                    .build()
            )
            .build()
            .apply(applicationContext)

        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, builder.build(), type)
    }

    companion object {
        const val CHANNEL_ID = "round"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_START = "start"
        private const val ACTION_STOP = "stop"
        private const val FLUSH_EVERY_S = 5
        private const val STALE_MS = 15_000L
        private const val MAX_ROUND_MS = 3 * 60 * 60 * 1000L
        private const val TAG = "SessionService"

        fun start(ctx: Context) = ContextCompat.startForegroundService(
            ctx, Intent(ctx, SessionService::class.java).setAction(ACTION_START)
        )

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, SessionService::class.java).setAction(ACTION_STOP))
        }
    }
}
