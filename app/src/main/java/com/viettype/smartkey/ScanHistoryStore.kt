package com.viettype.smartkey

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Lưu lại danh sách các mã QR/vạch đã quét được (nội dung + thời điểm).
 *
 * KHÔNG giới hạn số mục mỗi ngày. Vì không giới hạn nên dữ liệu được ghi vào 1 FILE riêng
 * (mỗi mã 1 dòng JSON, chỉ NỐI THÊM vào cuối file - tốn công như nhau dù lịch sử dài bao nhiêu),
 * thay vì nhét cả danh sách vào SharedPreferences như trước (mỗi lần quét phải đọc + ghi lại
 * toàn bộ, và SharedPreferences giữ hết trong RAM - lịch sử càng dài càng chậm). Người dùng có
 * thể bật tự động xoá toàn bộ lịch sử mỗi 1 hoặc 2 ngày (xem [setAutoClearDays]).
 */
object ScanHistoryStore {

    data class ScanEntry(val content: String, val timestampMs: Long)

    private const val PREFS_NAME = "scan_history"
    private const val KEY_LEGACY_ENTRIES_JSON = "entries_json" // bản cũ lưu trong SharedPreferences
    private const val KEY_DUPLICATE_LIMIT = "duplicate_limit"
    private const val KEY_AUTO_CLEAR_DAYS = "auto_clear_days"
    private const val KEY_LAST_CLEAR_AT = "last_clear_at"
    private const val HISTORY_FILE_NAME = "scan_history.jsonl"
    private const val DAY_MS = 24L * 60L * 60L * 1000L

    /** Tự động xoá lịch sử: 0 = tắt, 1 = mỗi ngày, 2 = mỗi 2 ngày. */
    const val AUTO_CLEAR_OFF = 0
    const val AUTO_CLEAR_EVERY_DAY = 1
    const val AUTO_CLEAR_EVERY_2_DAYS = 2

    private val lock = Any()

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

    fun getAutoClearDays(context: Context): Int =
        prefs(context).getInt(KEY_AUTO_CLEAR_DAYS, AUTO_CLEAR_OFF)

    /** Đặt chu kỳ tự xoá (0/1/2 ngày). Bắt đầu đếm từ NGAY LÚC đặt. */
    fun setAutoClearDays(context: Context, days: Int) = synchronized(lock) {
        val value = days.coerceIn(AUTO_CLEAR_OFF, AUTO_CLEAR_EVERY_2_DAYS)
        prefs(context).edit()
            .putInt(KEY_AUTO_CLEAR_DAYS, value)
            .putLong(KEY_LAST_CLEAR_AT, System.currentTimeMillis())
            .apply()
    }

    /** Xoá toàn bộ lịch sử nếu đã đến hạn theo chu kỳ tự xoá. Không có tác vụ nền nên hàm này
     *  được gọi mỗi khi quét / mở lịch / bàn phím hiện lên - kết quả với người dùng là như nhau. */
    fun autoClearIfDue(context: Context) = synchronized(lock) { autoClearIfDueLocked(context) }

    private fun autoClearIfDueLocked(context: Context) {
        val prefs = prefs(context)
        val days = prefs.getInt(KEY_AUTO_CLEAR_DAYS, AUTO_CLEAR_OFF)
        if (days <= AUTO_CLEAR_OFF) return
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_CLEAR_AT, 0L)
        when {
            last == 0L || now < last -> prefs.edit().putLong(KEY_LAST_CLEAR_AT, now).apply() // mốc chưa có / đồng hồ bị chỉnh lùi
            now - last >= days * DAY_MS -> {
                deleteAllLocked(context)
                prefs.edit().putLong(KEY_LAST_CLEAR_AT, now).apply()
            }
        }
    }

    fun addEntry(context: Context, content: String) = synchronized(lock) {
        autoClearIfDueLocked(context)
        migrateLegacyLocked(context)
        val line = entryToJson(ScanEntry(content, System.currentTimeMillis())) + "\n"
        try {
            FileOutputStream(historyFile(context), true).use { it.write(line.toByteArray(Charsets.UTF_8)) }
        } catch (ignored: Exception) {
        }
    }

    /** Mới nhất đứng đầu. */
    fun getEntries(context: Context): List<ScanEntry> = synchronized(lock) {
        autoClearIfDueLocked(context)
        migrateLegacyLocked(context)
        val file = historyFile(context)
        if (!file.exists()) return emptyList()
        val result = ArrayList<ScanEntry>()
        try {
            file.forEachLine(Charsets.UTF_8) { line ->
                if (line.isBlank()) return@forEachLine
                try {
                    val obj = JSONObject(line)
                    result.add(ScanEntry(obj.getString("content"), obj.getLong("time")))
                } catch (ignored: Exception) {
                    // dòng hỏng (vd máy tắt đột ngột lúc đang ghi) - bỏ qua, không làm mất cả lịch sử
                }
            }
        } catch (ignored: Exception) {
        }
        result.reverse()
        result
    }

    fun clearEntries(context: Context) = synchronized(lock) {
        deleteAllLocked(context)
        prefs(context).edit().putLong(KEY_LAST_CLEAR_AT, System.currentTimeMillis()).apply()
    }

    private fun deleteAllLocked(context: Context) {
        historyFile(context).delete()
        prefs(context).edit().remove(KEY_LEGACY_ENTRIES_JSON).apply()
    }

    /** Chuyển lịch sử cũ (lưu trong SharedPreferences) sang file, 1 lần duy nhất. */
    private fun migrateLegacyLocked(context: Context) {
        val prefs = prefs(context)
        val raw = prefs.getString(KEY_LEGACY_ENTRIES_JSON, null) ?: return
        try {
            val legacy = deserializeLegacy(raw) // mới nhất đứng đầu
            val file = historyFile(context)
            FileOutputStream(file, true).use { out ->
                for (entry in legacy.asReversed()) { // file lưu cũ nhất trước
                    out.write((entryToJson(entry) + "\n").toByteArray(Charsets.UTF_8))
                }
            }
        } catch (ignored: Exception) {
        }
        prefs.edit().remove(KEY_LEGACY_ENTRIES_JSON).apply()
    }

    private fun historyFile(context: Context) = File(context.applicationContext.filesDir, HISTORY_FILE_NAME)

    fun formatTimestamp(timestampMs: Long): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestampMs
        val fmt = SimpleDateFormat("HH:mm dd/MM/yyyy", Locale.US)
        return fmt.format(cal.time)
    }

    private fun entryToJson(entry: ScanEntry): String =
        JSONObject().put("content", entry.content).put("time", entry.timestampMs).toString()

    private fun deserializeLegacy(raw: String): List<ScanEntry> {
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
