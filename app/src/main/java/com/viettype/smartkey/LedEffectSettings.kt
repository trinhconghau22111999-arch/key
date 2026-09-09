package com.viettype.smartkey

import android.content.Context

/**
 * Cấu hình hiệu ứng viền sáng đổi màu chạy dọc mép trên của bàn phím (trang trí,
 * không ảnh hưởng chức năng gõ) - tương tự đèn LED RGB trên bàn phím cơ vật lý.
 */
object LedEffectSettings {

    enum class Mode { OFF, RAINBOW_CYCLE, BREATHING, STATIC_COLOR }
    enum class Direction { LEFT_TO_RIGHT, RIGHT_TO_LEFT }

    private const val PREFS_NAME = "led_effect_settings"
    private const val KEY_MODE = "mode"
    private const val KEY_DIRECTION = "direction"
    private const val KEY_SPEED_PERCENT = "speed_percent"

    fun getMode(context: Context): Mode {
        val raw = prefs(context).getString(KEY_MODE, Mode.OFF.name) ?: Mode.OFF.name
        return try {
            Mode.valueOf(raw)
        } catch (e: IllegalArgumentException) {
            Mode.OFF
        }
    }

    fun setMode(context: Context, mode: Mode) {
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()
    }

    fun getDirection(context: Context): Direction {
        val raw = prefs(context).getString(KEY_DIRECTION, Direction.LEFT_TO_RIGHT.name)
            ?: Direction.LEFT_TO_RIGHT.name
        return try {
            Direction.valueOf(raw)
        } catch (e: IllegalArgumentException) {
            Direction.LEFT_TO_RIGHT
        }
    }

    fun setDirection(context: Context, direction: Direction) {
        prefs(context).edit().putString(KEY_DIRECTION, direction.name).apply()
    }

    fun getSpeedPercent(context: Context): Int = prefs(context).getInt(KEY_SPEED_PERCENT, 50)

    fun setSpeedPercent(context: Context, percent: Int) {
        prefs(context).edit().putInt(KEY_SPEED_PERCENT, percent.coerceIn(1, 100)).apply()
    }

    /** Chu kỳ 1 vòng lặp hiệu ứng, tính bằng mili-giây, quy đổi từ % tốc độ (nhanh hơn -> chu kỳ ngắn hơn). */
    fun cycleDurationMs(context: Context): Long {
        val speed = getSpeedPercent(context).coerceIn(1, 100)
        // 100% tốc độ -> 1.2 giây/vòng; 1% tốc độ -> 8 giây/vòng.
        val minMs = 1200L
        val maxMs = 8000L
        return maxMs - ((maxMs - minMs) * speed / 100)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
