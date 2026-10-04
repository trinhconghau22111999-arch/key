package com.viettype.smartkey

import android.content.Context

/**
 * Bật/tắt tiếng "tách" mỗi lần bấm phím. Tiếng do hệ thống phát (AudioManager.playSoundEffect)
 * nên vẫn tuân theo mục "Âm thanh khi chạm" trong Cài đặt hệ thống của máy.
 */
object KeyClickSettings {

    private const val PREFS_NAME = "key_click_settings"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled).apply()
    }
}
