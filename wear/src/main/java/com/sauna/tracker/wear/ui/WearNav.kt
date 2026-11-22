package com.sauna.tracker.wear.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.sauna.tracker.shared.Notice
import com.sauna.tracker.wear.LiveSession
import com.sauna.tracker.wear.R
import com.sauna.tracker.wear.SessionService
import com.sauna.tracker.wear.WatchSync
import com.sauna.tracker.wear.appScope
import com.sauna.tracker.shared.sync.BluetoothLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun WearNav(ambient: Boolean, ambientTick: Int) {
    val context = LocalContext.current
    val nav = rememberSwipeDismissableNavController()
    val live by LiveSession.state.collectAsStateWithLifecycle()

    val heartRatePermissions = buildList {
        add(Manifest.permission.BODY_SENSORS)
        if (Build.VERSION.SDK_INT >= 36) add(READ_HEART_RATE)
    }
    val permissions = buildList {
        addAll(heartRatePermissions)
        add(Manifest.permission.ACTIVITY_RECOGNITION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        BluetoothLink.PERMISSION?.let(::add) // to send rounds to the phone
    }

    fun sendToPhone() {
        Toast.makeText(context, R.string.syncing, Toast.LENGTH_SHORT).show()
        appScope.launch {
            val outcome = WatchSync.syncNow(context)
            val message = when (outcome.status) {
                WatchSync.Status.OK -> context.getString(R.string.sync_ok_fmt, outcome.sent)
                WatchSync.Status.NO_PERMISSION -> context.getString(R.string.sync_no_permission)
                WatchSync.Status.BLUETOOTH_OFF -> context.getString(R.string.sync_bt_off)
                WatchSync.Status.NO_PHONE -> context.getString(R.string.sync_no_phone)
                WatchSync.Status.UNREACHABLE -> context.getString(R.string.sync_unreachable)
            }
            withContext(Dispatchers.Main) { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
        }
    }

    val bluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { sendToPhone() }

    fun requestSend() {
        val permission = BluetoothLink.PERMISSION
        if (permission != null && !BluetoothLink.hasPermission(context)) bluetoothLauncher.launch(permission)
        else sendToPhone()
    }

    fun startRound() {
        SessionService.start(context)
        nav.navigate(Routes.SESSION)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val heartRateGranted = heartRatePermissions.any {
            result[it] == true ||
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (heartRateGranted) startRound()
        else Toast.makeText(context, R.string.permission_needed, Toast.LENGTH_LONG).show()
    }

    fun requestStart() {
        fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        val missing = permissions.filterNot(::granted)
        // Heart rate is the only must-have; don't nag on every Start about the optional ones.
        if (missing.isEmpty() || heartRatePermissions.any(::granted)) startRound()
        else permissionLauncher.launch(missing.toTypedArray())
    }

    // When a round ends, replace the round screen with its summary.
    LaunchedEffect(live.running, live.lastFinishedId) {
        val finished = live.lastFinishedId
        if (!live.running && finished != null && nav.currentDestination?.route == Routes.SESSION) {
            nav.navigate(Routes.summary(finished)) { popUpTo(Routes.SESSION) { inclusive = true } }
        }
    }

    // Decided once: changing the start destination later would rebuild the graph and reset the back stack.
    val startDestination = remember {
        when {
            LiveSession.state.value.running -> Routes.SESSION
            !Notice.accepted(context) -> Routes.NOTICE
            else -> Routes.HOME
        }
    }

    MaterialTheme {
        SwipeDismissableNavHost(
            navController = nav,
            startDestination = startDestination,
        ) {
            composable(Routes.NOTICE) {
                NoticeScreen(onAccept = {
                    Notice.accept(context)
                    nav.navigate(Routes.HOME) { popUpTo(Routes.NOTICE) { inclusive = true } }
                })
            }
            composable(Routes.ABOUT) { AboutScreen() }
            composable(Routes.HOME) {
                HomeScreen(
                    live = live,
                    onStart = ::requestStart,
                    onOpenRound = { nav.navigate(Routes.SESSION) },
                    onRecent = { nav.navigate(Routes.RECENT) },
                    onSettings = { nav.navigate(Routes.SETTINGS) },
                    onSend = ::requestSend,
                )
            }
            composable(Routes.SESSION) {
                SessionScreen(
                    live = live,
                    ambient = ambient,
                    ambientTick = ambientTick,
                    onStop = { SessionService.stop(context) },
                )
            }
            composable(Routes.SUMMARY) { entry ->
                val id = entry.arguments?.getString("id") ?: return@composable
                SummaryScreen(id = id, onDone = { nav.popBackStack() })
            }
            composable(Routes.RECENT) {
                RecentScreen(onOpen = { nav.navigate(Routes.summary(it)) })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(onEdit = { nav.navigate(Routes.edit(it)) }, onAbout = { nav.navigate(Routes.ABOUT) })
            }
            composable(Routes.EDIT) { entry ->
                EditLimitScreen(which = entry.arguments?.getString("which") ?: "time")
            }
        }
    }
}

/** Wear OS 6 replacement for BODY_SENSORS (heart rate only). */
private const val READ_HEART_RATE = "android.permission.health.READ_HEART_RATE"

object Routes {
    const val NOTICE = "notice"
    const val ABOUT = "about"
    const val HOME = "home"
    const val SESSION = "session"
    const val SUMMARY = "summary/{id}"
    const val RECENT = "recent"
    const val SETTINGS = "settings"
    const val EDIT = "edit/{which}"
    fun summary(id: String) = "summary/$id"
    fun edit(which: String) = "edit/$which"
}
