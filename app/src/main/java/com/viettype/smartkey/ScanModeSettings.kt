package com.viettype.smartkey

import android.content.Context

/**
 * Chế độ hoạt động của khung quét sau khi đọc được mã:
 *  - LIÊN TỤC (mặc định, giữ đúng hành vi cũ): khung quét vẫn mở để quét mã kế tiếp,
 *    người dùng tự bấm "Huỷ" khi xong.
 *  - MỘT LẦN: xuất xong 1 mã thì tự đóng khung quét, trả lại bàn phím.
 */
object ScanModeSettings {

    private const val PREFS_NAME = "scan_mode_settings"
    private const val KEY_CONTINUOUS = "continuous"

    fun isContinuous(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_CONTINUOUS, true)

    fun setContinuous(context: Context, continuous: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_CONTINUOUS, continuous).apply()
    }
}
