package com.viettype.smartkey

import android.content.Context
import org.json.JSONArray

/**
 * Bộ lọc "ký tự đặc biệt" cho nội dung mã quét được.
 *
 *  - Khi BẬT ([isBlockSpecialEnabled]): nếu mã quét có chứa ký tự đặc biệt thì KHÔNG xuất
 *    kết quả vào ô nhập (và không lưu vào lịch sử quét).
 *  - "Ký tự đặc biệt" = mọi ký tự KHÔNG phải chữ cái, chữ số, khoảng trắng (kể cả dấu cách
 *    không ngắt dòng) hoặc dấu kết hợp của chữ có dấu (tiếng Việt dạng tổ hợp).
 *  - "Ngoại trừ": danh sách các ô loại trừ - mọi ký tự gõ trong các ô này KHÔNG bị coi là
 *    đặc biệt (vẫn được xuất). Mỗi ô có thể chứa 1 hoặc nhiều ký tự (vd "-_./"). Nút "+"
 *    ở màn Cài đặt thêm ô loại trừ mới; luôn có tối thiểu 1 ô.
 */
object ScanFilterSettings {

    private const val PREFS_NAME = "scan_filter_settings"
    private const val KEY_BLOCK_SPECIAL = "block_special_chars"
    private const val KEY_EXCEPTIONS_JSON = "exceptions_json"

    const val MAX_EXCEPTION_FIELDS = 10

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isBlockSpecialEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BLOCK_SPECIAL, false)

    fun setBlockSpecialEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BLOCK_SPECIAL, enabled).apply()
    }

    /** Danh sách các ô loại trừ (mỗi phần tử = nội dung 1 ô). Luôn trả về ít nhất 1 ô. */
    fun getExceptionFields(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_EXCEPTIONS_JSON, null) ?: return listOf("")
        return try {
            val arr = JSONArray(raw)
            val list = (0 until arr.length()).map { arr.optString(it, "") }
            if (list.isEmpty()) listOf("") else list
        } catch (e: Exception) {
            listOf("")
        }
    }

    private fun saveExceptionFields(context: Context, fields: List<String>) {
        val arr = JSONArray()
        for (f in fields) arr.put(f)
        prefs(context).edit().putString(KEY_EXCEPTIONS_JSON, arr.toString()).apply()
    }

    fun setExceptionField(context: Context, index: Int, value: String) {
        val list = getExceptionFields(context).toMutableList()
        if (index !in list.indices) return
        list[index] = value
        saveExceptionFields(context, list)
    }

    fun addExceptionField(context: Context) {
        val list = getExceptionFields(context).toMutableList()
        if (list.size >= MAX_EXCEPTION_FIELDS) return
        list.add("")
        saveExceptionFields(context, list)
    }

    fun removeExceptionField(context: Context, index: Int) {
        val list = getExceptionFields(context).toMutableList()
        if (list.size <= 1 || index !in list.indices) return
        list.removeAt(index)
        saveExceptionFields(context, list)
    }

    /** Tập mã ký tự (code point) được phép dù là "đặc biệt" - gộp từ tất cả các ô loại trừ. */
    private fun exceptionCodePoints(context: Context): Set<Int> {
        val result = HashSet<Int>()
        for (field in getExceptionFields(context)) {
            var i = 0
            while (i < field.length) {
                val cp = field.codePointAt(i)
                i += Character.charCount(cp)
                result.add(cp)
            }
        }
        return result
    }

    /**
     * Trả về ký tự đặc biệt ĐẦU TIÊN tìm thấy trong [content] (để báo cho người dùng biết),
     * hoặc null nếu bộ lọc đang tắt / mã không có ký tự đặc biệt nào (ngoài các ký tự ngoại trừ).
     */
    fun findBlockedChar(context: Context, content: String): String? {
        if (!isBlockSpecialEnabled(context)) return null
        val allowed = exceptionCodePoints(context)
        var i = 0
        while (i < content.length) {
            val cp = content.codePointAt(i)
            i += Character.charCount(cp)
            if (Character.isLetterOrDigit(cp) || Character.isWhitespace(cp) || Character.isSpaceChar(cp)) continue
            if (Character.getType(cp) == Character.NON_SPACING_MARK.toInt()) continue
            if (cp in allowed) continue
            return String(Character.toChars(cp))
        }
        return null
    }
}
