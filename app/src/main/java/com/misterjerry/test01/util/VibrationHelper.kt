package com.misterjerry.test01.util

import com.misterjerry.test01.data.Urgency
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class VibrationHelper(context: Context) {
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibratorManager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    fun vibrate(urgency: Urgency) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = when (urgency) {
                Urgency.HIGH -> {
                    // 다급한 패턴: 0.2초 진동, 0.1초 대기, 0.2초 진동, 강도 최대
                    val timings = longArrayOf(0, 200, 100, 200)
                    val amplitudes = intArrayOf(0, 255, 0, 255)
                    VibrationEffect.createWaveform(timings, amplitudes, -1)
                }
                Urgency.MEDIUM -> {
                    // 주의 패턴: 0.3초 진동, 강도 중간
                    VibrationEffect.createOneShot(300, 150)
                }
                Urgency.LOW -> {
                    // 일상 패턴: 0.1초 짧은 진동, 강도 약함
                    VibrationEffect.createOneShot(100, 50)
                }
            }
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            val pattern = when (urgency) {
                Urgency.HIGH -> longArrayOf(0, 200, 100, 200)
                Urgency.MEDIUM -> longArrayOf(0, 300)
                Urgency.LOW -> longArrayOf(0, 100)
            }
            // createWaveform이 아닌 구형 API에서는 pattern 사용 시 repeat index 필요 (-1: 반복 없음)
            vibrator.vibrate(pattern, -1)
        }
    }
}
