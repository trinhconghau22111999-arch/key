package com.viettype.smartkey

import android.content.Context

/**
 * Bật/tắt + chỉnh âm lượng tiếng "tách" mỗi lần bấm phím (0..100%, 0% = tắt hẳn).
 * Tiếng phát qua [KeyClickPlayer], độ lớn thực tế còn phụ thuộc âm lượng Media của máy.
 */
object KeyClickSettings {

    private const val PREFS_NAME = "key_click_settings"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_VOLUME_PERCENT = "volume_percent"

    private const val DEFAULT_VOLUME = 60

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getVolumePercent(context: Context): Int = prefs(context).getInt(KEY_VOLUME_PERCENT, DEFAULT_VOLUME)

    fun setVolumePercent(context: Context, percent: Int) {
        prefs(context).edit().putInt(KEY_VOLUME_PERCENT, percent.coerceIn(0, 100)).apply()
    }

    /** Âm lượng thực dùng khi gõ: 0 nếu đang tắt, ngược lại là mức người dùng chọn. */
    fun effectiveVolumePercent(context: Context): Int =
        if (isEnabled(context)) getVolumePercent(context) else 0
}
