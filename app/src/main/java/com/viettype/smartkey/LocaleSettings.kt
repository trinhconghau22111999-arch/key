package com.viettype.smartkey

import android.content.Context

/**
 * Danh sách ngôn ngữ bàn phím hỗ trợ + ngôn ngữ nào đang được BẬT (người dùng có
 * thể bật nhiều hơn 2, nhưng chỉ 2 ngôn ngữ bật gần nhất mới được gắn vào cử chỉ
 * "vuốt ngang phím Cách" để chuyển nhanh qua lại) và ngôn ngữ nào đang dùng ngay
 * lúc này.
 */
object LocaleSettings {

    enum class KeyboardLocale(val code: String, val displayName: String, val usesTelex: Boolean) {
        VIETNAMESE("vi", "Tiếng Việt", usesTelex = true),
        ENGLISH("en", "English", usesTelex = false),
    }

    private const val PREFS_NAME = "locale_settings"
    private const val KEY_ENABLED_CODES = "enabled_codes"
    private const val KEY_CURRENT_CODE = "current_code"

    fun getEnabledLocales(context: Context): List<KeyboardLocale> {
        val raw = prefs(context).getStringSet(KEY_ENABLED_CODES, null)
            ?: setOf(KeyboardLocale.VIETNAMESE.code, KeyboardLocale.ENGLISH.code)
        val result = KeyboardLocale.entries.filter { raw.contains(it.code) }
        return result.ifEmpty { listOf(KeyboardLocale.VIETNAMESE) }
    }

    fun setLocaleEnabled(context: Context, locale: KeyboardLocale, enabled: Boolean) {
        val updated = getEnabledLocales(context).map { it.code }.toMutableSet()
        if (enabled) updated.add(locale.code) else updated.remove(locale.code)
        if (updated.isEmpty()) updated.add(KeyboardLocale.VIETNAMESE.code) // luôn còn ít nhất 1 ngôn ngữ
        prefs(context).edit().putStringSet(KEY_ENABLED_CODES, updated).apply()

        // Nếu ngôn ngữ ĐANG GÕ vừa bị tắt (không còn trong danh sách bật) - vd chỉ còn
        // đúng 1 ngôn ngữ được bật - phải TỰ CHUYỂN bàn phím sang ngôn ngữ còn lại đang
        // bật ngay, tránh bị "khoá cứng" vào ngôn ngữ vừa tắt (bug cũ: getCurrentLocale
        // không kiểm tra ngôn ngữ hiện tại có còn nằm trong danh sách bật hay không).
        val currentCode = prefs(context).getString(KEY_CURRENT_CODE, null)
        if (currentCode != null && !updated.contains(currentCode)) {
            val fallback = KeyboardLocale.entries.firstOrNull { updated.contains(it.code) }
                ?: KeyboardLocale.VIETNAMESE
            setCurrentLocale(context, fallback)
        }
    }

    fun getCurrentLocale(context: Context): KeyboardLocale {
        val enabled = getEnabledLocales(context)
        val code = prefs(context).getString(KEY_CURRENT_CODE, KeyboardLocale.VIETNAMESE.code)
        val match = KeyboardLocale.entries.find { it.code == code }
        // Chỉ chấp nhận ngôn ngữ đã lưu nếu nó vẫn còn đang được BẬT - nếu không (vd người
        // dùng vừa tắt ngôn ngữ đang gõ), tự rơi về ngôn ngữ (duy nhất) đang bật.
        return if (match != null && enabled.contains(match)) match else enabled.first()
    }

    fun setCurrentLocale(context: Context, locale: KeyboardLocale) {
        prefs(context).edit().putString(KEY_CURRENT_CODE, locale.code).apply()
    }

    /** Chuyển sang ngôn ngữ TIẾP THEO trong danh sách đang bật (dùng cho cử chỉ vuốt phím Cách). */
    fun switchToNextLocale(context: Context): KeyboardLocale {
        val enabled = getEnabledLocales(context)
        if (enabled.size <= 1) return enabled.first()
        val current = getCurrentLocale(context)
        val currentIndex = enabled.indexOf(current).coerceAtLeast(0)
        val next = enabled[(currentIndex + 1) % enabled.size]
        setCurrentLocale(context, next)
        return next
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
