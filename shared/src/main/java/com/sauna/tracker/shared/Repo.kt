package com.sauna.tracker.shared

import android.content.Context
import com.sauna.tracker.shared.db.SaunaDb

/**
 * Local edits that the other device must learn about: settings and deletions.
 * They are stored here and carried over at the next Bluetooth sync. The watch sets
 * [onLocalChange] to start that sync right away.
 */
class Repo private constructor(context: Context) {
    private val dao = SaunaDb.get(context.applicationContext).sessions()
    val settingsStore = SettingsStore(context.applicationContext)

    @Volatile var onLocalChange: (() -> Unit)? = null

    suspend fun updateSettings(timeLimitMin: Int, hrLimit: Int) {
        settingsStore.save(
            Settings(
                timeLimitMin = timeLimitMin.coerceIn(Settings.TIME_RANGE),
                hrLimit = hrLimit.coerceIn(Settings.HR_RANGE),
                updatedAt = System.currentTimeMillis(),
            )
        )
        onLocalChange?.invoke()
    }

    /** Delete here; the other device deletes its copy at the next sync. */
    suspend fun deleteSession(id: String) {
        dao.delete(id)
        settingsStore.addDeleted(id)
        onLocalChange?.invoke()
    }

    companion object {
        @Volatile private var instance: Repo? = null
        fun get(context: Context): Repo = instance ?: synchronized(this) {
            instance ?: Repo(context).also { instance = it }
        }
    }
}
