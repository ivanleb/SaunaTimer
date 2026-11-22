package com.sauna.tracker.shared

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.sauna.tracker.shared.db.SaunaDb
import com.sauna.tracker.shared.db.loadSession
import com.sauna.tracker.shared.db.sampleEntities
import com.sauna.tracker.shared.db.toEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/**
 * Everything exchanged between the watch and the phone. Used by both apps.
 *
 * Privacy rule: data goes **only** to nearby (directly connected) nodes via MessageClient /
 * ChannelClient. No DataClient (its items are backed up to the cloud), no INTERNET permission.
 * If the other device is not nearby, nothing is sent; the "synced" flag in the database is the
 * queue, and everything is retried when the devices reconnect ([onConnected]).
 */
class SyncHub(context: Context) {
    private val ctx = context.applicationContext
    private val messages = Wearable.getMessageClient(ctx)
    private val channels = Wearable.getChannelClient(ctx)
    private val nodes = Wearable.getNodeClient(ctx)
    private val dao = SaunaDb.get(ctx).sessions()
    val settingsStore = SettingsStore(ctx)

    private suspend fun nearbyNodes(): List<Node> =
        safely("list nodes") { nodes.connectedNodes.await().filter { it.isNearby } } ?: emptyList()

    private suspend fun sendToNearby(path: String, payload: ByteArray) {
        for (node in nearbyNodes()) {
            safely("send $path") { messages.sendMessage(node.id, path, payload).await() }
        }
    }

    // ---------- settings ----------

    /** Local edit: save and offer to the other device. */
    suspend fun updateSettings(timeLimitMin: Int, hrLimit: Int) {
        val s = Settings(
            timeLimitMin = timeLimitMin.coerceIn(Settings.TIME_RANGE),
            hrLimit = hrLimit.coerceIn(Settings.HR_RANGE),
            updatedAt = System.currentTimeMillis(),
        )
        settingsStore.save(s)
        sendToNearby(DataPaths.SETTINGS, SessionCodec.encodeSettings(s))
    }

    private suspend fun applyRemoteSettings(payload: ByteArray) {
        val remote = SessionCodec.decodeSettings(payload) ?: return
        if (remote.updatedAt > settingsStore.current().updatedAt) settingsStore.save(remote)
    }

    // ---------- deletions ----------

    /** Delete locally and tell the other device to delete its copy too (now or on reconnect). */
    suspend fun deleteSession(id: String) {
        dao.delete(id)
        val ids = settingsStore.addDeleted(id)
        sendToNearby(DataPaths.DELETED, SessionCodec.encodeIds(ids))
    }

    private suspend fun applyRemoteDeletions(payload: ByteArray) {
        for (id in SessionCodec.decodeIds(payload)) {
            if (dao.getSession(id)?.inProgress == true) continue
            dao.delete(id)
        }
    }

    // ---------- sessions: watch -> phone ----------

    /** Watch: stream a finished session to the phone if it is nearby; otherwise it stays queued. */
    suspend fun sendSession(id: String) {
        val session = dao.loadSession(id) ?: return
        val bytes = SessionCodec.encodeCompressed(session)
        for (node in nearbyNodes()) {
            safely("send session $id") {
                val channel = channels.openChannel(node.id, DataPaths.SESSION_PREFIX + id).await()
                channels.getOutputStream(channel).await().use { it.write(bytes) }
            }
        }
    }

    /** Watch: send every finished session the phone has not confirmed yet. */
    suspend fun sendAllUnsynced(): Int {
        val pending = dao.unsynced()
        pending.forEach { sendSession(it.id) }
        return pending.size
    }

    /** Phone: receive one session over a channel, store it, acknowledge it. */
    suspend fun receiveSession(channel: ChannelClient.Channel) {
        val id = channel.path.removePrefix(DataPaths.SESSION_PREFIX)
        safely("receive session $id") {
            try {
                val bytes = channels.getInputStream(channel).await().use { it.readBytes() }
                if (id !in settingsStore.deletedIds()) {
                    val session = SessionCodec.decodeCompressed(bytes)
                    dao.replaceSession(session.toEntity(synced = true), session.sampleEntities())
                }
                // Ack even if we chose to skip it (deleted here), so the watch stops resending.
                messages.sendMessage(channel.nodeId, DataPaths.ACK_PREFIX + id, ByteArray(0)).await()
            } finally {
                channels.close(channel)
            }
        }
    }

    /** Phone: ask the watch to resend anything that has not arrived. False if no watch is nearby. */
    suspend fun requestSync(): Boolean {
        if (nearbyNodes().isEmpty()) return false
        onConnected(isPhone = true)
        sendToNearby(DataPaths.SYNC_REQUEST, ByteArray(0))
        return true
    }

    /**
     * The other device is (again) reachable, or the app was opened: exchange settings and
     * deletions; the watch also flushes its queue of unsent sessions.
     */
    suspend fun onConnected(isPhone: Boolean) {
        if (nearbyNodes().isEmpty()) return
        sendToNearby(DataPaths.SETTINGS, SessionCodec.encodeSettings(settingsStore.current()))
        val deleted = settingsStore.deletedIds()
        if (deleted.isNotEmpty()) sendToNearby(DataPaths.DELETED, SessionCodec.encodeIds(deleted))
        if (!isPhone) sendAllUnsynced()
    }

    /** Handle a message from the other device. */
    suspend fun handleMessage(event: MessageEvent, isPhone: Boolean) {
        val path = event.path
        safely("handle $path") {
            when {
                path == DataPaths.SETTINGS -> applyRemoteSettings(event.data)
                path == DataPaths.DELETED -> applyRemoteDeletions(event.data)
                path.startsWith(DataPaths.ACK_PREFIX) && !isPhone ->
                    dao.markSynced(path.removePrefix(DataPaths.ACK_PREFIX))
                path == DataPaths.SYNC_REQUEST && !isPhone -> onConnected(isPhone = false)
            }
        }
    }

    private suspend fun <T> safely(what: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: Exception) {
        if (e is CancellationException) throw e
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
