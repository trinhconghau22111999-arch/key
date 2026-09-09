package com.viettype.smartkey

import android.content.Context

/**
 * Bật/tắt hiệu ứng "tạo bóng" (đổ bóng thật, dùng View.elevation của Android) cho
 * phím đang được nhấn xuống - phím nổi lên và đổ bóng quanh viền trong lúc ngón
 * tay còn chạm, tắt bóng ngay khi nhả tay (xem attachKeyTouchHandling() trong
 * SmartKeyboardService). Mặc định TẮT để giữ nguyên giao diện phẳng như trước,
 * người dùng tự bật ở Cài đặt nếu muốn.
 */
object KeyShadowSettings {

    private const val PREFS_NAME = "key_shadow_settings"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled).apply()
    }
}
