@file:OptIn(androidx.camera.core.ExperimentalGetImage::class)

package com.viettype.smartkey

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

/**
 * Bàn phím tiếng Việt (Telex) + tiếng Anh, có thêm 2 tiện ích đặc trưng ngay
 * trên thanh công cụ: QUÉT MÃ QR/vạch (dùng CameraX + ML Kit, hiện ngay trong
 * khung bàn phím, không cần mở app riêng) và NHẬP LIỆU BẰNG GIỌNG NÓI (dùng
 * SpeechRecognizer trực tiếp).
 */
class SmartKeyboardService : InputMethodService(), LifecycleOwner {

    // LifecycleOwner tối giản tự cấp cho CameraX - CameraX cần 1 LifecycleOwner để tự biết
    // lúc nào phải giải phóng camera; Service không có sẵn cái này như Activity/Fragment nên
    // phải tự khai báo và điều khiển bằng tay theo đúng vòng đời camera đang mở/đóng.
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private enum class Page { LETTERS, SYMBOLS, SYMBOLS2, NUMPAD }
    private enum class CapsMode { OFF, SINGLE_SHIFT, CAPS_LOCK }

    private var currentPage = Page.LETTERS
    private var capsMode = CapsMode.OFF
    private var autoCapPending = true // true = ký tự tiếp theo sẽ tự viết hoa (đầu câu/đầu ô nhập)
    private var lastShiftTapAt = 0L

    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var rootContainer: FrameLayout
    private lateinit var keyboardBody: LinearLayout
    private lateinit var ledStripView: View
    private lateinit var utilityRowView: View
    private lateinit var rowsHost: LinearLayout
    private val letterKeyViews = mutableListOf<Pair<TextView, Char>>() // để đổi hoa/thường hàng loạt khi shift đổi

    /** 1 "khe" viền phím tham gia hiệu ứng RGB chạy: nền vẽ của phím + vị trí chuẩn
     *  hoá (0..1) của phím đó trong lưới, dùng để tính độ trễ pha khi hiệu ứng chạy qua. */
    private data class LedKeySlot(val drawable: GradientDrawable, val normX: Float, val normY: Float)
    private val ledKeySlots = mutableListOf<LedKeySlot>()

    private var ledAnimator: ValueAnimator? = null

    private var scanOverlay: View? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: androidx.camera.core.Camera? = null
    private var torchOn = false
    private var torchButtonView: TextView? = null
    private var lastQrHandledAt = 0L

    // Quét LIÊN TỤC: không tự đóng khung quét sau khi đọc được 1 mã, cho phép quét
    // nhiều mã kế tiếp nhau trong cùng 1 lượt mở camera. Theo dõi mã lặp lại để áp
    // "Giới hạn quét trùng lặp" - quét mã KHÁC thì đếm lại từ đầu (xem ScanHistoryStore).
    private var lastScannedContent: String? = null
    private var duplicateStreak = 0
    private var duplicateLimitToastShown = false

    private var speechRecognizer: SpeechRecognizer? = null
    private var micOverlay: View? = null
    private var micRecognizedText: String = ""

