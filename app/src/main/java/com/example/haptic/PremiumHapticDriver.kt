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

    // Composition primitives (PRIMITIVE_CLICK/TICK) require R, but hardware support is not
    // guaranteed even then - a lot of Android 11/12 devices report hasVibrator()=true and
    // silently no-op on unsupported primitives instead of throwing, which is why haptics can
    // "do nothing" without ever hitting a catch block. Check real support before relying on it.
    private val supportsPrimitives: Boolean by lazy {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && vibrator != null && try {
            vibrator.areAllPrimitivesSupported(
                VibrationEffect.Composition.PRIMITIVE_CLICK,
                VibrationEffect.Composition.PRIMITIVE_TICK
            )
        } catch (e: Exception) {
            false
        }
    }

    fun triggerClick() {
        if (vibrator == null || !vibrator.hasVibrator()) return
        if (supportsPrimitives) {
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
        if (supportsPrimitives) {
            try {
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.4f, 0)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.75f, 100)
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
                val amplitudes = intArrayOf(0, 60, 0, 130)
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
        if (supportsPrimitives) {
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
                // A soft nudge rather than DEFAULT_AMPLITUDE (max strength / 255) - devices
                // without primitive support still shouldn't feel a hard buzz.
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, 110))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
        } catch (e: Exception) {
            // Fail silent
        }
    }
}
