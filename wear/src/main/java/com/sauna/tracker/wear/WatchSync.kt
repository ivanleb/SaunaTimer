package com.sauna.tracker.wear

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.sauna.tracker.shared.SettingsStore
import com.sauna.tracker.shared.sync.BluetoothLink
import com.sauna.tracker.shared.sync.FrameIO
import com.sauna.tracker.shared.sync.LocalSyncStore
import com.sauna.tracker.shared.sync.SyncEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Syncs with the paired phone over a direct, encrypted Bluetooth link (RFCOMM to bonded
 * devices only). No Google services and no internet: data never leaves the two devices.
 * Rounds not confirmed by the phone stay queued in the database and go with the next sync.
 */
object WatchSync {
    enum class Status { OK, NO_PERMISSION, BLUETOOTH_OFF, NO_PHONE, UNREACHABLE }
    data class Outcome(val status: Status, val sent: Int = 0)

    private const val TAG = "WatchSync"
    private val mutex = Mutex()
    private val retryDelaysMs = listOf(15_000L, 60_000L, 5 * 60_000L, 15 * 60_000L)
    @Volatile private var retryJob: Job? = null

    /** Sync now and wait for the result (used by the "Send to phone" button). */
    suspend fun syncNow(context: Context): Outcome = mutex.withLock {
        withContext(Dispatchers.IO) { attempt(context.applicationContext) }
    }

    /**
     * Sync in the background; if the phone is not reachable, retry a few times with growing pauses.
     * A reconnect of the phone ([BluetoothReceiver]) or opening the app also starts a sync.
     */
    fun request(context: Context) {
        val ctx = context.applicationContext
        retryJob?.cancel()
        retryJob = appScope.launch {
            if (syncNow(ctx).status != Status.UNREACHABLE) return@launch
            for (pause in retryDelaysMs) {
                delay(pause)
                if (syncNow(ctx).status != Status.UNREACHABLE) return@launch
            }
        }
    }

    @SuppressLint("MissingPermission") // checked by BluetoothLink.hasPermission
    private suspend fun attempt(ctx: Context): Outcome {
        if (!BluetoothLink.hasPermission(ctx)) return Outcome(Status.NO_PERMISSION)
        val adapter = BluetoothLink.adapter(ctx)
        if (adapter == null || !adapter.isEnabled) return Outcome(Status.BLUETOOTH_OFF)
        val phones = adapter.bondedDevices.filter(::isPhone)
        if (phones.isEmpty()) return Outcome(Status.NO_PHONE)

        val prefs = ctx.getSharedPreferences("sync", Context.MODE_PRIVATE)
        val last = prefs.getString("phone", null)
        for (phone in phones.sortedByDescending { it.address == last }) {
            try {
                val sent = syncWith(ctx, phone)
                prefs.edit { putString("phone", phone.address) }
                SettingsStore(ctx).setLastSync(System.currentTimeMillis())
                Log.i(TAG, "Synced with phone, $sent round(s) sent")
                return Outcome(Status.OK, sent)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.i(TAG, "Phone not reachable: $e")
            }
        }
        return Outcome(Status.UNREACHABLE)
    }

    @SuppressLint("MissingPermission")
    private suspend fun syncWith(ctx: Context, phone: BluetoothDevice): Int = coroutineScope {
        // Secure socket: authenticated and encrypted, only to a device bonded with this watch.
        val socket = phone.createRfcommSocketToServiceRecord(BluetoothLink.SERVICE_UUID)
        // Blocking Bluetooth calls have no timeout of their own: close the socket if it takes too long.
        val watchdog = launch(Dispatchers.IO) { delay(BluetoothLink.TIMEOUT_MS); socket.close() }
        try {
            socket.connect()
            SyncEngine.runWatch(FrameIO(socket.inputStream, socket.outputStream), LocalSyncStore(ctx))
        } finally {
            watchdog.cancel()
            runCatching { socket.close() }
        }
    }

    @SuppressLint("MissingPermission")
    fun isPhone(device: BluetoothDevice): Boolean =
        device.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.PHONE
}
