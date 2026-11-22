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
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.sauna.tracker.mobile.ui.PhoneNav
import com.sauna.tracker.shared.DataPaths
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
        // Exchange settings/deletions and ask the watch for anything not yet received.
        lifecycleScope.launch { SyncHub.get(this@MainActivity).requestSync() }
    }
}

/** Receives rounds, settings and deletions directly from the nearby watch, even when the app is closed. */
class PhoneListenerService : WearableListenerService() {
    private val hub by lazy { SyncHub.get(this) }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path.startsWith(DataPaths.SESSION_PREFIX)) runBlocking { hub.receiveSession(channel) }
    }

    override fun onMessageReceived(event: MessageEvent) {
        runBlocking { hub.handleMessage(event, isPhone = true) }
    }

    override fun onCapabilityChanged(info: CapabilityInfo) {
        if (info.nodes.any { it.isNearby }) runBlocking { hub.onConnected(isPhone = true) }
    }
}
