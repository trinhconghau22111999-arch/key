package com.viettype.smartkey

import android.content.Context

/**
 * Bật/tắt hiệu ứng "bong bóng chữ" (bubble nổi lên phía trên phím, phóng to ký tự
 * đang gõ) cho phím đang được nhấn xuống - bong bóng hiện ngay khi ngón tay chạm
 * xuống, tắt ngay khi nhả tay (xem attachKeyTouchHandling() trong
 * SmartKeyboardService). Mặc định TẮT để giữ nguyên giao diện phẳng như trước,
 * người dùng tự bật ở Cài đặt nếu muốn.
 */
object KeyBubbleSettings {

    private const val PREFS_NAME = "key_bubble_settings"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled).apply()
    }
}
