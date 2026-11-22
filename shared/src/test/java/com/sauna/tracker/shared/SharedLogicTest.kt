package com.sauna.tracker.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class AlertPolicyTest {
    private val min = 60_000L

    @Test fun timeAlertFiresOnceAtLimit() {
        val p = AlertPolicy(timeLimitMs = 15 * min, hrLimit = 140)
        assertTrue(p.onTick(15 * min - 1000, 100).isEmpty())
        assertEquals(listOf(Alert.TIME_LIMIT), p.onTick(15 * min, 100))
        assertTrue(p.onTick(16 * min, 100).isEmpty())
        assertTrue(p.onTick(30 * min, null).isEmpty())
    }

    @Test fun hrAlertAtLimitThenRepeatsEvery30s() {
        val p = AlertPolicy(timeLimitMs = 15 * min, hrLimit = 140)
        assertTrue(p.onTick(1_000, 139).isEmpty())
        assertEquals(listOf(Alert.HR_LIMIT), p.onTick(2_000, 140))
        assertTrue(p.onTick(10_000, 150).isEmpty())
        assertTrue(p.onTick(31_000, 150).isEmpty())
        assertEquals(listOf(Alert.HR_LIMIT), p.onTick(32_000, 150))
    }

    @Test fun droppingBelowResetsSoNextCrossingAlertsImmediately() {
        val p = AlertPolicy(timeLimitMs = 15 * min, hrLimit = 140)
        assertEquals(listOf(Alert.HR_LIMIT), p.onTick(1_000, 145))
        assertTrue(p.onTick(5_000, 130).isEmpty())
        assertEquals(listOf(Alert.HR_LIMIT), p.onTick(6_000, 141))
    }

    @Test fun missingReadingDoesNotResetRepeat() {
        val p = AlertPolicy(timeLimitMs = 15 * min, hrLimit = 140)
        assertEquals(listOf(Alert.HR_LIMIT), p.onTick(1_000, 145))
        assertTrue(p.onTick(5_000, null).isEmpty())
        assertTrue(p.onTick(6_000, 145).isEmpty())
    }

    @Test fun bothAlertsCanFireTogether() {
        val p = AlertPolicy(timeLimitMs = 1 * min, hrLimit = 140)
        assertEquals(listOf(Alert.TIME_LIMIT, Alert.HR_LIMIT), p.onTick(1 * min, 150))
    }
}

class StatsAndCodecTest {
    private val samples = listOf(HrSample(0, 100), HrSample(10_000, 140), HrSample(20_000, 160), HrSample(30_000, 120))
    private val session = Session("abc", 1_700_000_000_000, 40_000, 15, 140, samples)

    @Test fun stats() {
        assertEquals(160, session.maxBpm)
        assertEquals(133, session.avgBpm) // (100+140+160)/3 time-weighted over 30 s
        assertEquals(20_000, session.timeAboveLimitMs)
    }

    @Test fun codecRoundTrip() {
        assertEquals(session, SessionCodec.decode(SessionCodec.encode(session)))
        val interrupted = session.copy(interrupted = true)
        assertEquals(interrupted, SessionCodec.decode(SessionCodec.encode(interrupted)))
    }

    @Test fun csv() {
        val lines = CsvExporter.toCsv(listOf(session), ZoneOffset.UTC).trim().lines()
        assertEquals(CsvExporter.HEADER, lines[0])
        assertEquals(5, lines.size)
        assertEquals("2023-11-14 22:13:20,2023-11-14 22:13:30,10.0,140,140,15,0", lines[2])
        assertEquals("sauna_20231114_221320.csv", CsvExporter.fileName(session, ZoneOffset.UTC))
    }

    @Test fun durationFormat() {
        assertEquals("0:05", formatDuration(5_000))
        assertEquals("15:00", formatDuration(900_000))
        assertEquals("1:01:01", formatDuration(3_661_000))
    }
}
