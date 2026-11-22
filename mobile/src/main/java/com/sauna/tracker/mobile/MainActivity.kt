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
import com.sauna.tracker.mobile.sync.PhoneSyncServer
import com.sauna.tracker.mobile.ui.PhoneNav

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

    override fun onStart() {
        super.onStart()
        // While the app is open, accept the watch's sync connection.
        PhoneSyncServer.acquire(this, "ui")
    }

    override fun onStop() {
        PhoneSyncServer.release("ui")
        super.onStop()
    }
}
