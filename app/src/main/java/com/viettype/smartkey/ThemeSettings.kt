package com.viettype.smartkey

import android.content.Context

/**
 * Lưu và đọc màu chủ đạo (accent) của bàn phím - người dùng chọn 1 trong bảng
 * màu dựng sẵn ở màn Cài đặt, bàn phím tự vẽ lại theo màu đã chọn ngay từ lần
 * hiện lên kế tiếp (không cần khởi động lại app/điện thoại).
 */
object ThemeSettings {

    private const val PREFS_NAME = "theme_settings"
    private const val KEY_ACCENT_COLOR = "accent_color_argb"

    /** Bảng màu dựng sẵn cho người dùng chọn - mỗi màu là 1 cặp (tên hiển thị, mã ARGB).
     *  Thứ tự: đỏ, xanh dương, xanh lá, vàng, hồng, đen, trắng, cam, tím, nâu. */
    val PRESET_COLORS: List<Pair<String, Int>> = listOf(
        "Đỏ" to 0xFFE57373.toInt(),
        "Xanh dương" to 0xFF64B5F6.toInt(),
        "Xanh lá" to 0xFF81C784.toInt(),
        "Vàng" to 0xFFFFD54F.toInt(),
        "Hồng" to 0xFFF06292.toInt(),
        "Đen" to 0xFF212121.toInt(),
        "Trắng" to 0xFFFAFAFA.toInt(),
        "Cam" to 0xFFFFB74D.toInt(),
        "Tím" to 0xFFB388FF.toInt(),
        "Nâu" to 0xFF8D6E63.toInt(),
    )

    private const val DEFAULT_COLOR = 0xFFB388FF.toInt()

    fun getAccentColor(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_ACCENT_COLOR, DEFAULT_COLOR)
    }

    fun setAccentColor(context: Context, colorArgb: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_ACCENT_COLOR, colorArgb).apply()
    }
}
