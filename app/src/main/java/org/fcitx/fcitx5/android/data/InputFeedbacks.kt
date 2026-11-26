/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data

import android.media.AudioManager
import android.os.Trace
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibrationAttributes
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum
import org.fcitx.fcitx5.android.input.feedback.KeySoundChoice
import org.fcitx.fcitx5.android.scene.theme.SoundKey
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.audioManager
import org.fcitx.fcitx5.android.utils.getSystemSettings
import org.fcitx.fcitx5.android.utils.vibrator

object InputFeedbacks {

    enum class InputFeedbackMode(override val stringRes: Int) : ManagedPreferenceEnum {
        FollowingSystem(R.string.following_system_settings),
        Enabled(R.string.enabled),
        Disabled(R.string.disabled);
    }

    private var systemSoundEffects = false
    private var systemHapticFeedback = false

    fun syncSystemPrefs() {
        systemSoundEffects = getSystemSettings<Int>(Settings.System.SOUND_EFFECTS_ENABLED) == 1
        // it says "Replaced by using android.os.VibrationAttributes.USAGE_TOUCH"
        // but gives no clue about how to use it, and this one still works
        @Suppress("DEPRECATION")
        systemHapticFeedback = getSystemSettings<Int>(Settings.System.HAPTIC_FEEDBACK_ENABLED) == 1
    }

    private val keyboardPrefs = AppPrefs.getInstance().keyboard

    private val soundOnKeyPress by keyboardPrefs.soundOnKeyPress
    private val soundOnKeyPressVolume by keyboardPrefs.soundOnKeyPressVolume
    private val hapticOnKeyPress by keyboardPrefs.hapticOnKeyPress
    private val buttonPressVibrationMilliseconds by keyboardPrefs.buttonPressVibrationMilliseconds
    private val buttonLongPressVibrationMilliseconds by keyboardPrefs.buttonLongPressVibrationMilliseconds
    private val buttonPressVibrationAmplitude by keyboardPrefs.buttonPressVibrationAmplitude
    private val buttonLongPressVibrationAmplitude by keyboardPrefs.buttonLongPressVibrationAmplitude

    private val vibrator = appContext.vibrator

    private val hasAmplitudeControl = vibrator.hasAmplitudeControl()

    fun hapticFeedback(view: View, longPress: Boolean = false) {
        when (hapticOnKeyPress) {
            InputFeedbackMode.Enabled -> {}
            InputFeedbackMode.Disabled -> return
            InputFeedbackMode.FollowingSystem -> if (!systemHapticFeedback) return
        }
        // 自定义时长、振幅只在「启用」模式下使用；跟随系统不读残留值（CyIME 语义）。
        val enabled = hapticOnKeyPress == InputFeedbackMode.Enabled
        val duration: Long = if (enabled) {
            (if (longPress) buttonLongPressVibrationMilliseconds else buttonPressVibrationMilliseconds).toLong()
        } else 0L
        val amplitude: Int = if (enabled) {
            if (longPress) buttonLongPressVibrationAmplitude else buttonPressVibrationAmplitude
        } else 0
        val hfc: Int = if (longPress) HapticFeedbackConstants.LONG_PRESS
        else HapticFeedbackConstants.KEYBOARD_TAP

        // 无自定义时长：先走系统按键触感（KEYBOARD_TAP），微信输入法、小米自带输入法同一条路，
        // HyperOS 会映射到厂商调校的短促 click；系统没接（返回 false）再自己兜底。
        if (duration == 0L && amplitude == 0) {
            var flags = HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
            if (enabled) {
                @Suppress("DEPRECATION")
                flags = flags or HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            }
            if (view.performHapticFeedback(hfc, flags)) return
            // HAL 预定义效果（厂商调校，myron 支持 CLICK/TICK/HEAVY_CLICK）。
            // areAllEffectsSupported 与其支持常量是 API 31 的（createPredefined 本身 30 就有）。
            val effect = if (longPress) VibrationEffect.EFFECT_HEAVY_CLICK else VibrationEffect.EFFECT_CLICK
            if (Build.VERSION.SDK_INT >= 31 &&
                vibrator.areAllEffectsSupported(effect) == Vibrator.VIBRATION_EFFECT_SUPPORT_YES
            ) {
                vibrateTouch(VibrationEffect.createPredefined(effect))
                return
            }
            // 预定义也不支持：按马达类型给短脉冲。振幅可控的多为线性马达，长脉冲会共振成「嗡」，
            // 只给十几毫秒打出来是「哒」；普通转子马达短于 30ms 转不起来。
            val ms = if (hasAmplitudeControl) (if (longPress) 30L else 15L)
            else (if (longPress) 70L else 45L)
            vibrateTouch(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            return
        }
        // 自定义时长：原逻辑不变。
        if (duration != 0L) {
            if (hasAmplitudeControl && amplitude != 0) {
                vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude))
            } else {
                vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } else {
            var flags = HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
            if (enabled) {
                @Suppress("DEPRECATION")
                flags = flags or HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            }
            view.performHapticFeedback(hfc, flags)
        }
    }

