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

    private enum class Page { LETTERS, SYMBOLS }
    private enum class CapsMode { OFF, SINGLE_SHIFT, CAPS_LOCK }

    private var currentPage = Page.LETTERS
    private var capsMode = CapsMode.OFF
    private var autoCapPending = true // true = ký tự tiếp theo sẽ tự viết hoa (đầu câu/đầu ô nhập)
    private var lastShiftTapAt = 0L

    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var rootContainer: FrameLayout
    private lateinit var keyboardBody: LinearLayout
    private lateinit var ledStripView: View
    private lateinit var rowsHost: LinearLayout
    private val letterKeyViews = mutableListOf<Pair<TextView, Char>>() // để đổi hoa/thường hàng loạt khi shift đổi

    private var ledAnimator: ValueAnimator? = null

    private var scanOverlay: View? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var lastQrHandledAt = 0L

    // Quét LIÊN TỤC: không tự đóng khung quét sau khi đọc được 1 mã, cho phép quét
    // nhiều mã kế tiếp nhau trong cùng 1 lượt mở camera. Theo dõi mã lặp lại để áp
    // "Giới hạn quét trùng lặp" - quét mã KHÁC thì đếm lại từ đầu (xem ScanHistoryStore).
    private var lastScannedContent: String? = null
    private var duplicateStreak = 0
    private var duplicateLimitToastShown = false

    private var speechRecognizer: SpeechRecognizer? = null
    private var micOverlay: View? = null

    // ============================== VÒNG ĐỜI ==============================

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onCreateInputView(): View {
        rootContainer = FrameLayout(this)

        keyboardBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1A0F2E"))
        }

        ledStripView = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(5))
        }
        keyboardBody.addView(ledStripView)

        keyboardBody.addView(buildUtilityRow())

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
        refreshLetterCaseDisplay()
        startLedAnimationIfNeeded()
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
        row.addView(utilityButton(if (currentPage == Page.LETTERS) "?123" else "ABC") {
            currentPage = if (currentPage == Page.LETTERS) Page.SYMBOLS else Page.LETTERS
            rebuildKeyRows()
        }.also { it.tag = "page_toggle" })
        return row
    }

    private fun utilityButton(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).also { it.setMargins(dp(3), 0, dp(3), 0) }
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(Color.parseColor("#332A1F4A"))
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
        listOf("@", "#", "$", "%", "&", "-", "+", "(", ")"),
        listOf("*", "\"", "'", ":", ";", "!", "?", "BACKSPACE"),
        listOf("ABC", "COMMA", "SPACE", "PERIOD", "ENTER"),
    )

    /** Ký tự có dấu phụ khi GIỮ LÂU (nhấn giữ) 1 phím chữ cái - dùng cho các ký tự
     *  không có sẵn trên bàn phím Telex thường (ürl, ç...) và số hay dùng kèm ký tự đặc biệt. */
    private val longPressVariants = mapOf(
        'a' to "àáảãạ", 'e' to "èéẻẽẹ", 'i' to "ìíỉĩị", 'o' to "òóỏõọ",
        'u' to "ùúủũụ", 'y' to "ỳýỷỹỵ", 's' to "$§", 'c' to "©ç",
    )

    private fun rebuildKeyRows() {
        rowsHost.removeAllViews()
        val rows = if (currentPage == Page.LETTERS) lettersRows else symbolsRows
        letterKeyViews.clear()
        for (rowKeys in rows) {
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
            }
            for (keyCode in rowKeys) {
                rowView.addView(buildKey(keyCode))
            }
            rowsHost.addView(rowView)
        }
        // Cập nhật lại nhãn nút chuyển trang (?123 <-> ABC) trên hàng tiện ích.
        (keyboardBody.getChildAt(1) as? LinearLayout)?.findViewWithTag<TextView>("page_toggle")?.text =
            if (currentPage == Page.LETTERS) "?123" else "ABC"
        refreshLetterCaseDisplay()
    }

    private fun buildKey(code: String): View {
        val weight = if (code == "SPACE") 4f else 1f
        val label = displayLabelFor(code)

        val keyView = TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = if (code.length == 1) 18f else 13f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight)
                .also { it.setMargins(dp(2), dp(2), dp(2), dp(2)) }
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(Color.parseColor("#2A1F4A"))
            }
        }

        if (code.length == 1 && code[0].isLetter()) {
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
                setColor(Color.parseColor("#2A1F4A"))
            }
        }
        for ((index, ch) in chars.withIndex()) {
            popup.addView(TextView(this).apply {
                text = ch.toString()
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
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
        val isUpper = capsMode != CapsMode.OFF
        val typedChar = if (rawChar.isLetter()) {
            if (isUpper) rawChar.uppercaseChar() else rawChar
        } else rawChar // số/ký tự đặc biệt không có khái niệm hoa/thường

        if (rawChar.isLetter() && locale.usesTelex) {
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

    private fun startLedAnimationIfNeeded() {
        ledAnimator?.cancel()
        val mode = LedEffectSettings.getMode(this)
        if (mode == LedEffectSettings.Mode.OFF) {
            ledStripView.setBackgroundColor(Color.TRANSPARENT)
            return
        }
        if (mode == LedEffectSettings.Mode.STATIC_COLOR) {
            ledStripView.setBackgroundColor(ThemeSettings.getAccentColor(this))
            return
        }

        val duration = LedEffectSettings.cycleDurationMs(this)
        val reversed = LedEffectSettings.getDirection(this) == LedEffectSettings.Direction.RIGHT_TO_LEFT
        ledAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                var t = animator.animatedValue as Float
                if (reversed) t = 1f - t
                val color = when (mode) {
                    LedEffectSettings.Mode.RAINBOW_CYCLE -> Color.HSVToColor(floatArrayOf(t * 360f, 0.85f, 1f))
                    LedEffectSettings.Mode.BREATHING -> {
                        val base = ThemeSettings.getAccentColor(this@SmartKeyboardService)
                        val brightness = 0.35f + 0.65f * (0.5f - 0.5f * kotlin.math.cos(t * 2 * Math.PI)).toFloat()
                        val hsv = FloatArray(3)
                        Color.colorToHSV(base, hsv)
                        hsv[2] = brightness
                        Color.HSVToColor(hsv)
                    }
                    else -> Color.TRANSPARENT
                }
                ledStripView.setBackgroundColor(color)
            }
            start()
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
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                showToast("Không mở được camera: ${e.message}")
                closeScanOverlay()
            }
        }, ContextCompat.getMainExecutor(this))
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
            addView(TextView(this@SmartKeyboardService).apply {
                text = "Dừng"
                setTextColor(Color.WHITE)
                setPadding(dp(16), dp(8), dp(16), dp(8))
                background = GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat()
                    setColor(Color.parseColor("#552A1F4A"))
                }
                setOnClickListener { closeMicOverlay() }
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
                    if (!text.isNullOrBlank()) {
                        currentInputConnection?.commitText("$text ", 1)
                    }
                    closeMicOverlay()
                }
                override fun onError(error: Int) {
                    closeMicOverlay()
                }
                override fun onPartialResults(partialResults: android.os.Bundle?) {
                    val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!text.isNullOrBlank()) statusText.text = text
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

    private fun closeMicOverlay() {
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        micOverlay?.let { rootContainer.removeView(it) }
        micOverlay = null
        keyboardBody.visibility = View.VISIBLE
    }

    private fun showToast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }
}
