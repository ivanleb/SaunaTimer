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
import com.sauna.tracker.wear.LiveSession
import com.sauna.tracker.wear.R
import com.sauna.tracker.wear.SessionService

@Composable
fun WearNav(ambient: Boolean, ambientTick: Int) {
    val context = LocalContext.current
    val nav = rememberSwipeDismissableNavController()
    val live by LiveSession.state.collectAsStateWithLifecycle()

    val permissions = buildList {
        add(Manifest.permission.BODY_SENSORS)
        add(Manifest.permission.ACTIVITY_RECOGNITION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun startRound() {
        SessionService.start(context)
        nav.navigate(Routes.SESSION)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.BODY_SENSORS] == true) startRound()
        else Toast.makeText(context, R.string.permission_needed, Toast.LENGTH_LONG).show()
    }

    fun requestStart() {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startRound() else permissionLauncher.launch(missing.toTypedArray())
    }

    // When a round ends, replace the round screen with its summary.
    LaunchedEffect(live.running, live.lastFinishedId) {
        val finished = live.lastFinishedId
        if (!live.running && finished != null && nav.currentDestination?.route == Routes.SESSION) {
            nav.navigate(Routes.summary(finished)) { popUpTo(Routes.SESSION) { inclusive = true } }
        }
    }

    // Decided once: changing the start destination later would rebuild the graph and reset the back stack.
    val startDestination = remember { if (LiveSession.state.value.running) Routes.SESSION else Routes.HOME }

    MaterialTheme {
        SwipeDismissableNavHost(
            navController = nav,
            startDestination = startDestination,
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    live = live,
                    onStart = ::requestStart,
                    onOpenRound = { nav.navigate(Routes.SESSION) },
                    onRecent = { nav.navigate(Routes.RECENT) },
                    onSettings = { nav.navigate(Routes.SETTINGS) },
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
                SettingsScreen(onEdit = { nav.navigate(Routes.edit(it)) })
            }
            composable(Routes.EDIT) { entry ->
                EditLimitScreen(which = entry.arguments?.getString("which") ?: "time")
            }
        }
    }
}

object Routes {
    const val HOME = "home"
    const val SESSION = "session"
    const val SUMMARY = "summary/{id}"
    const val RECENT = "recent"
    const val SETTINGS = "settings"
    const val EDIT = "edit/{which}"
    fun summary(id: String) = "summary/$id"
    fun edit(which: String) = "edit/$which"
}
