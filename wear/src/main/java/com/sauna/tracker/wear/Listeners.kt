package com.sauna.tracker.wear

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

/** The phone came back in Bluetooth range: send whatever is queued. */
class BluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        val device = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        if (device == null || !WatchSync.isPhone(device)) return
        val pending = goAsync()
        appScope.launch {
            try {
                delay(3_000) // let the link settle
                WatchSync.syncNow(context)
            } finally {
                pending.finish()
            }
        }
    }
}
