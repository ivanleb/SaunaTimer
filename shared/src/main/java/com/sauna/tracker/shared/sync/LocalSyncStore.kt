package com.sauna.tracker.shared.sync

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.sauna.tracker.shared.Session
import com.sauna.tracker.shared.Settings
import com.sauna.tracker.shared.SettingsStore
import com.sauna.tracker.shared.db.SaunaDb
import com.sauna.tracker.shared.db.loadSession
import com.sauna.tracker.shared.db.sampleEntities
import com.sauna.tracker.shared.db.toEntity
import java.util.UUID

/** [SyncStore] backed by the app's Room database and DataStore. */
class LocalSyncStore(context: Context) : SyncStore {
    private val ctx = context.applicationContext
    private val dao = SaunaDb.get(ctx).sessions()
    private val prefs = SettingsStore(ctx)

    override suspend fun settings(): Settings = prefs.current()
    override suspend fun saveSettings(settings: Settings) = prefs.save(settings)
    override suspend fun deletedIds(): List<String> = prefs.deletedIds()

    override suspend fun applyRemoteDeletions(ids: List<String>) {
        for (id in ids) {
            if (dao.getSession(id)?.inProgress == true) continue
            dao.delete(id)
        }
    }

    override suspend fun pendingSessions(): List<Session> = dao.unsynced().mapNotNull { dao.loadSession(it.id) }
    override suspend fun markSynced(id: String) = dao.markSynced(id)

    override suspend fun storeReceived(session: Session) {
        if (session.id in prefs.deletedIds()) return
        dao.replaceSession(session.toEntity(synced = true), session.sampleEntities())
    }
}

/** Constants and checks shared by both ends of the Bluetooth link. */
object BluetoothLink {
    /** SDP service id the phone listens on and the watch connects to. */
    val SERVICE_UUID: UUID = UUID.fromString("c5a7e0d2-3b1f-4e8a-9d6c-5f2b7a1e8c43")
    const val SERVICE_NAME = "Sauna Pulse sync"
    /** A whole sync must finish within this time, otherwise the socket is closed. */
    const val TIMEOUT_MS = 60_000L

    /** The runtime permission needed for Bluetooth sockets, or null below Android 12. */
    val PERMISSION: String? = if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_CONNECT else null

    fun hasPermission(ctx: Context): Boolean = PERMISSION == null ||
        ctx.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun adapter(ctx: Context): BluetoothAdapter? =
        ctx.getSystemService(BluetoothManager::class.java)?.adapter
}
