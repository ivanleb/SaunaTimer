package com.sauna.tracker.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.wear.ambient.AmbientLifecycleObserver
import com.sauna.tracker.shared.SyncHub
import com.sauna.tracker.wear.ui.WearNav
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val isAmbient = mutableStateOf(false)
    /** Bumped by the system about once a minute in ambient mode; the only time we redraw then. */
    private val ambientTick = mutableIntStateOf(0)

    private val ambientObserver = AmbientLifecycleObserver(
        this,
        object : AmbientLifecycleObserver.AmbientLifecycleCallback {
            override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
                isAmbient.value = true
                ambientTick.intValue++
            }
            override fun onUpdateAmbient() { ambientTick.intValue++ }
            override fun onExitAmbient() { isAmbient.value = false }
        },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(ambientObserver)
        setContent { WearNav(ambient = isAmbient.value, ambientTick = ambientTick.intValue) }
    }

    override fun onResume() {
        super.onResume()
        appScope.launch { SyncHub.get(this@MainActivity).onConnected(isPhone = false) }
    }
}
