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

    /** Rung 1 cái ngắn cho phản hồi gõ phím - tự bỏ qua nếu người dùng đã tắt rung.
     *
     *  Độ mạnh rung phụ thuộc 2 yếu tố: BIÊN ĐỘ (amplitude, motor rung mạnh/nhẹ) và
     *  THỜI LƯỢNG (duration, rung lâu hay mau tắt) - ở mức 100% trước đây chỉ kéo dài
     *  28ms nên nhiều máy cảm giác rung "chưa đã tay"; giờ kéo dài tới 60ms ở mức tối
     *  đa để cảm nhận rõ ràng hơn, đồng thời biên độ đạt tối đa (255, giới hạn phần
     *  cứng Android) sớm hơn 1 chút để các mức cao đều đã cảm nhận rõ. */
    fun tick(context: Context) {
        if (!isEnabled(context)) return
        val strength = getStrengthPercent(context)
        if (strength <= 0) return
        val durationMs = 10L + (strength * 0.5).toLong() // 10ms (1%) .. 60ms (100%)
        val vibrator = obtainVibrator(context) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Biên độ tối thiểu 40 (để mức thấp vẫn cảm nhận được) và đạt kịch trần
                // 255 ngay từ khoảng 80% trở lên, thay vì phải kéo hết cỡ 100% mới full.
                val amplitude = (40 + (215 * strength / 80)).coerceIn(1, 255)
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
