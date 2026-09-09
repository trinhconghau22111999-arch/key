package com.viettype.smartkey

import android.content.Context

/**
 * Cấu hình hiệu ứng đèn RGB "chạy" liên tục quanh VIỀN CÁC PHÍM (giống bàn
 * phím cơ gaming thật) - thuần trang trí, không ảnh hưởng chức năng gõ.
 * Mặc định TẮT vì tốn pin hơn màu viền tĩnh bình thường.
 */
object LedEffectSettings {

    /** MULTI_COLOR = chạy cầu vồng nhiều màu; SINGLE_COLOR = chạy 1 màu duy nhất
     *  (dùng đúng màu viền đang chọn ở mục Màu sắc). */
    enum class ColorMode { MULTI_COLOR, SINGLE_COLOR }

    /** Hướng chạy của hiệu ứng dọc theo lưới phím. */
    enum class Direction { LEFT_TO_RIGHT, TOP_TO_BOTTOM, DIAGONAL }

    private const val PREFS_NAME = "led_effect_settings"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_COLOR_MODE = "color_mode"
    private const val KEY_DIRECTION = "direction"
    private const val KEY_SPEED_PERCENT = "speed_percent"

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getColorMode(context: Context): ColorMode {
        val raw = prefs(context).getString(KEY_COLOR_MODE, ColorMode.MULTI_COLOR.name)
            ?: ColorMode.MULTI_COLOR.name
        return try {
            ColorMode.valueOf(raw)
        } catch (e: IllegalArgumentException) {
            ColorMode.MULTI_COLOR
        }
    }

    fun setColorMode(context: Context, mode: ColorMode) {
        prefs(context).edit().putString(KEY_COLOR_MODE, mode.name).apply()
    }

    fun getDirection(context: Context): Direction {
        val raw = prefs(context).getString(KEY_DIRECTION, Direction.DIAGONAL.name)
            ?: Direction.DIAGONAL.name
        return try {
            Direction.valueOf(raw)
        } catch (e: IllegalArgumentException) {
            Direction.DIAGONAL
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
