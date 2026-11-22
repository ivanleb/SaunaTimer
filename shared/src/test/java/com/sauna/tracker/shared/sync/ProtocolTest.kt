package com.sauna.tracker.shared.sync

import com.sauna.tracker.shared.HrSample
import com.sauna.tracker.shared.Session
import com.sauna.tracker.shared.Settings
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream

private class FakeStore(
    var settings: Settings = Settings(),
    val deleted: MutableList<String> = mutableListOf(),
    val sessions: MutableMap<String, Session> = mutableMapOf(),
    val synced: MutableSet<String> = mutableSetOf(),
) : SyncStore {
    override suspend fun settings() = settings
    override suspend fun saveSettings(settings: Settings) { this.settings = settings }
    override suspend fun deletedIds() = deleted.toList()
    override suspend fun applyRemoteDeletions(ids: List<String>) { ids.forEach { sessions.remove(it) } }
    override suspend fun pendingSessions() = sessions.values.filter { it.id !in synced }.sortedBy { it.startedAt }
    override suspend fun markSynced(id: String) { synced += id }
    override suspend fun storeReceived(session: Session) {
        if (session.id !in deleted) sessions[session.id] = session
    }
}

private fun round(id: String, start: Long) =
    Session(id, start, 60_000, 15, 140, (0 until 60).map { HrSample(it * 1000L, 100 + it) })

/** Connects the two sides with in-memory pipes and runs a full sync. */
private fun sync(watch: FakeStore, phone: FakeStore): Pair<Int, Int> = runBlocking(Dispatchers.IO) {
    val watchToPhone = PipedOutputStream()
    val phoneIn = PipedInputStream(watchToPhone, 1 shl 16)
    val phoneToWatch = PipedOutputStream()
    val watchIn = PipedInputStream(phoneToWatch, 1 shl 16)
    val p = async { SyncEngine.runPhone(FrameIO(phoneIn, phoneToWatch), phone) }
    val w = async { SyncEngine.runWatch(FrameIO(watchIn, watchToPhone), watch) }
    w.await() to p.await()
}

class ProtocolTest {
    @Test fun roundsArriveAndAreMarkedSynced() {
        val watch = FakeStore(sessions = mutableMapOf("a" to round("a", 1), "b" to round("b", 2)))
        val phone = FakeStore()
        assertEquals(2 to 2, sync(watch, phone))
        assertEquals(watch.sessions, phone.sessions)
        assertEquals(setOf("a", "b"), watch.synced)
        // Nothing left to send the second time.
        assertEquals(0 to 0, sync(watch, phone))
    }

    @Test fun newestSettingsWinInBothDirections() {
        val watch = FakeStore(settings = Settings(10, 130, updatedAt = 100))
        val phone = FakeStore(settings = Settings(20, 160, updatedAt = 200))
        sync(watch, phone)
        assertEquals(Settings(20, 160, 200), watch.settings)
        assertEquals(Settings(20, 160, 200), phone.settings)

        watch.settings = Settings(12, 150, updatedAt = 300)
        sync(watch, phone)
        assertEquals(Settings(12, 150, 300), phone.settings)
    }

    @Test fun deletionsApplyOnBothSides() {
        val watch = FakeStore(
            sessions = mutableMapOf("keep" to round("keep", 1), "delOnPhone" to round("delOnPhone", 2)),
            synced = mutableSetOf("keep", "delOnPhone"),
            deleted = mutableListOf("delOnWatch"),
        )
        val phone = FakeStore(
            sessions = mutableMapOf("keep" to round("keep", 1), "delOnWatch" to round("delOnWatch", 3)),
            deleted = mutableListOf("delOnPhone"),
        )
        sync(watch, phone)
        assertEquals(setOf("keep"), watch.sessions.keys)
        assertEquals(setOf("keep"), phone.sessions.keys)
    }

    @Test fun roundDeletedOnPhoneIsNotResentForever() {
        // The phone deleted "x" before it was marked synced on the watch (e.g. the ack got lost).
        val watch = FakeStore(sessions = mutableMapOf("x" to round("x", 1)))
        val phone = FakeStore(deleted = mutableListOf("x"))
        sync(watch, phone)
        assertTrue(phone.sessions.isEmpty())
        assertTrue(watch.sessions.isEmpty()) // the watch applied the phone's deletion before sending
    }

    @Test fun strangerIsRejected() {
        val garbage = ByteArrayOutputStream().also { FrameIO(ByteArrayInputStream(ByteArray(0)), it).apply {
            write(Frame.HELLO, "HELLO".encodeToByteArray()); write(Frame.DELETED)
        } }.toByteArray()
        val phone = FakeStore()
        val reply = ByteArrayOutputStream()
        val error = runCatching { runBlocking { SyncEngine.runPhone(FrameIO(ByteArrayInputStream(garbage), reply), phone) } }
        assertTrue(error.exceptionOrNull() is IOException)
        assertEquals("nothing is sent to an unknown device", 0, reply.size())
    }
}
