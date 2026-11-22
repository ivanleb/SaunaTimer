package com.sauna.tracker.shared.sync

import com.sauna.tracker.shared.Session
import com.sauna.tracker.shared.SessionCodec
import com.sauna.tracker.shared.Settings
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Watch <-> phone sync over a direct Bluetooth link (no Google services, no internet).
 *
 * Every frame is `[type: 1 byte][length: 4 bytes][payload]`. One sync, started by the watch:
 * ```
 * watch -> HELLO(settings), DELETED(ids)
 * phone -> HELLO(settings), DELETED(ids)       both sides: newest settings win, apply deletions
 * watch -> SESSION(gzip json)  phone -> ACK(id) repeated for every round the phone hasn't confirmed
 * watch -> DONE
 * ```
 */
object Frame {
    const val HELLO = 1
    const val DELETED = 2
    const val SESSION = 3
    const val ACK = 4
    const val DONE = 5
}

class ProtocolException(message: String) : IOException(message)

/** What the sync needs from local storage; Room/DataStore in the apps, a fake in tests. */
interface SyncStore {
    suspend fun settings(): Settings
    suspend fun saveSettings(settings: Settings)
    /** Ids deleted on this device (tombstones). */
    suspend fun deletedIds(): List<String>
    /** Delete the local copies of rounds the other device deleted. */
    suspend fun applyRemoteDeletions(ids: List<String>)
    /** Watch: finished rounds the phone hasn't confirmed yet. */
    suspend fun pendingSessions(): List<Session>
    suspend fun markSynced(id: String)
    /** Phone: store a received round unless it was deleted here. */
    suspend fun storeReceived(session: Session)
}

class FrameIO(input: InputStream, output: OutputStream) {
    private val inp = DataInputStream(BufferedInputStream(input))
    private val out = DataOutputStream(BufferedOutputStream(output))

    fun write(type: Int, payload: ByteArray = ByteArray(0)) {
        out.writeByte(type)
        out.writeInt(payload.size)
        out.write(payload)
        out.flush()
    }

    fun read(): Pair<Int, ByteArray> {
        val type = inp.readUnsignedByte()
        val len = inp.readInt()
        if (len !in 0..MAX_FRAME) throw ProtocolException("Bad frame length $len")
        return type to ByteArray(len).also { inp.readFully(it) }
    }

    fun expect(type: Int): ByteArray {
        val (t, payload) = read()
        if (t != type) throw ProtocolException("Expected frame $type, got $t")
        return payload
    }

    private companion object {
        const val MAX_FRAME = 8 * 1024 * 1024
    }
}

object SyncEngine {
    const val VERSION = 1
    private const val MAGIC = "SAUNAPULSE"

    /** The watch side. Returns the number of rounds the phone confirmed. */
    suspend fun runWatch(io: FrameIO, store: SyncStore): Int {
        io.write(Frame.HELLO, hello(store.settings()))
        io.write(Frame.DELETED, SessionCodec.encodeIds(store.deletedIds()))
        applyRemote(store, io.expect(Frame.HELLO), io.expect(Frame.DELETED))

        var sent = 0
        for (session in store.pendingSessions()) {
            io.write(Frame.SESSION, SessionCodec.encodeCompressed(session))
            val acked = io.expect(Frame.ACK).decodeToString()
            if (acked != session.id) throw ProtocolException("Ack for $acked, expected ${session.id}")
            store.markSynced(session.id)
            sent++
        }
        io.write(Frame.DONE)
        return sent
    }

    /** The phone side. Returns the number of rounds received. */
    suspend fun runPhone(io: FrameIO, store: SyncStore): Int {
        val remoteHello = io.expect(Frame.HELLO)
        val remoteDeleted = io.expect(Frame.DELETED)
        parseHello(remoteHello) // reject a stranger before telling it anything
        io.write(Frame.HELLO, hello(store.settings()))
        io.write(Frame.DELETED, SessionCodec.encodeIds(store.deletedIds()))
        applyRemote(store, remoteHello, remoteDeleted)

        var received = 0
        while (true) {
            val (type, payload) = io.read()
            when (type) {
                Frame.SESSION -> {
                    val session = SessionCodec.decodeCompressed(payload)
                    store.storeReceived(session)
                    // Ack even if it was skipped (deleted here), so the watch stops resending it.
                    io.write(Frame.ACK, session.id.encodeToByteArray())
                    received++
                }
                Frame.DONE -> return received
                else -> throw ProtocolException("Unexpected frame $type")
            }
        }
    }

    private suspend fun applyRemote(store: SyncStore, hello: ByteArray, deleted: ByteArray) {
        val remote = parseHello(hello)
        if (remote.updatedAt > store.settings().updatedAt) store.saveSettings(remote)
        store.applyRemoteDeletions(SessionCodec.decodeIds(deleted))
    }

    private fun hello(settings: Settings): ByteArray =
        "$MAGIC/$VERSION|".encodeToByteArray() + SessionCodec.encodeSettings(settings)

    private fun parseHello(payload: ByteArray): Settings {
        val text = payload.decodeToString()
        val header = text.substringBefore('|')
        if (header.substringBefore('/') != MAGIC) throw ProtocolException("Not a Sauna Pulse device")
        val version = header.substringAfter('/').toIntOrNull()
        if (version != VERSION) throw ProtocolException("Unsupported protocol version $version")
        return SessionCodec.decodeSettings(text.substringAfter('|').encodeToByteArray())
            ?: throw ProtocolException("Bad settings")
    }
}
