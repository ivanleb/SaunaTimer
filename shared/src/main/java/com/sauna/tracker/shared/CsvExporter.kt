package com.sauna.tracker.shared

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One flat table, so several sessions can go into one spreadsheet and be filtered/pivoted.
 */
object CsvExporter {
    const val HEADER = "session_start,datetime,elapsed_s,bpm,hr_limit,time_limit_min,interrupted"

    private val dateTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val fileStamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US)

    fun toCsv(sessions: List<Session>, zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        append(HEADER).append('\n')
        for (s in sessions.sortedBy { it.startedAt }) {
            val start = format(s.startedAt, zone)
            for (p in s.samples) {
                append(start).append(',')
                append(format(s.startedAt + p.t, zone)).append(',')
                append(String.format(Locale.US, "%.1f", p.t / 1000.0)).append(',')
                append(p.bpm).append(',')
                append(s.hrLimit).append(',')
                append(s.timeLimitMin).append(',')
                append(if (s.interrupted) 1 else 0)
                append('\n')
            }
        }
    }

    fun fileName(session: Session, zone: ZoneId = ZoneId.systemDefault()): String =
        "sauna_" + fileStamp.format(Instant.ofEpochMilli(session.startedAt).atZone(zone)) + ".csv"

    private fun format(epochMs: Long, zone: ZoneId): String =
        dateTime.format(Instant.ofEpochMilli(epochMs).atZone(zone))
}
