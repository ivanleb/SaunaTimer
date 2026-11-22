package com.sauna.tracker.mobile

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService
import com.sauna.tracker.mobile.ui.PhoneNav
import com.sauna.tracker.shared.SyncHub
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            val ctx = LocalContext.current
            val colors = when {
                Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
                dark -> darkColorScheme(primary = Color(0xFFFFB74D))
                else -> lightColorScheme(primary = Color(0xFFE65100))
            }
            MaterialTheme(colorScheme = colors) { PhoneNav() }
        }
    }

    override fun onResume() {
        super.onResume()
        // Pick up anything the listener might have missed (e.g. app was force-stopped).
        lifecycleScope.launch { SyncHub.get(this@MainActivity).pullAll(isPhone = true) }
    }
}

/** Stores rounds from the watch, applies settings and deletions, and acknowledges. */
class PhoneListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        val items = events.filter { it.type == DataEvent.TYPE_CHANGED }.map { it.dataItem.freeze() }
        val hub = SyncHub.get(this)
        runBlocking { items.forEach { hub.handleDataItem(it, isPhone = true) } }
    }
}
