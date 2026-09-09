package com.viettype.smartkey

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bộ bắt lỗi không mong muốn (uncaught exception) của toàn bộ ứng dụng, ghi lại
 * thành 1 file text đơn giản trong bộ nhớ riêng của app để có thể mở lại xem sau
 * khi khởi động lại - hữu ích để chẩn đoán crash xảy ra ở Service bàn phím (IME),
 * vốn không có giao diện riêng để tự hiện thông báo lỗi ngay lúc xảy ra.
 *
 * Không dùng thư viện báo lỗi ngoài (Crashlytics...) - chỉ ghi cục bộ, đơn giản,
 * không gửi dữ liệu đi đâu cả.
 */
object CrashLogger {

    private const val LOG_FILE_NAME = "crash_log.txt"
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                appendCrash(appContext, thread.name, throwable)
            } catch (ignored: Throwable) {
                // Không để việc GHI LOG cũng crash tiếp - im lặng bỏ qua.
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun appendCrash(context: Context, threadName: String, throwable: Throwable) {
        val stringWriter = StringWriter()
        throwable.printStackTrace(PrintWriter(stringWriter))
        val entry = buildString {
            append("===== ").append(timeFormat.format(Date())).append(" (thread: ").append(threadName).append(") =====\n")
            append(stringWriter.toString())
            append("\n")
        }
        logFile(context).appendText(entry)
    }

    /** Đọc toàn bộ log đã ghi được (rỗng nếu chưa từng có crash nào). */
    fun readAll(context: Context): String {
        val file = logFile(context)
        return if (file.exists()) file.readText() else ""
    }

    fun clear(context: Context) {
        val file = logFile(context)
        if (file.exists()) file.delete()
    }

    fun hasLog(context: Context): Boolean = logFile(context).let { it.exists() && it.length() > 0 }

    private fun logFile(context: Context): File = File(context.filesDir, LOG_FILE_NAME)
}
