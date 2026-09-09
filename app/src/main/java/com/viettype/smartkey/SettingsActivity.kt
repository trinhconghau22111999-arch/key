package com.viettype.smartkey

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File

/**
 * Màn hình Cài đặt chính (launcher của app) - gồm các khối: trạng thái bàn phím
 * đã bật hay chưa, chọn ngôn ngữ, màu chủ đạo, hiệu ứng viền sáng, độ rung, giới
 * hạn quét/ngày, lịch sử quét (xem/xuất Excel/xoá), và nhật ký lỗi (nếu có).
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var contentBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        contentBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 64, 40, 64)
            setBackgroundColor(Color.parseColor("#1A0F2E"))
        }
        scroll.addView(contentBox)
        setContentView(scroll)

        rebuildAll()
    }

    override fun onResume() {
        super.onResume()
        rebuildAll() // trạng thái bàn phím có thể vừa đổi sau khi quay lại từ Cài đặt hệ thống
    }

    private fun rebuildAll() {
        contentBox.removeAllViews()
        contentBox.addView(sectionTitle("VN Smart Key"))
        contentBox.addView(buildKeyboardStatusSection())
        contentBox.addView(spacer())
        contentBox.addView(sectionTitle("Ngôn ngữ"))
        contentBox.addView(buildLanguageSection())
        contentBox.addView(spacer())
        contentBox.addView(sectionTitle("Bố cục bàn phím"))
        contentBox.addView(buildLayoutSection())
        contentBox.addView(spacer())
        contentBox.addView(sectionTitle("Màu chủ đạo"))
        contentBox.addView(buildColorSection())
        contentBox.addView(spacer())
        contentBox.addView(sectionTitle("Hiệu ứng viền sáng"))
        contentBox.addView(buildLedSection())
        contentBox.addView(spacer())
        contentBox.addView(sectionTitle("Rung khi gõ"))
        contentBox.addView(buildVibrationSection())
        contentBox.addView(spacer())
        contentBox.addView(sectionTitle("Giới hạn quét mã / ngày"))
        contentBox.addView(buildScanLimitSection())
        contentBox.addView(spacer())
        contentBox.addView(sectionTitle("Giới hạn quét trùng lặp"))
        contentBox.addView(buildDuplicateScanLimitSection())
        contentBox.addView(spacer())
        contentBox.addView(sectionTitle("Lịch sử quét"))
        contentBox.addView(buildHistorySection())

        if (CrashLogger.hasLog(this)) {
            contentBox.addView(spacer())
            contentBox.addView(sectionTitle("Nhật ký lỗi gần nhất"))
            contentBox.addView(buildCrashLogSection())
        }
    }

    // ============================== TRẠNG THÁI BÀN PHÍM ==============================

    private fun buildKeyboardStatusSection(): View {
        val enabled = isThisKeyboardEnabled()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(bodyText(if (enabled) "Đã bật trong danh sách bàn phím." else "Chưa bật - cần bật thủ công."))
        box.addView(actionButton("Mở màn hướng dẫn bật bàn phím") {
            startActivity(Intent(this, OnboardingActivity::class.java))
        })
        return box
    }

    private fun isThisKeyboardEnabled(): Boolean {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any { it.packageName == packageName }
    }

    // ============================== NGÔN NGỮ ==============================

    private fun buildLanguageSection(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val enabledCodes = LocaleSettings.getEnabledLocales(this).map { it.code }.toSet()

        for (locale in LocaleSettings.KeyboardLocale.entries) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 12, 0, 12)
            }
            row.addView(TextView(this).apply {
                text = locale.displayName
                setTextColor(Color.WHITE)
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(Switch(this).apply {
                isChecked = enabledCodes.contains(locale.code)
                setOnCheckedChangeListener { _, isChecked ->
                    LocaleSettings.setLocaleEnabled(this@SettingsActivity, locale, isChecked)
                }
            })
            box.addView(row)
        }
        return box
    }

    // ============================== BỐ CỤC BÀN PHÍM ==============================

    private fun buildLayoutSection(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(TextView(this).apply {
            text = "Luôn bật hàng phím số"
            setTextColor(Color.WHITE)
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(Switch(this).apply {
            isChecked = NumberRowSettings.isEnabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, isChecked -> NumberRowSettings.setEnabled(this@SettingsActivity, isChecked) }
        })
        box.addView(row)
        box.addView(bodyText("Hiện thêm 1 hàng số 0-9 ở trên cùng, dành cho trang gõ chữ đầu tiên."))
        return box
    }

    // ============================== MÀU CHỦ ĐẠO ==============================
    private fun buildColorSection(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            // Cho phép cuộn ngang vì giờ có 10 màu, không đủ chỗ hiển thị hết trên 1 hàng.
        }
        val current = ThemeSettings.getAccentColor(this)

        for ((name, color) in ThemeSettings.PRESET_COLORS) {
            val swatch = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(90, 90).also { it.setMargins(8, 8, 8, 8) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    // Viền mờ mặc định cho MỌI ô màu (kể cả khi chưa chọn) - không có viền
                    // này thì màu Đen gần như biến mất vào nền tối #1A0F2E của app.
                    if (color == current) setStroke(6, Color.WHITE) else setStroke(2, Color.parseColor("#55FFFFFF"))
                }
                contentDescription = name
                setOnClickListener {
                    ThemeSettings.setAccentColor(this@SettingsActivity, color)
                    rebuildAll()
                }
            }
            row.addView(swatch)
        }
        val scroll = android.widget.HorizontalScrollView(this).apply { addView(row) }
        return scroll
    }

    // ============================== HIỆU ỨNG VIỀN SÁNG ==============================

    private fun buildLedSection(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val modes = LedEffectSettings.Mode.entries
        val modeNames = mapOf(
            LedEffectSettings.Mode.OFF to "Tắt",
            LedEffectSettings.Mode.RAINBOW_CYCLE to "Cầu vồng chạy",
            LedEffectSettings.Mode.BREATHING to "Thở (mờ dần - sáng dần)",
            LedEffectSettings.Mode.STATIC_COLOR to "Màu chủ đạo cố định",
        )
        val current = LedEffectSettings.getMode(this)

        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (mode in modes) {
            modeRow.addView(chip(modeNames[mode] ?: mode.name, mode == current) {
                LedEffectSettings.setMode(this, mode)
                rebuildAll()
            })
        }
        box.addView(modeRow)

        box.addView(bodyText("Tốc độ chạy hiệu ứng"))
        box.addView(SeekBar(this).apply {
            max = 100
            progress = LedEffectSettings.getSpeedPercent(this@SettingsActivity)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) LedEffectSettings.setSpeedPercent(this@SettingsActivity, progress)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        })
        return box
    }

    // ============================== RUNG KHI GÕ ==============================

    private fun buildVibrationSection(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val switchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        switchRow.addView(TextView(this).apply {
            text = "Bật rung"
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        switchRow.addView(Switch(this).apply {
            isChecked = VibrationSettings.isEnabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, isChecked -> VibrationSettings.setEnabled(this@SettingsActivity, isChecked) }
        })
        box.addView(switchRow)

        box.addView(bodyText("Kéo thanh để tự chỉnh độ rung khi gõ - kéo về 0% để tắt hẳn rung."))

        val valueLabel = bodyText("${VibrationSettings.getStrengthPercent(this@SettingsActivity)}%").apply {
            setTextColor(Color.WHITE)
            textSize = 20f
        }
        box.addView(valueLabel)

        // Rung NGAY trong lúc kéo (không đợi buông tay) để cảm nhận độ mạnh đang chọn,
        // nhưng giới hạn tần suất (>= 60ms/lần) để tránh rung dồn dập gây khó chịu.
        var lastDragTickAt = 0L
        box.addView(SeekBar(this).apply {
            max = 100
            progress = VibrationSettings.getStrengthPercent(this@SettingsActivity)
            progressTintList = android.content.res.ColorStateList.valueOf(ThemeSettings.getAccentColor(this@SettingsActivity))
            thumbTintList = android.content.res.ColorStateList.valueOf(ThemeSettings.getAccentColor(this@SettingsActivity))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    VibrationSettings.setStrengthPercent(this@SettingsActivity, progress)
                    valueLabel.text = "$progress%"
                    val now = System.currentTimeMillis()
                    if (now - lastDragTickAt >= 60) {
                        lastDragTickAt = now
                        VibrationSettings.tick(this@SettingsActivity)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    VibrationSettings.tick(this@SettingsActivity) // rung thử lần cuối để chốt cảm nhận
                }
            })
        })
        return box
    }

    // ============================== GIỚI HẠN QUÉT TRÙNG LẶP ==============================

    private fun buildDuplicateScanLimitSection(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(bodyText(
            "Khi quét liên tục cùng 1 mã QR/mã vạch nhiều lần liền nhau, chỉ xuất dữ liệu " +
                "tối đa số lần đặt dưới đây rồi tự dừng (quét mã KHÁC thì đếm lại từ đầu)."
        ))

        val countLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            text = ScanHistoryStore.getDuplicateLimit(this@SettingsActivity).toString()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 16, 0, 0)
        }
        row.addView(stepperButton("−") {
            val newValue = ScanHistoryStore.getDuplicateLimit(this) - 1
            ScanHistoryStore.setDuplicateLimit(this, newValue)
            countLabel.text = ScanHistoryStore.getDuplicateLimit(this).toString()
        })
        row.addView(countLabel)
        row.addView(stepperButton("+") {
            val newValue = ScanHistoryStore.getDuplicateLimit(this) + 1
            ScanHistoryStore.setDuplicateLimit(this, newValue)
            countLabel.text = ScanHistoryStore.getDuplicateLimit(this).toString()
        })
        box.addView(row)
        return box
    }

    private fun stepperButton(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 20f
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(140, 140).also { it.setMargins(16, 0, 16, 0) }
        background = GradientDrawable().apply {
            cornerRadius = 20f
            setStroke(4, ThemeSettings.getAccentColor(this@SettingsActivity))
            setColor(Color.parseColor("#2A1F4A"))
        }
        setOnClickListener { onClick() }
    }

    // ============================== GIỚI HẠN QUÉT ==============================

    private fun buildScanLimitSection(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val used = ScanHistoryStore.getTodayCount(this)
        val limit = ScanHistoryStore.getDailyLimit(this)
        box.addView(bodyText(
            if (limit <= 0) "Không giới hạn - đã quét $used lần hôm nay."
            else "Đã dùng $used / $limit lần hôm nay."
        ))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (presetLimit in listOf(10, 20, 50, 0)) {
            row.addView(chip(if (presetLimit == 0) "Không giới hạn" else "$presetLimit/ngày", presetLimit == limit) {
                ScanHistoryStore.setDailyLimit(this, presetLimit)
                rebuildAll()
            })
        }
        box.addView(row)
        return box
    }

    // ============================== LỊCH SỬ QUÉT ==============================

    private fun buildHistorySection(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val entries = ScanHistoryStore.getEntries(this)

        box.addView(bodyText("${entries.size} mục đã lưu."))

        val actionsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actionsRow.addView(actionButton("Xuất Excel & chia sẻ") { exportAndShareHistory() })
        actionsRow.addView(actionButton("Xoá lịch sử") {
            ScanHistoryStore.clearEntries(this)
            Toast.makeText(this, "Đã xoá lịch sử quét", Toast.LENGTH_SHORT).show()
            rebuildAll()
        })
        box.addView(actionsRow)

        for (entry in entries.take(15)) {
            box.addView(bodyText("• ${entry.content}  (${ScanHistoryStore.formatTimestamp(entry.timestampMs)})"))
        }
        if (entries.size > 15) box.addView(bodyText("...và ${entries.size - 15} mục khác."))
        return box
    }

    private fun exportAndShareHistory() {
        val entries = ScanHistoryStore.getEntries(this)
        if (entries.isEmpty()) {
            Toast.makeText(this, "Chưa có lịch sử để xuất.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val exportDir = File(cacheDir, "exports").apply { mkdirs() }
            val file = File(exportDir, "lich_su_quet.xlsx")
            val rows = entries.map { listOf(it.content, ScanHistoryStore.formatTimestamp(it.timestampMs)) }
            XlsxExporter.writeXlsx(file, listOf("Nội dung", "Thời gian"), rows)

            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Chia sẻ file lịch sử quét"))
        } catch (e: Exception) {
            Toast.makeText(this, "Không xuất được file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ============================== NHẬT KÝ LỖI ==============================

    private fun buildCrashLogSection(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(bodyText(CrashLogger.readAll(this).takeLast(1200)))
        box.addView(actionButton("Xoá nhật ký lỗi") {
            CrashLogger.clear(this)
            rebuildAll()
        })
        return box
    }

    // ============================== TIỆN ÍCH DỰNG GIAO DIỆN ==============================

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 18f
        setPadding(0, 24, 0, 8)
    }

    private fun bodyText(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.LTGRAY)
        textSize = 14f
        setPadding(0, 4, 0, 4)
    }

    private fun spacer(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2)
        setBackgroundColor(Color.parseColor("#33FFFFFF"))
    }

    private fun actionButton(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    private fun chip(text: String, selected: Boolean, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        setTextColor(if (selected) Color.BLACK else Color.WHITE)
        setPadding(24, 12, 24, 12)
        textSize = 13f
        val margin = 8
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.setMargins(margin, margin, margin, margin) }
        background = GradientDrawable().apply {
            cornerRadius = 24f
            setColor(if (selected) ThemeSettings.getAccentColor(this@SettingsActivity) else Color.parseColor("#33FFFFFF"))
        }
        setOnClickListener { onClick() }
    }
}
