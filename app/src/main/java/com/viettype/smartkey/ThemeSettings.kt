package com.viettype.smartkey

import android.content.Context
import android.graphics.Color

/**
 * Lưu và đọc màu viền phím + giao diện sáng/tối của bàn phím - người dùng
 * chọn ở màn Cài đặt, bàn phím tự vẽ lại theo lựa chọn ngay từ lần hiện lên
 * kế tiếp (không cần khởi động lại app/điện thoại).
 */
object ThemeSettings {

    private const val PREFS_NAME = "theme_settings"
    private const val KEY_ACCENT_COLOR = "accent_color_argb"
    private const val KEY_DARK_THEME = "dark_theme"

    /** Bảng màu dựng sẵn cho người dùng chọn - mỗi màu là 1 cặp (tên hiển thị, mã ARGB).
     *  Đây là màu VIỀN PHÍM (và cũng là màu dùng khi hiệu ứng RGB chạy ở chế độ "1 màu").
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

    private const val DEFAULT_COLOR = 0xFFFAFAFA.toInt() // trắng - mặc định

    fun getAccentColor(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_ACCENT_COLOR, DEFAULT_COLOR)
    }

    fun setAccentColor(context: Context, colorArgb: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_ACCENT_COLOR, colorArgb).apply()
    }

    /** true = nền Tối, false = nền Sáng (mặc định). Áp dụng cho toàn bộ nền bàn phím. */
    fun isDarkTheme(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DARK_THEME, false)
    }

    fun setDarkTheme(context: Context, dark: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_DARK_THEME, dark).apply()
    }

    /** Màu nền cả khối bàn phím (khay chứa toàn bộ các phím). */
    fun keyboardBackgroundColor(context: Context): Int =
        if (isDarkTheme(context)) 0xFF1A0F2E.toInt() else 0xFFEDEAF5.toInt()

    /** Màu nền của từng phím riêng lẻ. */
    fun keyBackgroundColor(context: Context): Int =
        if (isDarkTheme(context)) 0xFF2A1F4A.toInt() else 0xFFFFFFFF.toInt()

    /** Màu chữ/icon trên phím. */
    fun keyTextColor(context: Context): Int =
        if (isDarkTheme(context)) Color.WHITE else 0xFF1A0F2E.toInt()

    /** Màu nền các nút tiện ích (🌐, QR, 🎤, 123) ở hàng trên cùng. */
    fun utilityButtonBackgroundColor(context: Context): Int =
        if (isDarkTheme(context)) Color.parseColor("#332A1F4A") else Color.parseColor("#14000000")
}
