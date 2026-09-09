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
        val current = getEnabledLocales(context).map { it.code }.toMutableSet()
        if (enabled) current.add(locale.code) else current.remove(locale.code)
        if (current.isEmpty()) current.add(KeyboardLocale.VIETNAMESE.code) // luôn còn ít nhất 1 ngôn ngữ
        prefs(context).edit().putStringSet(KEY_ENABLED_CODES, current).apply()
    }

    fun getCurrentLocale(context: Context): KeyboardLocale {
        val code = prefs(context).getString(KEY_CURRENT_CODE, KeyboardLocale.VIETNAMESE.code)
        return KeyboardLocale.entries.find { it.code == code }
            ?: getEnabledLocales(context).first()
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