    /** 触摸反馈用途的振动：随系统「触摸互动」强度，而不是按媒体振动归类（CyIME vibrateTouch）。 */
    private fun vibrateTouch(effect: VibrationEffect) {
        // vibrate(effect, attributes) 与 createForUsage 均为 API 33 新增，lintVitalRelease 按 minSdk 31 检查
        if (Build.VERSION.SDK_INT >= 33) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
        } else {
            vibrator.vibrate(effect)
        }
    }

    /** 按键音音色: the selected theme's sounds where it has them, or the system's always. */
    enum class KeySoundSource(override val stringRes: Int) : ManagedPreferenceEnum {
        FollowTheme(R.string.key_sound_source_theme),
        System(R.string.key_sound_source_system);
    }

    private val keySoundSource by keyboardPrefs.keySoundSource

    private val audioManager = appContext.audioManager

    /** Whether it sounds, then which and how loud (KeySoundChoice); [system]: the click where no theme sound plays. */
    fun soundEffect(key: SoundKey, system: SoundKey = key) {
        Trace.beginSection("InputFeedbacks.soundEffect")
        try {
            val plays = soundPlays()
            val fromTheme = keySoundSource == KeySoundSource.FollowTheme
            ThemeSounds.sync(plays && fromTheme)
            if (!plays) return
            play(key, system, fromTheme, soundOnKeyPressVolume)
        } finally {
            Trace.endSection()
        }
    }

    /** As a press would play it at [volume] (0 the system's), on slider release; plays whether or not the keys would. */
    fun previewSound(volume: Int) {
        val fromTheme = keySoundSource == KeySoundSource.FollowTheme
        ThemeSounds.sync(fromTheme)
        play(SoundKey.LETTER, SoundKey.LETTER, fromTheme, volume)
    }

    private fun play(key: SoundKey, system: SoundKey, fromTheme: Boolean, volume: Int) {
        val sounds = ThemeSounds.shared
        when (val choice = KeySoundChoice.choose(key, sounds.given, sounds.loaded, fromTheme, volume, system)) {
            is KeySoundChoice.Choice.Theme ->
                if (!sounds.play(choice.key, choice.volume)) playSystemClick(system, KeySoundChoice.systemVolume(volume))
            is KeySoundChoice.Choice.System -> playSystemClick(choice.key, choice.volume)
        }
    }

    private fun soundPlays(): Boolean = when (soundOnKeyPress) {
        InputFeedbackMode.Enabled -> true
        InputFeedbackMode.Disabled -> false
        InputFeedbackMode.FollowingSystem -> systemSoundEffects
    }

    /** Loaded ahead of the first press, where they would play. */
    fun syncSounds() = ThemeSounds.sync(soundPlays() && keySoundSource == KeySoundSource.FollowTheme)

    private fun playSystemClick(key: SoundKey, volume: Float) = audioManager.playSoundEffect(fx(key), volume)

    // the system has no click of its own for a function key
    private fun fx(key: SoundKey): Int = when (key) {
        SoundKey.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
        SoundKey.BACKSPACE -> AudioManager.FX_KEYPRESS_DELETE
        SoundKey.ENTER -> AudioManager.FX_KEYPRESS_RETURN
        SoundKey.LETTER, SoundKey.FUNCTION -> AudioManager.FX_KEYPRESS_STANDARD
    }

    /** The service is gone. */
    fun releaseSounds() = ThemeSounds.shared.release()

}