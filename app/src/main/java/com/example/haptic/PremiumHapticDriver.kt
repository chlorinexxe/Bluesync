package com.example.haptic

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class PremiumHapticDriver(private val context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        manager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    fun triggerClick() {
        if (vibrator == null || !vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.9f)
                    .compose()
                vibrator.vibrate(effect)
            } catch (e: Exception) {
                vibrateLegacy(40)
            }
        } else {
            vibrateLegacy(40)
        }
    }

    fun triggerSkipPulse() {
        if (vibrator == null || !vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.4f, 0)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f, 100)
                    .compose()
                vibrator.vibrate(effect)
            } catch (e: Exception) {
                fallbackSkip()
            }
        } else {
            fallbackSkip()
        }
    }

    private fun fallbackSkip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val timings = longArrayOf(0, 15, 80, 45)
                val amplitudes = intArrayOf(0, 70, 0, 240)
                val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
                vibrator?.vibrate(effect)
            } catch (e: Exception) {
                vibrateLegacy(80)
            }
        } else {
            vibrateLegacy(80)
        }
    }

    fun triggerTick() {
        if (vibrator == null || !vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.35f)
                    .compose()
                vibrator.vibrate(effect)
            } catch (e: Exception) {
                vibrateLegacy(10)
            }
        } else {
            vibrateLegacy(10)
        }
    }

    private fun vibrateLegacy(durationMs: Long) {
        if (vibrator == null || !vibrator.hasVibrator()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
        } catch (e: Exception) {
            // Fail silent
        }
    }
}
