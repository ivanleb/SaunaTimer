package com.sauna.tracker.shared

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.sauna.tracker.shared.db.SaunaDb
import com.sauna.tracker.shared.db.loadSession
import com.sauna.tracker.shared.db.sampleEntities
import com.sauna.tracker.shared.db.toEntity
import kotlinx.coroutines.tasks.await

/**
 * Everything that goes over the Wearable Data Layer. Used by both apps.
 *
 * DataItems are persistent: if the other device is out of range, they are delivered
 * when it reconnects (also after a reboot), which gives us an offline queue for free.
 */
class SyncHub(context: Context) {
    private val ctx = context.applicationContext
    private val data = Wearable.getDataClient(ctx)
    private val messages = Wearable.getMessageClient(ctx)
    private val nodes = Wearable.getNodeClient(ctx)
    private val dao = SaunaDb.get(ctx).sessions()
    val settingsStore = SettingsStore(ctx)

    // ---------- settings ----------

    /** Local edit: save and publish to the other device. */
    suspend fun updateSettings(timeLimitMin: Int, hrLimit: Int) {
        val s = Settings(
            timeLimitMin = timeLimitMin.coerceIn(Settings.TIME_RANGE),
            hrLimit = hrLimit.coerceIn(Settings.HR_RANGE),
            updatedAt = System.currentTimeMillis(),
        )
        settingsStore.save(s)
        safely("publish settings") {
            val req = PutDataMapRequest.create(DataPaths.SETTINGS).apply {
                dataMap.putInt(DataPaths.KEY_TIME_LIMIT, s.timeLimitMin)
                dataMap.putInt(DataPaths.KEY_HR_LIMIT, s.hrLimit)
                dataMap.putLong(DataPaths.KEY_UPDATED_AT, s.updatedAt)
            }.asPutDataRequest().setUrgent()
            data.putDataItem(req).await()
        }
    }

    private suspend fun applyRemoteSettings(item: DataItem) {
        val map = DataMapItem.fromDataItem(item).dataMap
        val remote = Settings(
            timeLimitMin = map.getInt(DataPaths.KEY_TIME_LIMIT, Settings.DEFAULT_TIME_LIMIT_MIN),
            hrLimit = map.getInt(DataPaths.KEY_HR_LIMIT, Settings.DEFAULT_HR_LIMIT),
            updatedAt = map.getLong(DataPaths.KEY_UPDATED_AT, 0L),
        )
        if (remote.updatedAt > settingsStore.current().updatedAt) settingsStore.save(remote)
    }

    // ---------- deletions ----------

    /** Delete locally and tell the other device to delete its copy too. */
    suspend fun deleteSession(id: String) {
        dao.delete(id)
        val ids = settingsStore.addDeleted(id)
        safely("publish deletion") {
            data.deleteDataItems(wearUri(DataPaths.SESSION_PREFIX + id)).await()
            val req = PutDataMapRequest.create(DataPaths.DELETED).apply {
                dataMap.putStringArrayList(DataPaths.KEY_IDS, ArrayList(ids))
            }.asPutDataRequest().setUrgent()
            data.putDataItem(req).await()
        }
    }

    private suspend fun applyRemoteDeletions(item: DataItem) {
        val ids = DataMapItem.fromDataItem(item).dataMap.getStringArrayList(DataPaths.KEY_IDS) ?: return
        for (id in ids) {
            if (dao.getSession(id)?.inProgress == true) continue
            dao.delete(id)
            safely("drop session item") { data.deleteDataItems(wearUri(DataPaths.SESSION_PREFIX + id)).await() }
        }
    }

    // ---------- sessions: watch -> phone ----------

    /** Watch: publish a finished session. */
    suspend fun sendSession(id: String) {
        val session = dao.loadSession(id) ?: return
        safely("send session $id") {
            val req = PutDataMapRequest.create(DataPaths.SESSION_PREFIX + id).apply {
                dataMap.putAsset(DataPaths.KEY_DATA, Asset.createFromBytes(SessionCodec.encode(session)))
                // Changes the item even when the content is the same, so a resend is delivered.
                dataMap.putLong(DataPaths.KEY_SENT_AT, System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()
            data.putDataItem(req).await()
        }
    }

    /** Watch: publish every finished session the phone has not confirmed yet. */
    suspend fun sendAllUnsynced(): Int {
        val pending = dao.unsynced()
        pending.forEach { sendSession(it.id) }
        return pending.size
    }

    /** Watch: phone confirmed it stored the session. */
    suspend fun onAck(id: String) {
        dao.markSynced(id)
        safely("drop session item") { data.deleteDataItems(wearUri(DataPaths.SESSION_PREFIX + id)).await() }
    }

    /** Phone: store a session DataItem and acknowledge it to the sender. */
    private suspend fun importSession(item: DataItem) {
        val id = item.uri.path?.removePrefix(DataPaths.SESSION_PREFIX) ?: return
        val sender = item.uri.host
        if (id !in settingsStore.deletedIds()) {
            val asset = DataMapItem.fromDataItem(item).dataMap.getAsset(DataPaths.KEY_DATA) ?: return
            val bytes = data.getFdForAsset(asset).await().inputStream.use { it.readBytes() }
            val session = SessionCodec.decode(bytes)
            dao.replaceSession(session.toEntity(synced = true), session.sampleEntities())
        }
        if (sender != null) {
            safely("ack $id") { messages.sendMessage(sender, DataPaths.ACK_PREFIX + id, ByteArray(0)).await() }
        }
    }

    /** Phone: ask the watch to resend anything that has not arrived. */
    suspend fun requestSync(): Boolean = safely("request sync") {
        val connected = nodes.connectedNodes.await()
        connected.forEach { messages.sendMessage(it.id, DataPaths.SYNC_REQUEST, ByteArray(0)).await() }
        connected.isNotEmpty()
    } ?: false

    // ---------- incoming ----------

    /** Handle one changed DataItem from either device. Items we published ourselves are ignored. */
    suspend fun handleDataItem(item: DataItem, isPhone: Boolean) {
        if (item.uri.host == localNodeId()) return
        val path = item.uri.path ?: return
        safely("handle $path") {
            when {
                path == DataPaths.SETTINGS -> applyRemoteSettings(item)
                path == DataPaths.DELETED -> applyRemoteDeletions(item)
                path.startsWith(DataPaths.SESSION_PREFIX) && isPhone -> importSession(item)
            }
        }
    }

    /** Catch up on everything currently in the Data Layer (e.g. on app start). */
    suspend fun pullAll(isPhone: Boolean) {
        safely("pull all") {
            val buffer = data.getDataItems(wearUri("/"), DataClient.FILTER_PREFIX).await()
            val items = try { buffer.map { it.freeze() } } finally { buffer.release() }
            // Deletions first so we don't import something that was just deleted.
            items.sortedBy { if (it.uri.path == DataPaths.DELETED) 0 else 1 }
                .forEach { handleDataItem(it, isPhone) }
        }
    }

    private var cachedLocalId: String? = null
    private suspend fun localNodeId(): String? =
        cachedLocalId ?: safely("local node") { nodes.localNode.await().id }?.also { cachedLocalId = it }

    private fun wearUri(path: String): Uri =
        Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).path(path).build()

    private suspend fun <T> safely(what: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Log.w(TAG, "$what failed", e)
        null
    }

    companion object {
        private const val TAG = "SyncHub"
        @Volatile private var instance: SyncHub? = null
        fun get(context: Context): SyncHub = instance ?: synchronized(this) {
            instance ?: SyncHub(context).also { instance = it }
        }
    }
}
