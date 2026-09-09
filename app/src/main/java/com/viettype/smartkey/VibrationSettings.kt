package com.viettype.smartkey

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Bật/tắt + chỉnh độ mạnh rung phản hồi mỗi lần gõ phím. Độ mạnh lưu dạng số
 * nguyên 0..100 (%), quy đổi sang thời lượng/biên độ rung thực tế lúc gọi.
 */
object VibrationSettings {

    private const val PREFS_NAME = "vibration_settings"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_STRENGTH_PERCENT = "strength_percent"

    private const val DEFAULT_STRENGTH = 40

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getStrengthPercent(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_STRENGTH_PERCENT, DEFAULT_STRENGTH)

    fun setStrengthPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_STRENGTH_PERCENT, percent.coerceIn(0, 100)).apply()
    }

    /** Rung 1 cái ngắn cho phản hồi gõ phím - tự bỏ qua nếu người dùng đã tắt rung. */
    fun tick(context: Context) {
        if (!isEnabled(context)) return
        val strength = getStrengthPercent(context)
        if (strength <= 0) return
        val durationMs = 8L + (strength * 0.2).toLong() // 8ms..28ms tuỳ độ mạnh
        val vibrator = obtainVibrator(context) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val amplitude = (255 * strength / 100).coerceIn(1, 255)
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
        } catch (ignored: Exception) {
        }
    }

    private fun obtainVibrator(context: Context): Vibrator? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (e: Exception) {
            null
        }
    }
}
