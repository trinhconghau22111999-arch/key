package com.viettype.smartkey

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
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

    /** Ô nhập "giả" chỉ để tự động bật BÀN PHÍM THẬT lên xem trước ngay trong màn Cài đặt -
     *  không lưu/dùng giá trị gõ vào đây vào việc gì cả, xoá tự do thoải mái. */
    private var previewEditText: EditText? = null

    /** true khi lần rebuild NÀY là do vừa đổi màu/hiệu ứng - báo cho buildKeyboardPreviewSection()
     *  biết cần CHỜ MỘT NHỊP rồi mới bật lại bàn phím (thay vì bật lại ngay lập tức), để bàn
     *  phím thật kịp tắt hẳn trước khi bật lại - đúng như "tắt rồi bật lại" người dùng yêu cầu. */
    private var pendingPreviewKeyboardRestart = false

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

    /** forceKeyboardRestart = true: dùng cho lúc đổi MÀU SẮC/HIỆU ỨNG ĐÈN - chủ động ẩn hẳn
     *  bàn phím thật (y hệt bấm nút Back) trước khi build lại, rồi mới bật lại sau 1 nhịp ngắn.
     *  Bàn phím có sẵn logic tự đọc lại theme mỗi lần MỞ LẠI (refreshTheme() trong
     *  onStartInputView() - xem SmartKeyboardService.kt), nên tắt hẳn rồi bật lại là cách
     *  CHẮC CHẮN nhất để ép nó chạy lại đúng logic đó, thay vì chỉ đổi focus ngầm giữa 2 View. */
    private fun rebuildAll(forceKeyboardRestart: Boolean = false) {
        if (forceKeyboardRestart) {
            previewEditText?.let {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(it.windowToken, 0) // = bấm Back để tắt bàn phím
            }
            pendingPreviewKeyboardRestart = true
        }
        contentBox.removeAllViews()
        contentBox.addView(sectionTitle("QR Keyboard gaming 2"))
        contentBox.addView(versionInfoText())
        contentBox.addView(buildKeyboardStatusSection())
        contentBox.addView(spacer())
        contentBox.addView(sectionTitle("Ngôn ngữ"))
        contentBox.addView(buildLanguageSection())
        contentBox.addView(spacer())
        contentBox.addView(sectionTitle("Bố cục bàn phím"))
        contentBox.addView(buildLayoutSection())
        contentBox.addView(spacer())
        contentBox.addView(sectionTitle("Màu sắc"))
        contentBox.addView(buildColorSection())
        contentBox.addView(spacer())
        contentBox.addView(sectionTitle("Hiệu ứng đèn RGB chạy"))
        contentBox.addView(buildLedSection())
        contentBox.addView(spacer())
        // Khối xem trước đặt SAU CÙNG 2 mục Màu sắc/Hiệu ứng - luôn phản ánh đúng lựa chọn
        // MỚI NHẤT ở 2 mục phía trên (vì cả màn hình được build lại từ trên xuống dưới mỗi
        // lần đổi lựa chọn). Có 1 ô nhập "giả" tự động được focus ngay khi build xong để
        // BÀN PHÍM THẬT tự bật lên xem trước, không cần người dùng tự bấm mở.
        contentBox.addView(sectionTitle("Xem trước bàn phím"))
        contentBox.addView(buildKeyboardPreviewSection())
        contentBox.addView(spacer())
        contentBox.addView(sectionTitle("Rung khi gõ"))
        contentBox.addView(buildVibrationSection())
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

        val shadowRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 20, 0, 0)
        }
        shadowRow.addView(TextView(this).apply {
            text = "Bong bóng chữ khi gõ phím"
            setTextColor(Color.WHITE)
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        shadowRow.addView(Switch(this).apply {
            isChecked = KeyBubbleSettings.isEnabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, isChecked -> KeyBubbleSettings.setEnabled(this@SettingsActivity, isChecked) }
        })
        box.addView(shadowRow)
        box.addView(bodyText("Hiện bong bóng phóng to ký tự phía trên phím trong lúc đang nhấn giữ, tắt ngay khi nhả tay."))
        return box
    }

    // ============================== MÀU SẮC ==============================
    private fun buildColorSection(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(bodyText(
            "Chọn màu viền bàn phím và giao diện sáng/tối - áp dụng ngay cho bàn phím, " +
                "không cần khởi động lại."
        ))

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
                    rebuildAll(forceKeyboardRestart = true)
                }
            }
            row.addView(swatch)
        }
        val scroll = android.widget.HorizontalScrollView(this).apply {
            addView(row)
            setPadding(0, 12, 0, 12)
        }
        box.addView(scroll)

        // Giao diện sáng/tối - đổi toàn bộ nền khối bàn phím + màu nền từng phím.
        // Nút tự đổi màu NỀN của chính nó theo lựa chọn hiện tại (trắng khi đang ở chế độ
        // Sáng, tím than khi đang ở chế độ Tối) để người dùng thấy rõ hiệu ứng ngay tại đây,
        // không cần bấm xong mới biết đã đổi đúng ý chưa.
        val isDark = ThemeSettings.isDarkTheme(this)
        box.addView(pillSwitchButton(
            label = if (isDark) "🌙  Đang dùng nền Tối" else "☀️  Đang dùng nền Sáng",
            bgColor = if (isDark) Color.parseColor("#1A0F2E") else Color.WHITE,
            textColor = if (isDark) Color.WHITE else Color.parseColor("#1A0F2E")
        ) {
            ThemeSettings.setDarkTheme(this@SettingsActivity, !isDark)
            rebuildAll(forceKeyboardRestart = true)
        })
        return box
    }

    /** Nút bo tròn có viền nổi bật màu chủ đạo, bấm vào là chuyển sang trạng thái khác ngay
     *  (dùng cho công tắc sáng/tối - không cần icon check riêng vì nhãn đã tự nói rõ trạng thái,
     *  và nền/chữ của chính nút cũng đổi theo để phản ánh đúng lựa chọn Sáng/Tối hiện tại). */
    private fun pillSwitchButton(
        label: String,
        bgColor: Int = Color.parseColor("#1A0F2E"),
        textColor: Int = Color.WHITE,
        onClick: () -> Unit
    ): TextView = TextView(this).apply {
        text = label
        setTextColor(textColor)
        textSize = 15f
        gravity = Gravity.CENTER
        setPadding(24, 28, 24, 28)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.setMargins(0, 8, 0, 0) }
        background = GradientDrawable().apply {
            cornerRadius = 28f
            setColor(bgColor)
            setStroke(4, ThemeSettings.getAccentColor(this@SettingsActivity))
        }
        setOnClickListener { onClick() }
    }

    // ============================== HIỆU ỨNG ĐÈN RGB CHẠY ==============================

    private fun buildLedSection(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(bodyText(
            "Màu viền phím tự động \"chạy\" liên tục (giống bàn phím cơ gaming thật). " +
                "Mặc định tắt (tốn pin hơn màu tĩnh bình thường)."
        ))

        val enabled = LedEffectSettings.isEnabled(this)
        box.addView(checkToggleButton(
            if (enabled) "Đang BẬT hiệu ứng RGB chạy" else "Đang TẮT hiệu ứng RGB chạy",
            checked = enabled
        ) {
            LedEffectSettings.setEnabled(this@SettingsActivity, !enabled)
            rebuildAll(forceKeyboardRestart = true)
        })

        if (!enabled) return box // các tuỳ chọn bên dưới chỉ có ý nghĩa khi hiệu ứng đang bật

        // Nhiều màu (cầu vồng) hay 1 màu (đúng màu viền đang chọn ở mục Màu sắc).
        val colorMode = LedEffectSettings.getColorMode(this)
        val colorModeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 0)
        }
        colorModeRow.addView(chip("Nhiều màu", colorMode == LedEffectSettings.ColorMode.MULTI_COLOR) {
            LedEffectSettings.setColorMode(this, LedEffectSettings.ColorMode.MULTI_COLOR)
            rebuildAll(forceKeyboardRestart = true)
        })
        colorModeRow.addView(chip("1 màu (màu viền)", colorMode == LedEffectSettings.ColorMode.SINGLE_COLOR) {
            LedEffectSettings.setColorMode(this, LedEffectSettings.ColorMode.SINGLE_COLOR)
            rebuildAll(forceKeyboardRestart = true)
        })
        box.addView(colorModeRow)

        // Hướng chạy của hiệu ứng.
        val direction = LedEffectSettings.getDirection(this)
        val directionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 0)
        }
        directionRow.addView(chip("Trái -> Phải", direction == LedEffectSettings.Direction.LEFT_TO_RIGHT) {
            LedEffectSettings.setDirection(this, LedEffectSettings.Direction.LEFT_TO_RIGHT)
            rebuildAll(forceKeyboardRestart = true)
        })
        directionRow.addView(chip("Trên -> Dưới", direction == LedEffectSettings.Direction.TOP_TO_BOTTOM) {
            LedEffectSettings.setDirection(this, LedEffectSettings.Direction.TOP_TO_BOTTOM)
            rebuildAll(forceKeyboardRestart = true)
        })
        directionRow.addView(chip("Chéo góc", direction == LedEffectSettings.Direction.DIAGONAL) {
            LedEffectSettings.setDirection(this, LedEffectSettings.Direction.DIAGONAL)
            rebuildAll(forceKeyboardRestart = true)
        })
        box.addView(directionRow)

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

    /** Nút công tắc bật/tắt kiểu checkbox (✅/⬜ + nhãn), viền nổi bật màu chủ đạo khi đang bật -
     *  giống nút "Đang BẬT hiệu ứng RGB chạy" trong ảnh mẫu. */
    private fun checkToggleButton(label: String, checked: Boolean, onClick: () -> Unit): TextView = TextView(this).apply {
        text = (if (checked) "✅  " else "⬜  ") + label
        setTextColor(Color.WHITE)
        textSize = 15f
        gravity = Gravity.CENTER
        setPadding(24, 28, 24, 28)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.setMargins(0, 8, 0, 0) }
        background = GradientDrawable().apply {
            cornerRadius = 28f
            setColor(Color.parseColor("#1A0F2E"))
            setStroke(4, if (checked) ThemeSettings.getAccentColor(this@SettingsActivity) else Color.parseColor("#55FFFFFF"))
        }
        setOnClickListener { onClick() }
    }

    // ============================== XEM TRƯỚC BÀN PHÍM (Ô GÕ THỬ, TỰ ĐỘNG BẬT) ==============================

    /** Thay vì tự vẽ lại từng phím giả (phức tạp, dễ lệch so với bàn phím thật), cách ĐƠN GIẢN
     *  hơn hẳn: dựng 1 Ô NHẬP THẬT (EditText) ngay trong màn Cài đặt rồi tự động focus + bật
     *  bàn phím lên ngay khi build xong - nhờ vậy BÀN PHÍM THẬT (đúng y hệt màu/hiệu ứng đang
     *  áp dụng, không phải hàng giả lập) tự hiện ra để xem, không cần người dùng tự bấm vào ô.
     *  Giá trị gõ vào ô này KHÔNG được lưu/dùng vào việc gì - chỉ để xem, gõ/xoá thoải mái. */
    private fun buildKeyboardPreviewSection(): View {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            background = GradientDrawable().apply {
                cornerRadius = 24f
                setColor(ThemeSettings.keyboardBackgroundColor(this@SettingsActivity))
                setStroke(2, Color.parseColor("#33FFFFFF"))
            }
        }
        outer.addView(bodyText(
            "Bàn phím tự bật lên ngay bên dưới để bạn xem trực tiếp màu sắc/hiệu ứng vừa chọn - " +
                "gõ thử thoải mái, không lưu lại gì cả."
        ).apply { setTextColor(Color.LTGRAY) })

        val editText = EditText(this).apply {
            hint = "Ô xem trước - gõ thử rồi xoá"
            setHintTextColor(Color.parseColor("#88FFFFFF"))
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(24, 20, 24, 20)
            background = GradientDrawable().apply {
                cornerRadius = 16f
                setColor(ThemeSettings.keyBackgroundColor(this@SettingsActivity))
                setStroke(3, ThemeSettings.getAccentColor(this@SettingsActivity))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(0, 12, 0, 0) }
        }
        previewEditText = editText
        outer.addView(editText)

        outer.addView(actionButton("Chọn bàn phím QR Keyboard gaming 2 để xem trước") {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker() // chỉ có cách này để đổi bàn phím đang active - Android không cho app tự ép đổi ngầm
        })
        outer.addView(bodyText(
            "Nếu ô trên đang hiện bàn phím KHÁC (không phải QR Keyboard gaming 2), bấm nút trên " +
                "để chọn đúng bàn phím này - chỉ cần chọn 1 lần."
        ))

        // Tự động focus + ép bật bàn phím lên NGAY sau khi view được gắn vào màn hình - người
        // dùng không cần tự bấm vào ô mới thấy được bàn phím.
        // Nếu lần build này là do vừa đổi MÀU/HIỆU ỨNG (đã chủ động ẩn bàn phím CŨ ở rebuildAll()
        // phía trên) thì phải CHỜ 1 NHỊP (~250ms) mới bật lại - bật lại ngay lập tức trong cùng
        // 1 khung hình có thể khiến hệ thống gộp tắt+bật thành 1 thao tác đổi focus ngầm, bàn
        // phím thật không thực sự đóng-mở lại nên KHÔNG chạy lại refreshTheme() để lấy màu mới.
        val restartDelayMs = if (pendingPreviewKeyboardRestart) 250L else 0L
        pendingPreviewKeyboardRestart = false
        editText.postDelayed({
            editText.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(editText, InputMethodManager.SHOW_FORCED)
        }, restartDelayMs)
        return outer
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

    /** Hiện "Phiên bản x.x (mã yyy)" ngay dưới tiêu đề - để người dùng tự xác nhận
     *  app đã CẬP NHẬT đúng bản mới sau khi cài đè APK mới lên (không gỡ bản cũ). */
    private fun versionInfoText(): TextView = bodyText(
        "Phiên bản ${BuildConfig.VERSION_NAME} (mã ${BuildConfig.VERSION_CODE})"
    )

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
