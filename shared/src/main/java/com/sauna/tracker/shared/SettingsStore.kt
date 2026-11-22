package com.sauna.tracker.shared

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("sauna_settings")

/** Local persistence for the limits and for the ids this device has deleted. */
class SettingsStore(context: Context) {
    private val ds = context.applicationContext.dataStore

    val settings: Flow<Settings> = ds.data.map { p ->
        Settings(
            timeLimitMin = p[TIME] ?: Settings.DEFAULT_TIME_LIMIT_MIN,
            hrLimit = p[HR] ?: Settings.DEFAULT_HR_LIMIT,
            updatedAt = p[UPDATED] ?: 0L,
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun save(s: Settings) {
        ds.edit {
            it[TIME] = s.timeLimitMin
            it[HR] = s.hrLimit
            it[UPDATED] = s.updatedAt
        }
    }

    suspend fun deletedIds(): List<String> =
        ds.data.first()[DELETED]?.split(',')?.filter { it.isNotBlank() } ?: emptyList()

    /** Remembers a deletion; keeps only the most recent ones so the DataItem stays small. */
    suspend fun addDeleted(id: String): List<String> {
        val updated = (deletedIds() - id + id).takeLast(MAX_TOMBSTONES)
        ds.edit { it[DELETED] = updated.joinToString(",") }
        return updated
    }

    private companion object {
        val TIME = intPreferencesKey("timeLimitMin")
        val HR = intPreferencesKey("hrLimit")
        val UPDATED = longPreferencesKey("updatedAt")
        val DELETED = stringPreferencesKey("deletedIds")
        const val MAX_TOMBSTONES = 500
    }
}
