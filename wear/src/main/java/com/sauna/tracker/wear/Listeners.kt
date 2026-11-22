package com.sauna.tracker.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.sauna.tracker.shared.DataPaths
import com.sauna.tracker.shared.SyncHub
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Receives settings, deletions and acknowledgements from the phone. */
class WearListenerService : WearableListenerService() {
    private val hub by lazy { SyncHub.get(this) }

    override fun onDataChanged(events: DataEventBuffer) {
        val items = events.filter { it.type == DataEvent.TYPE_CHANGED }.map { it.dataItem.freeze() }
        runBlocking { items.forEach { hub.handleDataItem(it, isPhone = false) } }
    }

    override fun onMessageReceived(event: MessageEvent) {
        val path = event.path
        runBlocking {
            when {
                path.startsWith(DataPaths.ACK_PREFIX) -> hub.onAck(path.removePrefix(DataPaths.ACK_PREFIX))
                path == DataPaths.SYNC_REQUEST -> hub.sendAllUnsynced()
            }
        }
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
