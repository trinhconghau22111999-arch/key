package com.viettype.smartkey

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Lưu lại danh sách các mã QR/vạch đã quét được (nội dung + thời điểm).
 */
object ScanHistoryStore {

    data class ScanEntry(val content: String, val timestampMs: Long)

    private const val PREFS_NAME = "scan_history"
    private const val KEY_ENTRIES_JSON = "entries_json"
    private const val KEY_DUPLICATE_LIMIT = "duplicate_limit"

    private const val MAX_STORED_ENTRIES = 500

    /** Số lần TỐI ĐA cho phép xuất liên tiếp CÙNG 1 nội dung mã trong 1 lượt quét
     *  liên tục - quét sang mã KHÁC sẽ đếm lại từ đầu. Mặc định 2 lần. */
    const val DEFAULT_DUPLICATE_LIMIT = 2
    const val MIN_DUPLICATE_LIMIT = 1
    const val MAX_DUPLICATE_LIMIT = 20

    fun getDuplicateLimit(context: Context): Int =
        prefs(context).getInt(KEY_DUPLICATE_LIMIT, DEFAULT_DUPLICATE_LIMIT)

    fun setDuplicateLimit(context: Context, limit: Int) {
        prefs(context).edit()
            .putInt(KEY_DUPLICATE_LIMIT, limit.coerceIn(MIN_DUPLICATE_LIMIT, MAX_DUPLICATE_LIMIT))
            .apply()
    }

    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun addEntry(context: Context, content: String) {
        val prefs = prefs(context)
        val list = getEntries(context).toMutableList()
        list.add(0, ScanEntry(content, System.currentTimeMillis()))
        while (list.size > MAX_STORED_ENTRIES) list.removeAt(list.lastIndex)
        prefs.edit().putString(KEY_ENTRIES_JSON, serialize(list)).apply()
    }

    fun getEntries(context: Context): List<ScanEntry> {
        val raw = prefs(context).getString(KEY_ENTRIES_JSON, null) ?: return emptyList()
        return deserialize(raw)
    }

    fun clearEntries(context: Context) {
        prefs(context).edit().remove(KEY_ENTRIES_JSON).apply()
    }

    fun formatTimestamp(timestampMs: Long): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestampMs
        val fmt = SimpleDateFormat("HH:mm dd/MM/yyyy", Locale.US)
        return fmt.format(cal.time)
    }

    private fun serialize(list: List<ScanEntry>): String {
        val arr = JSONArray()
        for (entry in list) {
            val obj = JSONObject()
            obj.put("content", entry.content)
            obj.put("time", entry.timestampMs)
            arr.put(obj)
        }
        return arr.toString()
    }

    private fun deserialize(raw: String): List<ScanEntry> {
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                ScanEntry(obj.getString("content"), obj.getLong("time"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
