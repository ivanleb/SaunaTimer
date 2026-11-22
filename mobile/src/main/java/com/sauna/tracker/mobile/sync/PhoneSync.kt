package com.sauna.tracker.mobile.sync

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.CompanionDeviceService
import android.content.Context
import android.content.IntentSender
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.sauna.tracker.shared.SettingsStore
import com.sauna.tracker.shared.sync.BluetoothLink
import com.sauna.tracker.shared.sync.FrameIO
import com.sauna.tracker.shared.sync.LocalSyncStore
import com.sauna.tracker.shared.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Listens for the watch on a Bluetooth RFCOMM socket. The watch connects after each round
 * (and when it comes back in range). Runs while the app is open ("ui") and, on Android 12+,
 * while the system reports the associated watch nearby ("presence", see [WatchPresenceService]).
 */
object PhoneSyncServer {
    private const val TAG = "PhoneSyncServer"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val holders = mutableSetOf<String>()
    private var server: BluetoothServerSocket? = null
    private var loop: Job? = null

    @Synchronized
    fun acquire(context: Context, reason: String) {
        holders += reason
        if (loop?.isActive != true) start(context.applicationContext)
    }

    @Synchronized
    fun release(reason: String) {
        holders -= reason
        if (holders.isEmpty()) {
            runCatching { server?.close() }
            server = null
            loop?.cancel()
            loop = null
        }
    }

    @SuppressLint("MissingPermission") // checked by BluetoothLink.hasPermission
    private fun start(ctx: Context) {
        if (!BluetoothLink.hasPermission(ctx)) return
        val adapter = BluetoothLink.adapter(ctx) ?: return
        if (!adapter.isEnabled) return
        val socket = try {
            // Secure: only bonded devices can connect, and the link is encrypted.
            adapter.listenUsingRfcommWithServiceRecord(BluetoothLink.SERVICE_NAME, BluetoothLink.SERVICE_UUID)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot listen", e)
            return
        }
        server = socket
        loop = scope.launch {
            Log.i(TAG, "Waiting for the watch")
            while (isActive) {
                val client = try { socket.accept() } catch (e: Exception) { break } // closed or Bluetooth off
                launch { handle(ctx, client) }
            }
            Log.i(TAG, "Stopped listening")
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun handle(ctx: Context, client: BluetoothSocket) {
        val watchdog = scope.launch { delay(BluetoothLink.TIMEOUT_MS); client.close() }
        try {
            val allowed = WatchPairing.associatedAddresses(ctx)
            if (allowed.isNotEmpty() && allowed.none { it.equals(client.remoteDevice.address, ignoreCase = true) }) {
                Log.i(TAG, "Ignoring a device that is not the selected watch")
                return
            }
            val received = SyncEngine.runPhone(FrameIO(client.inputStream, client.outputStream), LocalSyncStore(ctx))
            SettingsStore(ctx).setLastSync(System.currentTimeMillis())
            Log.i(TAG, "Sync done, $received round(s) received")
        } catch (e: Exception) {
            Log.w(TAG, "Sync failed: $e")
        } finally {
            watchdog.cancel()
            runCatching { client.close() }
        }
    }
}

/**
 * The system binds this service while the associated watch is nearby and keeps the app alive,
 * so rounds arrive even when the phone app is closed (Android 12+).
 */
@RequiresApi(31)
class WatchPresenceService : CompanionDeviceService() {
    @Deprecated("Replaced by the AssociationInfo variant on Android 13")
    override fun onDeviceAppeared(address: String) = PhoneSyncServer.acquire(this, "presence")

    @Deprecated("Replaced by the AssociationInfo variant on Android 13")
    override fun onDeviceDisappeared(address: String) = PhoneSyncServer.release("presence")

    @RequiresApi(33)
    override fun onDeviceAppeared(associationInfo: AssociationInfo) = PhoneSyncServer.acquire(this, "presence")

    @RequiresApi(33)
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) = PhoneSyncServer.release("presence")

    override fun onDestroy() {
        PhoneSyncServer.release("presence")
        super.onDestroy()
    }
}

/** One-time selection of the watch through the system's companion device picker. */
object WatchPairing {
    /** Background sync needs presence events, available from Android 12. */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= 31

    private fun cdm(ctx: Context): CompanionDeviceManager? = ctx.getSystemService(CompanionDeviceManager::class.java)

    fun associatedAddresses(ctx: Context): List<String> {
        val cdm = cdm(ctx) ?: return emptyList()
        return if (Build.VERSION.SDK_INT >= 33) {
            cdm.myAssociations.mapNotNull { it.deviceMacAddress?.toString() }
        } else {
            @Suppress("DEPRECATION") cdm.associations
        }
    }

    data class Paired(val name: String, val address: String, val isWatch: Boolean)

    /** Devices already paired with this phone, watches first; readable names instead of a scan. */
    @SuppressLint("MissingPermission")
    fun pairedDevices(ctx: Context): List<Paired> {
        if (!BluetoothLink.hasPermission(ctx)) return emptyList()
        val bonded = BluetoothLink.adapter(ctx)?.bondedDevices.orEmpty()
        return bonded.map {
            val name = it.name ?: it.address
            val watch = it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.WEARABLE ||
                name.contains("watch", ignoreCase = true)
            Paired(name, it.address, watch)
        }.sortedWith(compareByDescending<Paired> { it.isWatch }.thenBy { it.name.lowercase() })
    }

    /** Asks the system to confirm the chosen paired device; [onPicker] receives the dialog to launch. */
    fun associate(ctx: Context, address: String, onPicker: (IntentSender) -> Unit, onFailure: (CharSequence?) -> Unit) {
        val cdm = cdm(ctx) ?: return onFailure(null)
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().setAddress(address).build())
            .setSingleDevice(true)
            .build()
        val callback = object : CompanionDeviceManager.Callback() {
            @Deprecated("Replaced by onAssociationPending on Android 13")
            override fun onDeviceFound(intentSender: IntentSender) = onPicker(intentSender)
            override fun onAssociationPending(intentSender: IntentSender) = onPicker(intentSender)
            override fun onFailure(error: CharSequence?) = onFailure(error)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            cdm.associate(request, ctx.mainExecutor, callback)
        } else {
            @Suppress("DEPRECATION") cdm.associate(request, callback, null)
        }
    }

    /** After the user picked the watch: ask the system to tell us when it is nearby. */
    @SuppressLint("MissingPermission")
    fun startObserving(ctx: Context) {
        if (!supported) return
        val cdm = cdm(ctx) ?: return
        for (address in associatedAddresses(ctx)) {
            try {
                @Suppress("DEPRECATION") cdm.startObservingDevicePresence(address)
            } catch (e: Exception) {
                Log.w("WatchPairing", "Cannot observe $address", e)
            }
        }
    }

    fun forget(ctx: Context) {
        val cdm = cdm(ctx) ?: return
        if (Build.VERSION.SDK_INT >= 33) {
            cdm.myAssociations.forEach { cdm.disassociate(it.id) }
        } else {
            @Suppress("DEPRECATION") associatedAddresses(ctx).forEach { cdm.disassociate(it) }
        }
    }
}
