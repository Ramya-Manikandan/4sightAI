package com.foursightai

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * HapticManager — urgency-coded vibration feedback.
 *
 * Four patterns, each instantly recognisable by a blind user:
 *   CLEAR    — single short tap     (path clear, all good)
 *   WARNING  — two medium pulses    (obstacle 2–4 m ahead)
 *   URGENT   — three fast bursts    (obstacle under 2 m)
 *   STOP     — one long strong buzz (obstacle < 0.6 m, stop immediately)
 */
class HapticManager(context: Context) {

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vm.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    enum class Urgency { CLEAR, WARNING, URGENT, STOP }

    fun vibrate(urgency: Urgency) {
        if (!vibrator.hasVibrator()) return

        val effect = when (urgency) {
            Urgency.CLEAR -> {
                // Single short tap — 80 ms
                VibrationEffect.createOneShot(80L, VibrationEffect.DEFAULT_AMPLITUDE)
            }
            Urgency.WARNING -> {
                // Two medium pulses: on 150 ms, off 100 ms, on 150 ms
                VibrationEffect.createWaveform(
                    longArrayOf(0L, 150L, 100L, 150L),
                    intArrayOf(0, 180, 0, 180),
                    -1
                )
            }
            Urgency.URGENT -> {
                // Three fast bursts: 100 ms on, 60 ms off ×3
                VibrationEffect.createWaveform(
                    longArrayOf(0L, 100L, 60L, 100L, 60L, 100L),
                    intArrayOf(0, 220, 0, 220, 0, 220),
                    -1
                )
            }
            Urgency.STOP -> {
                // One long strong buzz — 500 ms at full amplitude
                VibrationEffect.createOneShot(500L, 255)
            }
        }

        vibrator.vibrate(effect)
    }

    /**
     * Convenience: derive urgency from a distance in metres.
     * The NavigationEngine calls this after every instruction decision.
     */
    fun vibrateForDistance(distanceMetres: Float) {
        val urgency = when {
            distanceMetres <= 0.6f -> Urgency.STOP
            distanceMetres <= 1.5f -> Urgency.URGENT
            distanceMetres <= 3.0f -> Urgency.WARNING
            else                   -> return   // > 3 m — no vibration, just voice
        }
        vibrate(urgency)
    }

    fun cancel() {
        vibrator.cancel()
    }
}