    // ============================== VÒNG ĐỜI ==============================

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onCreateInputView(): View {
        rootContainer = FrameLayout(this)

        keyboardBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ThemeSettings.keyboardBackgroundColor(this@SmartKeyboardService))
        }

        ledStripView = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(5))
        }
        keyboardBody.addView(ledStripView)

        utilityRowView = buildUtilityRow()
        keyboardBody.addView(utilityRowView)

        rowsHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        keyboardBody.addView(rowsHost)
        rebuildKeyRows()

        rootContainer.addView(keyboardBody, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ))

        return rootContainer
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        autoCapPending = true
        capsMode = CapsMode.OFF
        // Người dùng có thể vừa đổi màu viền/nền sáng-tối/hiệu ứng RGB ở màn Cài đặt rồi
        // quay lại gõ ngay - vẽ lại toàn bộ theo cấu hình mới nhất, không cần khởi động lại.
        refreshTheme()
        refreshLetterCaseDisplay()
        startLedAnimationIfNeeded()
    }

    /** Vẽ lại nền khối bàn phím + hàng tiện ích + toàn bộ phím theo màu viền/nền sáng-tối
     *  đang chọn trong Cài đặt (Màu sắc). Gọi mỗi lần bàn phím hiện lên để áp dụng ngay
     *  thay đổi vừa chọn mà không cần khởi động lại app/điện thoại. */
    private fun refreshTheme() {
        keyboardBody.setBackgroundColor(ThemeSettings.keyboardBackgroundColor(this))
        val newUtilityRow = buildUtilityRow()
        val utilityIndex = keyboardBody.indexOfChild(utilityRowView)
        if (utilityIndex >= 0) {
            keyboardBody.removeView(utilityRowView)
            keyboardBody.addView(newUtilityRow, utilityIndex)
        }
        utilityRowView = newUtilityRow
        rebuildKeyRows() // vẽ lại từng phím với màu nền/chữ theo theme mới
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        closeScanOverlay()
        closeMicOverlay()
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        ledAnimator?.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        speechRecognizer?.destroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ============================== HÀNG TIỆN ÍCH TRÊN CÙNG ==============================

    private fun buildUtilityRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        row.addView(utilityButton("🌐") { onGlobeKeyPressed() })
        row.addView(utilityButton("QR") { onScanButtonPressed() })
        row.addView(utilityButton("🎤") { onMicButtonPressed() })
        // Nút "?123"/"ABC" cũ ở đây bị TRÙNG chức năng với phím "SYM"/"ABC" đã có sẵn ngay
        // trong các hàng phím phía dưới, nên đổi hẳn thành phím tắt mở bàn phím SỐ kiểu máy
        // tính (trang riêng NUMPAD) cho nhanh, không phụ thuộc đang ở trang nào.
        row.addView(utilityButton("123") {
            currentPage = Page.NUMPAD
            rebuildKeyRows()
        })
        return row
    }

    private fun utilityButton(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(ThemeSettings.keyTextColor(this@SmartKeyboardService))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).also { it.setMargins(dp(3), 0, dp(3), 0) }
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(ThemeSettings.utilityButtonBackgroundColor(this@SmartKeyboardService))
            }
            setOnClickListener {
                VibrationSettings.tick(this@SmartKeyboardService)
                onClick()
            }
        }
    }

    // ============================== BỐ CỤC CÁC HÀNG PHÍM ==============================

    private val lettersRows = listOf(
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
        listOf("SHIFT", "z", "x", "c", "v", "b", "n", "m", "BACKSPACE"),
        listOf("SYM", "COMMA", "SPACE", "PERIOD", "ENTER"),
    )

    private val symbolsRows = listOf(
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
        listOf("@", "#", "đ", "_", "&", "-", "+", "(", ")"),
        listOf("*", "PAGE3", "\"", "'", ":", ";", "!", "?", "BACKSPACE"),
        listOf("ABC", "LT", "SPACE", "GT", "ENTER"),
    )

    /** Trang 3 - thêm các ký hiệu đặc biệt (toán học, tiền tệ, bản quyền...), mở từ phím
     *  "=\<" ở trang 2. 2 phím góc trái-dưới (?123 và ABC) dùng lại đúng mã phím "SYM"/"ABC"
     *  đã có sẵn nên bấm vào là quay thẳng về trang 2 / trang 1 tương ứng. */
    private val symbols2Rows = listOf(
        listOf("~", "`", "|", "•", "√", "π", "÷", "×", "¶", "Δ"),
        listOf("£", "€", "$", "¢", "^", "°", "=", "{", "}", "\\"),
        listOf("SYM", "%", "©", "®", "™", "%", "±", "[", "]", "BACKSPACE"),
        listOf("ABC", "LT", "SPACE", "GT", "ENTER"),
    )

    /** Hàng số 1-0 dùng cho tuỳ chọn "Luôn bật hàng phím số" ở trang gõ chữ. */
    private val numberRow = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")

    /** Ký tự có dấu phụ khi GIỮ LÂU (nhấn giữ) 1 phím chữ cái - dùng cho các ký tự
     *  không có sẵn trên bàn phím Telex thường (ürl, ç...) và số hay dùng kèm ký tự đặc biệt. */
    private val longPressVariants = mapOf(
        'a' to "àáảãạ", 'e' to "èéẻẽẹ", 'i' to "ìíỉĩị", 'o' to "òóỏõọ",
        'u' to "ùúủũụ", 'y' to "ỳýỷỹỵ", 's' to "$§", 'c' to "©ç",
    )

    private fun rebuildKeyRows() {
        rowsHost.removeAllViews()
        letterKeyViews.clear()
        ledKeySlots.clear() // phím cũ đã bị gỡ khỏi cây view - bỏ hết khe viền cũ, tránh vẽ vào phím đã mất
        if (currentPage == Page.NUMPAD) {
            // Trang số kiểu máy tính có phím Enter cao gấp đôi (chiếm 2 hàng dưới cùng) nên
            // không dùng chung được vòng lặp hàng-đều-cột như các trang khác - tự dựng riêng.
            rowsHost.addView(buildNumpadBody())
            refreshLetterCaseDisplay()
            return
        }
        val rows = mutableListOf<List<String>>()
        // "Luôn bật hàng phím số" - chỉ áp dụng cho trang gõ chữ đầu tiên (LETTERS),
        // trang SYMBOLS vốn đã có sẵn hàng số riêng ở trên cùng rồi.
        if (currentPage == Page.LETTERS && NumberRowSettings.isEnabled(this)) {
            rows.add(numberRow)
        }
        rows.addAll(when (currentPage) {
            Page.LETTERS -> lettersRows
            Page.SYMBOLS -> symbolsRows
            Page.SYMBOLS2 -> symbols2Rows
            Page.NUMPAD -> emptyList() // xử lý riêng ở nhánh return phía trên, không tới đây
        })
        val rowCount = rows.size
        for ((rowIndex, rowKeys) in rows.withIndex()) {
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
            }
            val colCount = rowKeys.size
            val normY = if (rowCount > 1) rowIndex / (rowCount - 1).toFloat() else 0f
            for ((colIndex, keyCode) in rowKeys.withIndex()) {
                val normX = if (colCount > 1) colIndex / (colCount - 1).toFloat() else 0f
                rowView.addView(buildKey(keyCode, normX = normX, normY = normY))
            }
            rowsHost.addView(rowView)
        }
        refreshLetterCaseDisplay()
    }

    /** Trang bàn phím số (123): cột số bên trái (1-9 + hàng toán tử) chiếm 3 phần bề rộng,
     *  cột phải 1 phần gồm Xoá / ABC / Enter - riêng Enter cao gấp đôi, chiếm luôn 2 hàng
     *  dưới cùng, giống bàn phím số máy tính trong ảnh mẫu. */
    private fun buildNumpadBody(): View {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48 * 4))
        }

        val numberColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 3f)
        }
        // Cột số có 4 hàng (3 hàng số + 1 hàng toán tử) - normY tính theo hàng trong tổng 4 hàng
        // để hiệu ứng RGB "Trên -> Dưới"/"Chéo góc" chạy mượt xuyên suốt cả trang bàn phím số.
        val numpadRowCount = 4
        val numberRowsKeys = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"))
        for ((rowIndex, rowKeys) in numberRowsKeys.withIndex()) {
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            val normY = rowIndex / (numpadRowCount - 1).toFloat()
            for ((colIndex, keyCode) in rowKeys.withIndex()) {
                val normX = colIndex / (rowKeys.size - 1).toFloat()
                rowView.addView(buildKey(keyCode, normX = normX, normY = normY))
            }
            numberColumn.addView(rowView)
        }
        // Hàng toán tử dưới cùng của cột số: "0" rộng gấp đôi các phím còn lại, giống ảnh mẫu.
        val operatorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val operatorNormY = 1f // hàng cuối cùng trong 4 hàng
        operatorRow.addView(buildKey("+", normX = 0f, normY = operatorNormY))
        operatorRow.addView(buildKey("-", normX = 0.25f, normY = operatorNormY))
        operatorRow.addView(buildKey("0", weightOverride = 2f, normX = 0.5f, normY = operatorNormY))
        operatorRow.addView(buildKey("×", normX = 0.75f, normY = operatorNormY))
        operatorRow.addView(buildKey("/", normX = 1f, normY = operatorNormY))
        numberColumn.addView(operatorRow)

        val rightColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }
        rightColumn.addView(asVerticalWeighted(buildKey("BACKSPACE", normX = 1f, normY = 0f), 1f))
        rightColumn.addView(asVerticalWeighted(buildKey("ABC", normX = 1f, normY = 0.5f), 1f))
        rightColumn.addView(asVerticalWeighted(buildKey("ENTER", normX = 1f, normY = 1f), 2f))

        body.addView(numberColumn)
        body.addView(rightColumn)
        return body
    }

    /** buildKey() vốn set LayoutParams theo kiểu "cột ngang trong 1 hàng" (width=0 co giãn,
     *  height=MATCH_PARENT) - phím nào cần XẾP DỌC (như cột Xoá/ABC/Enter ở trang số) phải đổi
     *  lại thành width=MATCH_PARENT, height=0 co giãn thì mới cao đúng tỉ lệ mong muốn. */
    private fun asVerticalWeighted(view: View, weight: Float): View {
        view.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, weight).also {
            it.setMargins(dp(2), dp(2), dp(2), dp(2))
        }
        return view
    }

    private fun buildKey(code: String, weightOverride: Float? = null, normX: Float = 0f, normY: Float = 0f): View {
        val weight = weightOverride ?: if (code == "SPACE") 4f else 1f
        val label = displayLabelFor(code)

        val keyBackground = GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setColor(ThemeSettings.keyBackgroundColor(this@SmartKeyboardService))
            // Viền bắt đầu trong suốt, độ dày 0 - hiệu ứng RGB chạy (nếu đang BẬT) sẽ tự
            // set màu + độ dày viền theo thời gian thực, xem startLedAnimationIfNeeded().
            setStroke(0, Color.TRANSPARENT)
        }
        ledKeySlots.add(LedKeySlot(keyBackground, normX, normY))

        val keyView = TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            // Enter/Shift/Backspace hiển thị bằng 1 icon chữ Unicode - trước đây tính cỡ
            // chữ theo ĐỘ DÀI MÃ PHÍM ("ENTER" dài 5 ký tự) nên bị xếp vào nhóm chữ nhỏ dù
            // NHÃN hiển thị chỉ có 1 ký tự icon, khiến icon trông rất bé. Giờ tính theo
            // đúng phím icon để phóng to hẳn cho dễ nhìn.
            textSize = when (code) {
                "ENTER", "SHIFT", "BACKSPACE" -> 22f
                else -> if (code.length == 1) 18f else 13f
            }
            setTextColor(ThemeSettings.keyTextColor(this@SmartKeyboardService))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight)
                .also { it.setMargins(dp(2), dp(2), dp(2), dp(2)) }
            background = keyBackground
        }

        if (code.length == 1 && code[0].isLetter() && currentPage != Page.SYMBOLS2) {
            // Trang 3 dùng vài ký tự Hy Lạp/toán học (π, Δ...) mà Kotlin cũng coi là "letter" -
            // không đưa vào letterKeyViews để tránh bị hoa/thường hoá nhầm theo trạng thái Shift.
            letterKeyViews.add(keyView to code[0])
        }
        if (code == "SHIFT") {
            keyView.tag = "shift_key"
        }

        attachKeyTouchHandling(keyView, code)
        return keyView
    }

    private fun displayLabelFor(code: String): String = when (code) {
        "SHIFT" -> if (capsMode == CapsMode.CAPS_LOCK) "⇪" else "⇧"
        "BACKSPACE" -> "⌫"
        "SPACE" -> LocaleSettings.getCurrentLocale(this).displayName
        "ENTER" -> "⏎"
        "COMMA" -> ","
        "PERIOD" -> "."
        "LT" -> "<"
        "GT" -> ">"
        "PAGE3" -> "=\\<"
        "SYM" -> "?123"
        "ABC" -> "ABC"
        else -> if (code.length == 1 && capsMode != CapsMode.OFF) code.uppercase() else code
    }

    /** Gắn xử lý chạm cho 1 phím: bấm nhanh -> gõ ngay; giữ lâu -> hiện popup ký tự phụ (nếu
     *  có) hoặc lặp lại liên tục (áp dụng cho Backspace, xoá nhanh khi giữ tay). */
    private fun attachKeyTouchHandling(keyView: TextView, code: String) {
        var repeatRunnable: Runnable? = null
        var longPressRunnable: Runnable? = null
        var longPressTriggered = false
        var popupView: LinearLayout? = null
        var popupChars: List<Char> = emptyList()
        var selectedVariantIndex = 0

        keyView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    longPressTriggered = false
                    v.alpha = 0.6f
                    if (code == "BACKSPACE") {
                        repeatRunnable = object : Runnable {
                            override fun run() {
                                handleBackspace()
                                mainHandler.postDelayed(this, 50)
                            }
                        }
                        mainHandler.postDelayed(repeatRunnable!!, 350)
                    } else if (code.length == 1 && longPressVariants.containsKey(code[0].lowercaseChar())) {
                        longPressRunnable = Runnable {
                            longPressTriggered = true
                            popupChars = longPressVariants.getValue(code[0].lowercaseChar()).toList()
                            selectedVariantIndex = 0
                            popupView = showAccentPopup(v, popupChars, 0)
                        }
                        mainHandler.postDelayed(longPressRunnable!!, 350)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (longPressTriggered && popupChars.isNotEmpty()) {
                        val idx = accentPopupIndexForTouchX(v, event.rawX, popupChars.size)
                        if (idx != selectedVariantIndex) {
                            selectedVariantIndex = idx
                            updateAccentPopupSelection(popupView, idx)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.alpha = 1f
                    repeatRunnable?.let { mainHandler.removeCallbacks(it) }
                    longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                    if (longPressTriggered) {
                        popupView?.let { rootContainer.removeView(it) }
                        val chosen = popupChars.getOrNull(selectedVariantIndex)
                        if (chosen != null) commitAccentVariant(chosen, code[0].isUpperCase())
                    } else {
                        onKeyTapped(code)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.alpha = 1f
                    repeatRunnable?.let { mainHandler.removeCallbacks(it) }
                    longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                    popupView?.let { rootContainer.removeView(it) }
                    true
                }
                else -> false
            }
        }
    }

    /** Hiện 1 hàng popup nhỏ NGAY PHÍA TRÊN phím đang giữ, liệt kê các ký tự phụ để chọn - vuốt
     *  ngón tay ngang qua popup (không nhấc tay lên) để đổi ký tự đang chọn, nhấc tay ra để chốt
     *  ký tự đang tô sáng. */
    private fun showAccentPopup(anchorKey: View, chars: List<Char>, selectedIndex: Int): LinearLayout {
        val location = IntArray(2)
        anchorKey.getLocationInWindow(location)
        val rootLocation = IntArray(2)
        rootContainer.getLocationInWindow(rootLocation)

        val popup = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(ThemeSettings.keyBackgroundColor(this@SmartKeyboardService))
            }
        }
        for ((index, ch) in chars.withIndex()) {
            popup.addView(TextView(this).apply {
                text = ch.toString()
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(ThemeSettings.keyTextColor(this@SmartKeyboardService))
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setBackgroundColor(if (index == selectedIndex) ThemeSettings.getAccentColor(this@SmartKeyboardService) else Color.TRANSPARENT)
            })
        }

        val params = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = location[0] - rootLocation[0]
            topMargin = location[1] - rootLocation[1] - dp(46)
        }
        rootContainer.addView(popup, params)
        return popup
    }

    private fun updateAccentPopupSelection(popup: LinearLayout?, selectedIndex: Int) {
        popup ?: return
        for (i in 0 until popup.childCount) {
            (popup.getChildAt(i) as? TextView)?.setBackgroundColor(
                if (i == selectedIndex) ThemeSettings.getAccentColor(this) else Color.TRANSPARENT
            )
        }
    }

    /** Từ vị trí ngón tay hiện tại (toạ độ tuyệt đối trên màn hình), tính xem đang ở gần cột thứ
     *  mấy của popup - CHO PHÉP vuốt ra ngoài popup vẫn tính (kẹp về đầu/cuối) để không bị "tuột"
     *  lựa chọn chỉ vì lệch tay vài pixel. */
    private fun accentPopupIndexForTouchX(anchorKey: View, touchRawX: Float, variantCount: Int): Int {
        val location = IntArray(2)
        anchorKey.getLocationInWindow(location)
        val relativeX = touchRawX - location[0]
        val columnWidth = (anchorKey.width.toFloat() * 1.6f) / variantCount // popup hơi rộng hơn phím gốc 1 chút
        val index = (relativeX / columnWidth).toInt()
        return index.coerceIn(0, variantCount - 1)
    }

    private fun commitAccentVariant(chosen: Char, wasUpperKey: Boolean) {
        val actual = if (capsMode != CapsMode.OFF || wasUpperKey) chosen.uppercaseChar() else chosen
        currentInputConnection?.commitText(actual.toString(), 1)
        afterCharacterCommitted(isLetter = true)
    }

    private fun onKeyTapped(code: String) {
        when (code) {
            "SHIFT" -> handleShiftTap()
            "BACKSPACE" -> handleBackspace()
            "SPACE" -> handleSpace()
            "ENTER" -> handleEnter()
            "COMMA" -> commitPunctuation(",")
            "PERIOD" -> commitPunctuation(".")
            "LT" -> commitPunctuation("<")
            "GT" -> commitPunctuation(">")
            "PAGE3" -> { currentPage = Page.SYMBOLS2; rebuildKeyRows() }
            "SYM" -> { currentPage = Page.SYMBOLS; rebuildKeyRows() }
            "ABC" -> { currentPage = Page.LETTERS; rebuildKeyRows() }
            else -> if (code.length == 1) handleLetterOrSymbolKey(code[0]) else Unit
        }
    }

    // ============================== XỬ LÝ GÕ CHỮ ==============================

    private fun getCurrentWordBuffer(): String {
        val ic = currentInputConnection ?: return ""
        val textBefore = ic.getTextBeforeCursor(30, 0)?.toString() ?: return ""
        var start = textBefore.length
        while (start > 0 && textBefore[start - 1].isLetter()) start--
        return textBefore.substring(start)
    }

    private fun handleLetterOrSymbolKey(rawChar: Char) {
        val ic = currentInputConnection ?: return
        val locale = LocaleSettings.getCurrentLocale(this)
        // Trang 3 (ký hiệu toán học/Hy Lạp) không áp dụng hoa/thường - π, Δ... phải gõ ra
        // đúng như hiển thị dù Shift/Caps Lock đang bật từ trang chữ trước đó.
        val isUpper = capsMode != CapsMode.OFF && currentPage != Page.SYMBOLS2
        val typedChar = if (rawChar.isLetter() && currentPage != Page.SYMBOLS2) {
            if (isUpper) rawChar.uppercaseChar() else rawChar
        } else rawChar // số/ký tự đặc biệt không có khái niệm hoa/thường

        if (rawChar.isLetter() && locale.usesTelex && currentPage != Page.SYMBOLS2) {
            val wordBefore = getCurrentWordBuffer()
            val transformed = TelexEngine.applyKey(wordBefore, typedChar)
            if (transformed != null) {
                ic.deleteSurroundingText(wordBefore.length, 0)
                ic.commitText(transformed, 1)
                afterCharacterCommitted(isLetter = true)
                return
            }
        }

        ic.commitText(typedChar.toString(), 1)
        afterCharacterCommitted(isLetter = rawChar.isLetter())
    }

    private fun commitPunctuation(symbol: String) {
        currentInputConnection?.commitText(symbol, 1)
        if (symbol == "." || symbol == "!" || symbol == "?") {
            autoCapPending = true
        }
        VibrationSettings.tick(this)
    }

    private fun afterCharacterCommitted(isLetter: Boolean) {
        if (capsMode == CapsMode.SINGLE_SHIFT) {
            capsMode = CapsMode.OFF
            refreshLetterCaseDisplay()
        }
        if (isLetter) autoCapPending = false
        VibrationSettings.tick(this)
    }

    private fun handleBackspace() {
        val ic = currentInputConnection ?: return
        ic.deleteSurroundingText(1, 0)
        VibrationSettings.tick(this)
    }

    private fun handleSpace() {
        currentInputConnection?.commitText(" ", 1)
        VibrationSettings.tick(this)
    }

    private fun handleEnter() {
        currentInputConnection?.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER))
        currentInputConnection?.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER))
        autoCapPending = true
        VibrationSettings.tick(this)
    }

    private fun handleShiftTap() {
        val now = System.currentTimeMillis()
        capsMode = when {
            capsMode == CapsMode.CAPS_LOCK -> CapsMode.OFF
            now - lastShiftTapAt < 300 -> CapsMode.CAPS_LOCK // 2 lần chạm nhanh -> khoá hoa
            capsMode == CapsMode.OFF -> CapsMode.SINGLE_SHIFT
            else -> CapsMode.OFF
        }
        lastShiftTapAt = now
        refreshLetterCaseDisplay()
    }

    private fun onGlobeKeyPressed() {
        LocaleSettings.switchToNextLocale(this)
        rebuildKeyRows()
    }

    private fun refreshLetterCaseDisplay() {
        if (autoCapPending && capsMode == CapsMode.OFF) capsMode = CapsMode.SINGLE_SHIFT
        val upper = capsMode != CapsMode.OFF
        for ((view, baseChar) in letterKeyViews) {
            view.text = if (upper) baseChar.uppercaseChar().toString() else baseChar.toString()
        }
        // Tìm đúng phím Shift qua TAG đã gắn ở buildKey() (không dựa vào VỊ TRÍ hàng cố định -
        // trang Symbols không có hàng nào chứa Shift, dựa theo vị trí dễ ghi đè nhầm lên phím
        // khác đang đứng ở đúng vị trí đó, vd phím "*" từng bị đè nhãn thành "⇧" trước khi sửa).
        rowsHost.findViewWithTag<TextView>("shift_key")?.text =
            if (capsMode == CapsMode.CAPS_LOCK) "⇪" else "⇧"
    }

    // ============================== HIỆU ỨNG VIỀN SÁNG ==============================

    /** Độ dày viền phím lúc hiệu ứng RGB đang chạy. */
    private val ledBorderWidthPx get() = dp(2)

    private fun startLedAnimationIfNeeded() {
        ledAnimator?.cancel()

        if (!LedEffectSettings.isEnabled(this)) {
            // Tắt hẳn - trả viền mọi phím + dải đèn trên cùng về trong suốt.
            ledStripView.setBackgroundColor(Color.TRANSPARENT)
            for (slot in ledKeySlots) slot.drawable.setStroke(0, Color.TRANSPARENT)
            return
        }

        val colorMode = LedEffectSettings.getColorMode(this)
        val direction = LedEffectSettings.getDirection(this)
        val duration = LedEffectSettings.cycleDurationMs(this)
        val singleBaseColor = ThemeSettings.getAccentColor(this)
        val singleHsv = FloatArray(3).also { Color.colorToHSV(singleBaseColor, it) }

        ledAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                val globalT = animator.animatedValue as Float

                // Dải đèn mỏng trên cùng - hiển thị đúng màu đang "chạy" tới ngay lúc này (pha 0).
                ledStripView.setBackgroundColor(colorAtPhase(globalT, colorMode, singleHsv))

                // Viền từng phím "chạy" theo đúng hướng đã chọn - mỗi phím trễ pha theo vị trí
                // của nó trong lưới (trái->phải dùng normX, trên->dưới dùng normY, chéo góc dùng
                // trung bình cả 2) nên màu lan dần qua bàn phím giống đèn LED chạy thật.
                for (slot in ledKeySlots) {
                    val posAlong = when (direction) {
                        LedEffectSettings.Direction.LEFT_TO_RIGHT -> slot.normX
                        LedEffectSettings.Direction.TOP_TO_BOTTOM -> slot.normY
                        LedEffectSettings.Direction.DIAGONAL -> (slot.normX + slot.normY) / 2f
                    }
                    val phase = ((globalT + posAlong) % 1f + 1f) % 1f
                    slot.drawable.setStroke(ledBorderWidthPx, colorAtPhase(phase, colorMode, singleHsv))
                }
            }
            start()
        }
    }

    /** Màu tại 1 pha (0..1) của hiệu ứng - "Nhiều màu" quét cầu vồng đủ 360 độ hue; "1 màu" giữ
     *  nguyên màu viền đang chọn, chỉ nhấp nháy độ sáng theo dạng sóng để tạo cảm giác đang "chạy". */
    private fun colorAtPhase(phase: Float, colorMode: LedEffectSettings.ColorMode, singleHsv: FloatArray): Int {
        return when (colorMode) {
            LedEffectSettings.ColorMode.MULTI_COLOR -> Color.HSVToColor(floatArrayOf(phase * 360f, 0.85f, 1f))
            LedEffectSettings.ColorMode.SINGLE_COLOR -> {
                val wave = ((kotlin.math.cos(phase * 2 * Math.PI) + 1) / 2).toFloat() // 0..1, đỉnh sáng nhất tại phase=0
                val hsv = floatArrayOf(singleHsv[0], singleHsv[1], 0.3f + 0.7f * wave)
                Color.HSVToColor(hsv)
            }
        }
    }

    // ============================== QUÉT MÃ QR / VẠCH ==============================

    private fun onScanButtonPressed() {
        if (!ScanHistoryStore.canScanMore(this)) {
            showToast("Đã đạt giới hạn quét hôm nay.")
            return
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startActivity(Intent(this, CameraPermissionRelay::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        showScanOverlay()
    }

    private fun showScanOverlay() {
        if (scanOverlay != null) return
        keyboardBody.visibility = View.GONE

        val previewView = PreviewView(this)
        val overlay = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(280))
            setBackgroundColor(Color.BLACK)
            addView(previewView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(TextView(this@SmartKeyboardService).apply {
                text = "🔦"
                setTextColor(Color.WHITE)
                textSize = 15f
                setPadding(dp(16), dp(8), dp(16), dp(8))
                background = GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat()
                    setColor(Color.parseColor("#88000000"))
                }
                setOnClickListener { toggleTorch() }
                torchButtonView = this
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).also {
                it.gravity = Gravity.TOP or Gravity.START
                it.setMargins(dp(8), dp(8), 0, 0)
            })
            addView(TextView(this@SmartKeyboardService).apply {
                text = "Huỷ"
                setTextColor(Color.WHITE)
                textSize = 15f
                setPadding(dp(16), dp(8), dp(16), dp(8))
                background = GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat()
                    setColor(Color.parseColor("#88000000"))
                }
                setOnClickListener { closeScanOverlay() }
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).also {
                it.gravity = Gravity.TOP or Gravity.END
                it.setMargins(0, dp(8), dp(8), 0)
            })
        }
        scanOverlay = overlay
        rootContainer.addView(overlay)

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider
                val preview = androidx.camera.core.Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                val scanner = BarcodeScanning.getClient()
                analysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
                    analyzeFrameForBarcode(imageProxy, scanner)
                }
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                showToast("Không mở được camera: ${e.message}")
                closeScanOverlay()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Bật/tắt đèn flash của camera sau trong lúc đang quét mã. */
    private fun toggleTorch() {
        val cam = camera ?: return
        if (cam.cameraInfo.hasFlashUnit() != true) {
            showToast("Thiết bị không có đèn flash.")
            return
        }
        torchOn = !torchOn
        cam.cameraControl.enableTorch(torchOn)
        torchButtonView?.text = if (torchOn) "💡" else "🔦"
        torchButtonView?.background = GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setColor(if (torchOn) ThemeSettings.getAccentColor(this@SmartKeyboardService) else Color.parseColor("#88000000"))
        }
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun analyzeFrameForBarcode(imageProxy: ImageProxy, scanner: com.google.mlkit.vision.barcode.BarcodeScanner) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes -> handleBarcodeResults(barcodes) }
            .addOnCompleteListener { imageProxy.close() }
    }

    private fun handleBarcodeResults(barcodes: List<Barcode>) {
        val now = System.currentTimeMillis()
        if (now - lastQrHandledAt < 1500) return // chống đọc trùng nhiều khung hình liên tiếp của CÙNG 1 lượt giữ mã trước camera
        val content = barcodes.firstOrNull()?.rawValue ?: return
        lastQrHandledAt = now

        if (content == lastScannedContent) {
            duplicateStreak++
        } else {
            // Mã KHÁC với lần trước -> đếm lại từ đầu.
            lastScannedContent = content
            duplicateStreak = 1
            duplicateLimitToastShown = false
        }

        val duplicateLimit = ScanHistoryStore.getDuplicateLimit(this)
        if (duplicateStreak > duplicateLimit) {
            // Đã đạt giới hạn lặp cho ĐÚNG mã này - ngừng xuất thêm, chỉ báo 1 lần
            // (không báo liên tục mỗi khung hình) cho tới khi người dùng đưa mã KHÁC vào.
            if (!duplicateLimitToastShown) {
                showToast("Đã đạt giới hạn quét lặp ($duplicateLimit lần) cho mã này. Quét mã khác để tiếp tục.")
                duplicateLimitToastShown = true
            }
            return
        }

        if (!ScanHistoryStore.canScanMore(this)) {
            showToast("Đã đạt giới hạn quét hôm nay.")
            closeScanOverlay()
            return
        }

        currentInputConnection?.commitText(content, 1)
        ScanHistoryStore.addEntry(this, content)
        VibrationSettings.tick(this)
        // KHÔNG đóng khung quét ở đây - quét liên tục, người dùng tự bấm "Huỷ" khi xong.
    }

    private fun closeScanOverlay() {
        if (torchOn) {
            try {
                camera?.cameraControl?.enableTorch(false)
            } catch (ignored: Exception) {
            }
        }
        torchOn = false
        torchButtonView = null
        camera = null
        scanOverlay?.let { rootContainer.removeView(it) }
        scanOverlay = null
        try {
            cameraProvider?.unbindAll()
        } catch (ignored: Exception) {
        }
        keyboardBody.visibility = View.VISIBLE
        lastScannedContent = null
        duplicateStreak = 0
        duplicateLimitToastShown = false
    }

    // ============================== NHẬP LIỆU BẰNG GIỌNG NÓI ==============================

    private fun onMicButtonPressed() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startActivity(Intent(this, MicPermissionRelay::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showToast("Máy không hỗ trợ nhận dạng giọng nói.")
            return
        }
        showMicOverlayAndListen()
    }

    private fun showMicOverlayAndListen() {
        if (micOverlay != null) return
        keyboardBody.visibility = View.GONE
        micRecognizedText = ""

        val statusText = TextView(this).apply {
            text = "Đang nghe..."
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
        }
        val overlay = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(140))
            setBackgroundColor(Color.parseColor("#1A0F2E"))
            addView(statusText, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).also {
                it.gravity = Gravity.CENTER
            })
            addView(LinearLayout(this@SmartKeyboardService).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(TextView(this@SmartKeyboardService).apply {
                    text = "Huỷ"
                    setTextColor(Color.WHITE)
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(6).toFloat()
                        setColor(Color.parseColor("#552A1F4A"))
                    }
                    setOnClickListener { closeMicOverlay() }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                addView(TextView(this@SmartKeyboardService).apply {
                    text = "Gửi"
                    setTextColor(Color.WHITE)
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(6).toFloat()
                        setColor(Color.parseColor("#552A1F4A"))
                    }
                    setOnClickListener { sendMicTextAndClose() }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
                    it.marginStart = dp(10)
                })
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).also {
                it.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                it.setMargins(0, 0, 0, dp(12))
            })
        }
        micOverlay = overlay
        rootContainer.addView(overlay)

        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (LocaleSettings.getCurrentLocale(this@SmartKeyboardService).usesTelex) "vi-VN" else "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: android.os.Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!text.isNullOrBlank()) micRecognizedText = text
                    // Không tự gửi & đóng ở đây nữa - người dùng chủ động bấm "Gửi" khi
                    // đã ưng ý với nội dung đang nhận dạng (xem sendMicTextAndClose()).
                }
                override fun onError(error: Int) {
                    closeMicOverlay()
                }
                override fun onPartialResults(partialResults: android.os.Bundle?) {
                    val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!text.isNullOrBlank()) {
                        statusText.text = text
                        micRecognizedText = text
                    }
                }
                override fun onReadyForSpeech(params: android.os.Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            })
            startListening(recognizerIntent)
        }
    }

    /** Gửi nội dung đang nhận dạng được (dù là kết quả tạm hay đã chốt) vào ô nhập, rồi đóng
     *  khung ghi âm - thay cho nút "Dừng" cũ vốn chỉ đóng khung mà không gửi gì cả. */
    private fun sendMicTextAndClose() {
        val text = micRecognizedText
        if (text.isNotBlank()) {
            currentInputConnection?.commitText("$text ", 1)
        }
        closeMicOverlay()
    }

    private fun closeMicOverlay() {
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        micOverlay?.let { rootContainer.removeView(it) }
        micOverlay = null
        micRecognizedText = ""
        keyboardBody.visibility = View.VISIBLE
    }

    private fun showToast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }
}
