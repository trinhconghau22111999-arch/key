package com.viettype.smartkey

import android.content.Context

/**
 * Bật/tắt hàng phím số (1-0) hiện thêm phía TRÊN CÙNG của trang gõ chữ đầu tiên
 * (Page.LETTERS) - giúp gõ số nhanh mà không cần chuyển qua trang "?123". Mặc
 * định TẮT để giữ bàn phím gọn như trước.
 */
object NumberRowSettings {

    private const val PREFS_NAME = "number_row_settings"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled).apply()
    }
}
