package com.sauna.tracker.wear

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Process-wide scope for work that must outlive a screen (saving, syncing). */
val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

class WearApp : Application() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(SessionService.CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
        // A fresh process means no round is being recorded in memory, so any round
        // still marked "in progress" was cut off (overheat, empty battery, crash).
        appScope.launch { Recovery.run(this@WearApp) }
    }
}
