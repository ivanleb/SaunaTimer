package com.sauna.tracker.wear

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.sauna.tracker.shared.Alert

object Vibration {
    // Long triple buzz = time is up; short double buzz = heart rate limit.
    private val TIME_PATTERN = longArrayOf(0, 800, 300, 800, 300, 800)
    private val HR_PATTERN = longArrayOf(0, 300, 150, 300)

    fun play(context: Context, alert: Alert) {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
        val pattern = if (alert == Alert.TIME_LIMIT) TIME_PATTERN else HR_PATTERN
        val effect = VibrationEffect.createWaveform(pattern, -1)
        // Alarm usage so the alert still comes through in Do Not Disturb / theatre mode.
        if (Build.VERSION.SDK_INT >= 33) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    }
}
