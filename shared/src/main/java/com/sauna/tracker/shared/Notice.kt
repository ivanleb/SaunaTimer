package com.sauna.tracker.shared

import android.content.Context
import androidx.core.content.edit

/** The one-time safety notice and the app version shown on the About screens. */
object Notice {
    private const val PREFS = "notice"
    private const val KEY = "accepted_v1"

    fun accepted(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun accept(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY, true) }

    fun versionName(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
}
