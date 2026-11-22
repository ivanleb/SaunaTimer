package com.sauna.tracker.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.sauna.tracker.shared.SyncHub
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Messages and reconnects from the phone. Everything arrives directly from the nearby phone;
 * nothing goes through the cloud (see SyncHub).
 */
class WearListenerService : WearableListenerService() {
    private val hub by lazy { SyncHub.get(this) }

    override fun onMessageReceived(event: MessageEvent) {
        runBlocking { hub.handleMessage(event, isPhone = false) }
    }

    /** The phone app became reachable (Bluetooth reconnected): send what is queued. */
    override fun onCapabilityChanged(info: CapabilityInfo) {
        if (info.nodes.any { it.isNearby }) runBlocking { hub.onConnected(isPhone = false) }
    }
}

/** After the watch restarts (e.g. after shutting down from heat), recover and send the cut-off round. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        appScope.launch {
            try { Recovery.run(context) } finally { pending.finish() }
        }
    }
}
