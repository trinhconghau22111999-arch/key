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
import android.view.ViewGroup
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
    // TOI UU (nguoi dung phan anh: "go lau lau bat thinh linh no lai khong an: co cham nhung
    // no khong nhan, khong rung luon"): truoc day refreshLetterCaseDisplay() - goi sau MOI KY
    // TU go duoc (afterCharacterCommitted()) - luon lam 2 viec TON KEM du hoa/thuong KHONG DOI:
    // (1) rowsHost.findViewWithTag("shift_key") DUYET DE QUY toan bo cay view MOI LAN GO; (2)
    // gan lai .text cho CA ~26 phim chu (moi lan tao MOI 1 String qua .toString()) DU trang thai
    // Hoa/thuong KHONG HE thay doi so voi lan truoc. Hang tram/nghin lan nhu vay trong 1 phien go
    // gay ap luc GC dinh ky tren luong chinh - dung luc do neu ACTION_DOWN cua 1 nhip cham tiep
    // theo roi vao dung khoanh khac GC dang chay, touch co the bi tre/mat nhip cam nhan, giong
    // trieu chung nguoi dung mo ta. Cache san View phim Shift (khoi phai tim lai) + chi thuc su
    // cap nhat lai chu/icon khi trang thai Hoa/thuong THAT SU DOI KHAC lan truoc.
    private var shiftKeyView: TextView? = null
    private var lastAppliedCapsUpper: Boolean? = null
    private var lastAppliedCapsLock: Boolean? = null

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

        rowsHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // QUAN TRỌNG VỀ THỨ TỰ: phải gọi rebuildKeyRows() (clear + dựng lại ledKeySlots cho
        // các hàng chữ/số) TRƯỚC khi dựng utilityRowView, rồi mới add cả 2 vào cây view. Nếu
        // buildUtilityRow() chạy trước như bản cũ, 4 khe viền LED của hàng tiện ích (🌐/QR/🎤/
        // 123) đăng ký trong đó sẽ bị ledKeySlots.clear() trong rebuildKeyRows() xoá mất ngay
        // lập tức - xem giải thích đầy đủ hơn ở refreshTheme() (lỗi y hệt).
        rebuildKeyRows()
        utilityRowView = buildUtilityRow()
        keyboardBody.addView(utilityRowView)
        keyboardBody.addView(rowsHost)

        rootContainer.addView(keyboardBody, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ))

        return rootContainer
    }

    /** Bấm nút Back của hệ thống (hàng phím đa nhiệm back/home/đa nhiệm dưới màn hình) trong
     *  lúc đang mở khung quét QR: coi như "Huỷ" - đóng camera + khung quét, ĐỒNG THỜI tắt
     *  hẳn bàn phím luôn (không chỉ đóng khung quét mà bàn phím vẫn còn mở). Lần sau người
     *  dùng mở bàn phím lại thì tự về Trang 1 như bình thường (đã có sẵn ở onStartInputView -
     *  currentPage luôn reset về Page.LETTERS mỗi lần mở lại). Chỉ can thiệp khi đang quét;
     *  Back bình thường (không quét) vẫn để hệ thống xử lý như cũ. */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK && scanOverlay != null) {
            closeScanOverlay()
            requestHideSelf(0)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        autoCapPending = true
        capsMode = CapsMode.OFF
        // Ô nhập mã PIN/số điện thoại/số (inputType lớp CLASS_NUMBER, CLASS_PHONE,
        // hoặc CLASS_DATETIME - ví dụ ô nhập mã PIN khoá màn hình, mã OTP, SĐT...) thì tự
        // mở thẳng Trang bàn phím số (NUMPAD) luôn, đỡ phải tự bấm nút "123" mỗi lần.
        // Các ô nhập bình thường khác vẫn về Trang chữ (LETTERS) như cũ.
        currentPage = if (isNumericInputField(info)) Page.NUMPAD else Page.LETTERS
        // Người dùng có thể vừa đổi màu viền/nền sáng-tối/hiệu ứng RGB ở màn Cài đặt rồi
        // quay lại gõ ngay - vẽ lại toàn bộ theo cấu hình mới nhất, không cần khởi động lại.
        refreshTheme()
        refreshLetterCaseDisplay()
        startLedAnimationIfNeeded()
    }

    /** Ô nhập có phải kiểu chỉ nhận số không (mã PIN, mã OTP, số điện thoại, ngày giờ...)? */
    private fun isNumericInputField(info: android.view.inputmethod.EditorInfo?): Boolean {
        val inputType = info?.inputType ?: return false
        val classType = inputType and android.text.InputType.TYPE_MASK_CLASS
        return classType == android.text.InputType.TYPE_CLASS_NUMBER ||
            classType == android.text.InputType.TYPE_CLASS_PHONE ||
            classType == android.text.InputType.TYPE_CLASS_DATETIME
    }

    /** Vẽ lại nền khối bàn phím + hàng tiện ích + toàn bộ phím theo màu viền/nền sáng-tối
     *  đang chọn trong Cài đặt (Màu sắc). Gọi mỗi lần bàn phím hiện lên để áp dụng ngay
     *  thay đổi vừa chọn mà không cần khởi động lại app/điện thoại. */
    private fun refreshTheme() {
        keyboardBody.setBackgroundColor(ThemeSettings.keyboardBackgroundColor(this))
        // QUAN TRỌNG VỀ THỨ TỰ: rebuildKeyRows() CHẠY TRƯỚC vì nó ledKeySlots.clear() ngay dòng
        // đầu tiên (xoá sạch để dựng lại từ đầu) - nếu buildUtilityRow() chạy trước như cũ, 4
        // khe viền LED vừa đăng ký cho hàng tiện ích (🌐/QR/🎤/123) sẽ BỊ XOÁ MẤT NGAY SAU ĐÓ bởi
        // lệnh clear() này, khiến hàng tiện ích lại mất viền y hệt lỗi cũ dù code thêm ở
        // utilityButton() đã đúng - đây là lỗi thứ tự gọi hàm, không phải lỗi ở chỗ đăng ký.
        rebuildKeyRows() // vẽ lại từng phím với màu nền/chữ theo theme mới
        val newUtilityRow = buildUtilityRow()
        val utilityIndex = keyboardBody.indexOfChild(utilityRowView)
        if (utilityIndex >= 0) {
            keyboardBody.removeView(utilityRowView)
            keyboardBody.addView(newUtilityRow, utilityIndex)
        }
        utilityRowView = newUtilityRow
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        // KHÔNG tự đóng khung quét QR ở đây nữa: onFinishInputView() còn bị gọi cả những lúc
        // con trỏ chỉ RỜI Ô NHẬP TRONG CHỐC LÁT (ví dụ quét mã xong bấm Enter) chứ không hẳn là
        // người dùng muốn thoát quét - trước đây khiến khung quét tự ẩn ngoài ý muốn. Giờ khung
        // quét QR đứng yên cho tới khi người dùng tự bấm "Huỷ" (xem closeScanOverlay()).
        closeMicOverlay()
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        ledAnimator?.cancel()
        ledIdleHandler.removeCallbacksAndMessages(null)
        mainHandler.removeCallbacksAndMessages(null)
        speechRecognizer?.destroy()
        // Bàn phím có thể bị hệ thống huỷ hẳn (onDestroy) trong lúc khung quét QR vẫn đang mở
        // (giờ không còn tự đóng theo onFinishInputView nữa) - phải tự giải phóng camera ở đây,
        // nếu không sẽ rò rỉ camera/đèn flash vẫn bật ngầm dù bàn phím đã biến mất.
        if (scanOverlay != null) closeScanOverlay()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Không phải lúc nào hệ thống cũng tự gọi lại onCreateInputView() khi xoay màn hình -
        // nếu bàn phím đang mở sẵn mà không dựng lại, chiều cao hàng phím (vốn phụ thuộc
        // isLandscape() ở dp/keyRowHeightDp() bên dưới) sẽ vẫn giữ nguyên kích thước cũ của
        // hướng trước đó. Chủ động dựng lại toàn bộ view ngay khi orientation đổi để bàn
        // phím thu nhỏ lại đúng lúc vừa xoay ngang, không phải đợi đóng-mở lại bàn phím.
        if (::rootContainer.isInitialized) {
            // onCreateInputView() bên dưới dựng HẲN 1 rootContainer mới - khung quét QR/ghi âm
            // đang mở (nếu có) đang là view con của rootContainer CŨ nên sẽ bị "mồ côi": không
            // ai còn nhìn thấy nữa nhưng camera/mic thật vẫn chạy ngầm phía sau (không có cách
            // nào bấm "Huỷ" vì nút đó cũng đã mất theo). Đóng hẳn 2 khung này TRƯỚC khi dựng lại
            // UI để giải phóng camera/mic đúng lúc, người dùng tự mở quét/ghi âm lại sau khi
            // xoay xong nếu cần.
            if (scanOverlay != null) closeScanOverlay()
            if (micOverlay != null) closeMicOverlay()
            setInputView(onCreateInputView())
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** Các phím chức năng/điều khiển KHÔNG hiện "bong bóng chữ" khi nhấn (Shift, Backspace,
     *  Space, Enter, chuyển trang ?123/ABC...) vì phóng to ký tự cho các phím này không có
     *  ý nghĩa hoặc nhãn quá dài để hiện gọn trong bong bóng. */
    private val keyBubbleExcludedCodes = setOf("SHIFT", "BACKSPACE", "SPACE", "ENTER", "SYM", "ABC", "PAGE3")

    // TOI UU (nguoi dung phan anh: "luc nhanh luc cham"): View bong bong chu
    // DUNG CHUNG, tao 1 LAN DUY NHAT roi TAI SU DUNG cho moi lan cham phim
    // thay vi truoc day moi lan cham phim lai TAO MOI hoan toan 1 TextView +
    // 1 GradientDrawable (2 doi tuong MOI moi lan, hang chuc/hang tram lan
    // trong 1 phien go phim) - gop phan gay ap luc GC lien tuc tren luong
    // chinh, cung 1 nguyen nhan voi hieu ung LED (xem [ledColorHsvScratch]).
    private var sharedKeyBubbleView: TextView? = null

    /** Hiện bong bóng phóng to ký tự đang được nhấn, nổi ngay phía trên phím - kiểu hiệu ứng
     *  "key preview" quen thuộc của hầu hết bàn phím ảo, giúp người dùng thấy rõ mình vừa
     *  chạm đúng phím nào trước khi nhả tay. */
    private fun showKeyBubble(anchorKey: View, label: String): View {
        val location = IntArray(2)
        anchorKey.getLocationInWindow(location)
        val rootLocation = IntArray(2)
        rootContainer.getLocationInWindow(rootLocation)

        // TOI UU: lay lai View CU (neu con) thay vi tao moi - chi can cap
        // nhat lai text/mau/kich thuoc cho khop phim dang cham lan nay. Neu
        // View cu dang con gan o 1 container khac (hiem, do 1 nhip cham cu
        // chua kip go het), go no ra truoc.
        val bubble = sharedKeyBubbleView ?: TextView(this).apply {
            textSize = 26f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat() }
            setPadding(dp(14), dp(10), dp(14), dp(10))
            sharedKeyBubbleView = this
        }
        (bubble.parent as? ViewGroup)?.removeView(bubble)
        bubble.text = label
        bubble.setTextColor(ThemeSettings.keyTextColor(this@SmartKeyboardService))
        (bubble.background as GradientDrawable).setColor(ThemeSettings.keyBackgroundColor(this@SmartKeyboardService))
        bubble.minWidth = anchorKey.width

        // Đo trước kích thước THẬT của bong bóng (WRAP_CONTENT, có thể rộng hơn hẳn phím do
        // padding 2 bên) rồi mới tính lề trái để CĂN GIỮA chính xác theo tâm phím - trước đây
        // trừ cứng 1/4 bề rộng phím nên bong bóng luôn bị lệch trái, lệch càng rõ với phím có
        // nhãn dài (bong bóng rộng ra nhưng lề trái không tính lại theo bề rộng mới đó).
        val widthSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        bubble.measure(widthSpec, heightSpec)
        val bubbleWidth = bubble.measuredWidth

        val params = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = location[0] - rootLocation[0] + (anchorKey.width - bubbleWidth) / 2
            topMargin = location[1] - rootLocation[1] - dp(56)
        }
        rootContainer.addView(bubble, params)
        return bubble
    }

    private fun removeKeyBubble(bubble: View?) {
        bubble?.let { rootContainer.removeView(it) }
    }

    /** Màn hình xoay ngang có chiều cao khả dụng thấp hơn hẳn lúc đứng, nếu vẫn giữ nguyên
     *  chiều cao từng hàng phím như lúc đứng thì bàn phím sẽ chiếm phần lớn màn hình, đúng
     *  như phản ánh "khi nằm ngang bàn phím nó quá lớn". Thu nhỏ chiều cao hàng phím + hàng
     *  tiện ích + cỡ chữ trên phím khi đang ở orientation LANDSCAPE để bàn phím gọn lại. */
    private fun isLandscape(): Boolean =
        resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    private fun keyRowHeightDp(): Int = if (isLandscape()) 30 else 48
    private fun utilityRowHeightDp(): Int = if (isLandscape()) 28 else 38
    private fun keyTextSizeScale(): Float = if (isLandscape()) 0.8f else 1f

    // ============================== HÀNG TIỆN ÍCH TRÊN CÙNG ==============================

    private fun buildUtilityRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        row.addView(utilityButton("🌐", normX = 0f) { onGlobeKeyPressed() })
        row.addView(utilityButton("QR", normX = 1f / 3f) { onScanButtonPressed() })
        row.addView(utilityButton("🎤", normX = 2f / 3f) { onMicButtonPressed() })
        // Nút "?123"/"ABC" cũ ở đây bị TRÙNG chức năng với phím "SYM"/"ABC" đã có sẵn ngay
        // trong các hàng phím phía dưới, nên đổi hẳn thành phím tắt mở bàn phím SỐ kiểu máy
        // tính (trang riêng NUMPAD) cho nhanh, không phụ thuộc đang ở trang nào.
        row.addView(utilityButton("123", normX = 1f) {
            currentPage = Page.NUMPAD
            rebuildKeyRows()
            startLedAnimationIfNeeded()
        })
        return row
    }

    private fun utilityButton(label: String, normX: Float, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(ThemeSettings.keyTextColor(this@SmartKeyboardService))
            textSize = 14f * keyTextSizeScale()
            layoutParams = LinearLayout.LayoutParams(0, dp(utilityRowHeightDp()), 1f).also { it.setMargins(dp(3), 0, dp(3), 0) }
            val keyBackground = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(ThemeSettings.utilityButtonBackgroundColor(this@SmartKeyboardService))
                // SỬA LỖI (người dùng phản ánh: "4 phím phía trên không có viền"): 4 phím ở hàng
                // tiện ích (🌐/QR/🎤/123) trước đây KHÔNG hề được thêm vào ledKeySlots - chỉ các
                // phím do buildKey() tạo (những hàng chữ/số phía dưới) mới có, nên hiệu ứng viền
                // RGB (và màu viền tĩnh lúc hiệu ứng tắt) không bao giờ chạm tới 4 phím này. Giờ
                // set sẵn viền trong suốt/độ dày 0 rồi đăng ký vào ledKeySlots giống hệt buildKey()
                // - hiệu ứng chạy (hoặc màu tĩnh) sẽ tự áp dụng luôn cho cả hàng này.
                setStroke(0, Color.TRANSPARENT)
            }
            background = keyBackground
            ledKeySlots.add(LedKeySlot(keyBackground, normX, 0f)) // hàng trên cùng -> normY = 0
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
        // Thêm "/" ngay bên phải ")" - hàng này giờ đủ 10 cột, thẳng hàng với hàng số ở trên.
        listOf("@", "#", "đ", "_", "&", "-", "+", "(", ")", "/"),
        // Đổi chỗ "*" và "=\<" (PAGE3) cho nhau theo đúng vị trí người dùng đã đánh dấu.
        listOf("PAGE3", "*", "\"", "'", ":", ";", "!", "?", "BACKSPACE"),
        listOf("ABC", "LT", "SPACE", "GT", "ENTER"),
    )

    /** Trang 3 - thêm các ký hiệu đặc biệt (toán học, tiền tệ, bản quyền...), mở từ phím
     *  "=\<" ở trang 2. 2 phím góc trái-dưới (?123 và ABC) dùng lại đúng mã phím "SYM"/"ABC"
     *  đã có sẵn nên bấm vào là quay thẳng về trang 2 / trang 1 tương ứng. */
    private val symbols2Rows = listOf(
        listOf("~", "`", "|", "•", "√", "π", "÷", "×", "¶", "Δ"),
        listOf("£", "€", "$", "¢", "^", "°", "=", "{", "}", "\\"),
        listOf("SYM", "%", "©", "®", "™", "‰", "±", "[", "]", "BACKSPACE"),
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
        // View phím cũ (nếu có) sắp bị gỡ hết khỏi cây - reset cache + trạng thái đã áp dụng để
        // lần refreshLetterCaseDisplay() kế tiếp BẮT BUỘC vẽ lại đầy đủ 1 lần (view mới toanh,
        // chưa có chữ hoa/thường đúng) thay vì tưởng "không đổi gì" rồi bỏ qua nhầm.
        shiftKeyView = null
        lastAppliedCapsUpper = null
        lastAppliedCapsLock = null
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
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(keyRowHeightDp()))
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
     *  cột phải 1 phần gồm ABC / Xoá / Enter - riêng Enter cao gấp đôi, chiếm luôn 2 hàng
     *  dưới cùng, giống bàn phím số máy tính trong ảnh mẫu. */
    private fun buildNumpadBody(): View {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(keyRowHeightDp() * 4))
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
        // Đổi chỗ Xoá và ABC theo yêu cầu - ABC giờ ở trên, Xoá ở giữa.
        rightColumn.addView(asVerticalWeighted(buildKey("ABC", normX = 1f, normY = 0f), 1f))
        rightColumn.addView(asVerticalWeighted(buildKey("BACKSPACE", normX = 1f, normY = 0.5f), 1f))
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
            textSize = keyTextSizeScale() * when {
                // Icon nút Xoá ở trang 1 (chữ) và trang 2 (ký hiệu) thu nhỏ còn ~80% (22 -> 17.6)
                // theo yêu cầu - riêng trang 3 và trang bàn phím số giữ nguyên cỡ cũ.
                code == "BACKSPACE" && (currentPage == Page.LETTERS || currentPage == Page.SYMBOLS) -> 17.6f
                code == "ENTER" || code == "SHIFT" || code == "BACKSPACE" -> 22f
                code.length == 1 -> 18f
                else -> 13f
            }
            setTextColor(ThemeSettings.keyTextColor(this@SmartKeyboardService))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight)
                .also { it.setMargins(dp(2), dp(2), dp(2), dp(2)) }
            background = keyBackground
        }

        if (code.length == 1 && code[0].isLetter() && currentPage != Page.SYMBOLS2 && code != "đ") {
            // Trang 3 dùng vài ký tự Hy Lạp/toán học (π, Δ...) mà Kotlin cũng coi là "letter" -
            // không đưa vào letterKeyViews để tránh bị hoa/thường hoá nhầm theo trạng thái Shift.
            // Phím "đ" ở trang Symbols là phím TẮT chèn nhanh ký tự này, không phải phím trong
            // bộ chữ cái đang gõ - phải luôn cố định "đ" thường, không tự hoá "Đ" theo Shift/hoa
            // đầu câu (trước đây bị đưa vào đây nên thỉnh thoảng tự đổi thành "Đ" rất khó hiểu).
            letterKeyViews.add(keyView to code[0])
        }
        if (code == "SHIFT") {
            keyView.tag = "shift_key"
            shiftKeyView = keyView
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
        // CHỈ trang chữ cái (LETTERS) mới có khái niệm hoa/thường - các ký hiệu/số ở trang
        // Symbols và Symbols2 (π, đ, Δ, √...) phải luôn hiển thị ĐÚNG NHÃN GỐC, không tự
        // in hoa theo capsMode. Trước đây thiếu điều kiện currentPage nên mỗi khi bàn phím
        // mở lên sẵn đang ở trang Symbols/Symbols2 lúc auto-cap đầu câu đang bật, các phím
        // 1 ký tự này (đ -> Đ, π -> Π trông vuông vuông như chữ Π hoa...) bị in hoa nhầm.
        else -> if (currentPage == Page.LETTERS && code.length == 1 && capsMode != CapsMode.OFF) code.uppercase() else code
    }

    /** Gắn xử lý chạm cho 1 phím: bấm nhanh -> gõ ngay; giữ lâu -> hiện popup ký tự phụ (nếu
     *  có) hoặc lặp lại liên tục (áp dụng cho Backspace, xoá nhanh khi giữ tay). */
    private fun attachKeyTouchHandling(keyView: TextView, code: String) {
        var repeatRunnable: Runnable? = null
        var longPressRunnable: Runnable? = null
        var longPressTriggered = false
        var backspaceFired = false  // true nếu repeatRunnable đã xoá ít nhất 1 ký tự
        var popupView: LinearLayout? = null
        var popupChars: List<Char> = emptyList()
        var selectedVariantIndex = 0
        var keyBubble: View? = null

        keyView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    longPressTriggered = false
                    v.alpha = 0.6f
                    // TIẾT KIỆM PIN: bất kỳ lần chạm phím nào cũng tính là "đang hoạt động" -
                    // đánh thức lại hiệu ứng LED nếu vừa tạm dừng do 10s không gõ, và luôn hẹn
                    // giờ lại đồng hồ đếm từ đầu (xem armLedIdleTimer()/pauseLedForIdle()).
                    notifyLedActivity()
                    // Hiện "bong bóng chữ" (bubble phóng to ký tự, nổi phía trên phím) trong lúc
                    // đang nhấn giữ, nếu người dùng đã bật ở Cài đặt (mặc định TẮT). Bong bóng
                    // biến mất ngay khi nhả tay (xem ACTION_UP/ACTION_CANCEL bên dưới).
                    if (KeyBubbleSettings.isEnabled(this@SmartKeyboardService) && code !in keyBubbleExcludedCodes) {
                        keyBubble = showKeyBubble(v, displayLabelFor(code))
                    }
                    // Rung phản hồi NGAY LÚC NGÓN TAY CHẠM XUỐNG, không đợi ký tự thật sự
                    // được chèn vào ô nhập (trước đây rung ở cuối, sau khi xử lý Telex/commit
                    // xong nên cảm giác "rung trễ" dù chỉ vài chục mili-giây). Trừ BACKSPACE vì
                    // phím này tự rung theo từng lần xoá khi giữ tay lặp lại (xem handleBackspace()).
                    if (code != "BACKSPACE") VibrationSettings.tick(this@SmartKeyboardService)
                    backspaceFired = false
                    if (code == "BACKSPACE") {
                        repeatRunnable = object : Runnable {
                            override fun run() {
                                backspaceFired = true
                                handleBackspace()
                                mainHandler.postDelayed(this, 50)
                            }
                        }
                        mainHandler.postDelayed(repeatRunnable!!, 350)
                    } else if (code.length == 1 && longPressVariants.containsKey(code[0].lowercaseChar())) {
                        longPressRunnable = Runnable {
                            longPressTriggered = true
                            // Ẩn bong bóng chữ để nhường chỗ cho popup ký tự phụ (áp dụng cho các
                            // phím có dấu như a, e, o...), tránh 2 popup chồng lên nhau.
                            keyBubble?.let { removeKeyBubble(it) }
                            keyBubble = null
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
                    keyBubble?.let { removeKeyBubble(it) }
                    keyBubble = null
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
                    keyBubble?.let { removeKeyBubble(it) }
                    keyBubble = null
                    repeatRunnable?.let { mainHandler.removeCallbacks(it) }
                    longPressRunnable?.let { mainHandler.removeCallbacks(it) }
                    popupView?.let { rootContainer.removeView(it) }
                    // Khi ngón tay quét qua phím Backspace (swipe), Android gửi ACTION_CANCEL
                    // thay vì ACTION_UP nên onKeyTapped không bao giờ được gọi -> không xoá gì.
                    // Nếu repeatRunnable chưa kịp chạy (chưa xoá lần nào), xoá 1 ký tự ở đây.
                    if (code == "BACKSPACE" && !backspaceFired) handleBackspace()
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
            "PAGE3" -> { currentPage = Page.SYMBOLS2; rebuildKeyRows(); startLedAnimationIfNeeded() }
            "SYM" -> { currentPage = Page.SYMBOLS; rebuildKeyRows(); startLedAnimationIfNeeded() }
            "ABC" -> { currentPage = Page.LETTERS; rebuildKeyRows(); startLedAnimationIfNeeded() }
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
        // đúng như hiển thị dù Shift/Caps Lock đang bật từ trang chữ trước đó. Phím tắt "đ" ở
        // trang Symbols cũng vậy - luôn chèn "đ" thường cố định, không tự hoá theo Shift/hoa
        // đầu câu (nó là phím chèn nhanh, không phải phím thuộc bộ chữ cái đang gõ).
        val isUpper = capsMode != CapsMode.OFF && currentPage != Page.SYMBOLS2 && rawChar != 'đ'
        val typedChar = if (rawChar.isLetter() && currentPage != Page.SYMBOLS2 && rawChar != 'đ') {
            if (isUpper) rawChar.uppercaseChar() else rawChar
        } else rawChar // số/ký tự đặc biệt (và phím tắt "đ") không có khái niệm hoa/thường

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
        // Rung đã xử lý ngay lúc chạm xuống (ACTION_DOWN) - xem attachKeyTouchHandling().
    }

    private fun afterCharacterCommitted(isLetter: Boolean) {
        // QUAN TRỌNG: phải tắt autoCapPending TRƯỚC khi gọi refreshLetterCaseDisplay().
        // Lỗi cũ: tắt capsMode xong mới gọi refreshLetterCaseDisplay() trong khi
        // autoCapPending vẫn còn true -> hàm đó thấy "autoCapPending && capsMode==OFF"
        // nên tự BẬT LẠI SINGLE_SHIFT ngay lập tức, khiến CHỮ THỨ 2 cũng bị hoa theo
        // (luôn in hoa 2 chữ cái đầu thay vì đúng 1 chữ theo ý muốn).
        if (isLetter) autoCapPending = false
        if (capsMode == CapsMode.SINGLE_SHIFT) {
            capsMode = CapsMode.OFF
            refreshLetterCaseDisplay()
        }
        // Rung đã xử lý ngay lúc chạm xuống (ACTION_DOWN) - xem attachKeyTouchHandling().
    }

    private fun handleBackspace() {
        val ic = currentInputConnection ?: return
        // Nếu đang có VÙNG CHỌN (bôi đen 1 khối chữ): deleteSurroundingText(1, 0) KHÔNG đụng
        // tới phần đang chọn (theo đúng tài liệu InputConnection - nó chỉ xoá ký tự nằm TRƯỚC
        // vùng chọn, còn khối đang bôi đen giữ nguyên) -> trước đây gây cảm giác "quét khối
        // xoá không được". Có selection thì phải xoá bằng commitText("", 1) - thay thế toàn bộ
        // phần đang chọn bằng chuỗi rỗng, đúng hành vi Backspace tiêu chuẩn của các bàn phím khác.
        val hasSelection = !ic.getSelectedText(0).isNullOrEmpty()
        if (hasSelection) {
            ic.commitText("", 1)
        } else {
            ic.deleteSurroundingText(1, 0)
        }
        // BACKSPACE vẫn tự rung ở đây (không rung ở ACTION_DOWN) vì hàm này còn được gọi lặp
        // lại liên tục lúc giữ tay để xoá nhanh - mỗi lần xoá cần rung riêng để phản hồi đúng
        // từng ký tự đã mất, không chỉ 1 cái rung duy nhất lúc vừa chạm xuống.
        VibrationSettings.tick(this)
    }

    private fun handleSpace() {
        currentInputConnection?.commitText(" ", 1)
        // Rung đã xử lý ngay lúc chạm xuống (ACTION_DOWN) - xem attachKeyTouchHandling().
    }

    private fun handleEnter() {
        currentInputConnection?.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER))
        currentInputConnection?.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER))
        autoCapPending = true
        // Rung đã xử lý ngay lúc chạm xuống (ACTION_DOWN) - xem attachKeyTouchHandling().
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
        // SỬA LỖI (người dùng phản ánh: "chữ cái đầu tiên luôn mặc định là chữ in,
        // không cho chỉnh lại chữ thường"): khi người dùng CHỦ ĐỘNG bấm Shift để
        // TẮT hoa (capsMode vừa chuyển về OFF ở trên), phải HUỶ LUÔN autoCapPending
        // (cờ "tự động viết hoa chữ đầu câu" đang chờ) - nếu không,
        // refreshLetterCaseDisplay() bên dưới sẽ thấy autoCapPending vẫn còn true
        // VÀ capsMode==OFF, rồi TỰ BẬT LẠI SINGLE_SHIFT ngay lập tức (xem điều kiện
        // trong chính hàm đó) - y hệt lỗi đã từng sửa ở afterCharacterCommitted()
        // (xem comment ở đó) nhưng trước đây thiếu áp dụng cho đường bấm Shift này,
        // khiến người dùng bấm Shift để tắt hoa nhưng hoa lại "bật lại ngay tức
        // khắc", CẢM GIÁC như không bấm được / không thể chỉnh về chữ thường.
        if (capsMode == CapsMode.OFF) autoCapPending = false
        refreshLetterCaseDisplay()
    }

    private fun onGlobeKeyPressed() {
        LocaleSettings.switchToNextLocale(this)
        rebuildKeyRows()
        startLedAnimationIfNeeded()
    }

    private fun refreshLetterCaseDisplay() {
        if (autoCapPending && capsMode == CapsMode.OFF) capsMode = CapsMode.SINGLE_SHIFT
        val upper = capsMode != CapsMode.OFF
        val isCapsLock = capsMode == CapsMode.CAPS_LOCK
        // TOI UU: phan lon cac lan go phim KHONG lam thay doi trang thai Hoa/thuong so voi
        // lan truoc (vd go lien tiep nhieu chu thuong sau khi hoa dau cau da tat) - bo qua
        // HOAN TOAN vong lap gan lai .text cho ~26 phim VA buoc tim/cap nhat phim Shift khi
        // ca 2 gia tri deu giu nguyen, tranh cap phat String + duyet cay view vo ich moi phim.
        if (upper == lastAppliedCapsUpper && isCapsLock == lastAppliedCapsLock) return
        lastAppliedCapsUpper = upper
        lastAppliedCapsLock = isCapsLock
        for ((view, baseChar) in letterKeyViews) {
            view.text = if (upper) baseChar.uppercaseChar().toString() else baseChar.toString()
        }
        // Tìm đúng phím Shift qua CACHE đã lưu sẵn lúc tạo phím (shiftKeyView) thay vì
        // findViewWithTag() duyệt đệ quy toàn bộ cây view mỗi lần gọi hàm này.
        shiftKeyView?.text = if (isCapsLock) "⇪" else "⇧"
    }

    // ============================== HIỆU ỨNG VIỀN SÁNG ==============================

    /** Độ dày viền phím lúc hiệu ứng RGB đang chạy. */
    private val ledBorderWidthPx get() = dp(2)

    // TOI UU (nguoi dung phan anh: "co luc bam phan hoi nhanh, co luc cham re
    // re" - do KHONG DEU): mang HSV dung CHUNG, tai su dung MOI KHUNG HINH
    // thay vi cap phat MOI (floatArrayOf(...)) trong colorAtPhase() - truoc
    // day MOI LAN goi ham do (moi PHIM, moi KHUNG HINH, ~40 phim x 60
    // lan/giay = hang nghin lan cap phat MOI GIAY khi hieu ung LED dang
    // chay) deu tao ra 1 FloatArray(3) MOI, gay ap luc don rac (GC) lien
    // tuc tren luong chinh - thinh thoang trung dung luc nguoi dung cham
    // phim se cam thay "khung" 1 nhip. Mang nay CHi tao 1 LAN DUY NHAT, moi
    // lan goi chi GHI DE gia tri vao, khong cap phat gi them.
    private val ledColorHsvScratch = FloatArray(3)
    private var lastLedFrameAt = 0L

    // TIET KIEM PIN: sau 10 giay LIEN TUC khong go phim nao, tu TAM DUNG hieu ung LED dang
    // chay (khong con cap nhat mau/redraw moi khung hinh nua) - gop phan giam hao pin ro ret
    // khi nguoi dung mo ban phim len roi ngung go 1 luc (vd doc lai tin nhan) nhung van de
    // ban phim hien, hieu ung truoc day cu chay MAI KHONG NGUNG du khong ai dung toi. Go phim
    // bat ky lap tuc "danh thuc" lai hieu ung ngay, khong can tat/bat lai o Cai dat.
    private val ledIdleHandler = Handler(Looper.getMainLooper())
    private val ledIdleTimeoutMs = 10_000L
    private var ledPausedForIdle = false
    private val ledIdleRunnable = Runnable { pauseLedForIdle() }

    /** Hẹn giờ lại 10 giây kể từ THỜI ĐIỂM GÕ GẦN NHẤT - huỷ lịch hẹn cũ (nếu còn) trước khi
     *  đặt lịch mới, đảm bảo luôn tính đúng "10 giây kể từ lần gõ cuối", không phải 10 giây
     *  kể từ lúc mở bàn phím lên. */
    private fun armLedIdleTimer() {
        ledIdleHandler.removeCallbacks(ledIdleRunnable)
        ledIdleHandler.postDelayed(ledIdleRunnable, ledIdleTimeoutMs)
    }

    private fun pauseLedForIdle() {
        val animator = ledAnimator ?: return
        if (animator.isRunning) {
            animator.pause() // pause() giữ nguyên pha màu đang hiển thị, không tắt hẳn/reset
            ledPausedForIdle = true
        }
    }

    /** Gọi mỗi khi người dùng chạm BẤT KỲ phím nào - nếu hiệu ứng LED đang tạm dừng do idle
     *  thì chạy tiếp ngay (resume() tiếp tục đúng từ pha đang dở dang, không giật/nhảy màu),
     *  đồng thời luôn hẹn giờ lại đồng hồ đếm 10 giây từ đầu. */
    private fun notifyLedActivity() {
        if (ledPausedForIdle) {
            ledAnimator?.resume()
            ledPausedForIdle = false
        }
        armLedIdleTimer()
    }

    private fun startLedAnimationIfNeeded() {
        ledAnimator?.cancel()
        ledPausedForIdle = false
        ledIdleHandler.removeCallbacks(ledIdleRunnable)

        if (!LedEffectSettings.isEnabled(this)) {
            // Hiệu ứng "chạy" đang TẮT - dải đèn trên cùng tắt hẳn, nhưng viền phím vẫn phải
            // hiển thị TĨNH đúng màu đang chọn ở mục "Màu sắc" (trước đây bị set trong suốt ở
            // đây nên đổi màu trong Cài đặt không thấy tác dụng gì trên bàn phím thật).
            ledStripView.setBackgroundColor(Color.TRANSPARENT)
            val staticColor = ThemeSettings.getAccentColor(this)
            for (slot in ledKeySlots) slot.drawable.setStroke(ledBorderWidthPx, staticColor)
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
                // TOI UU: gioi han toi da ~30 khung hinh/giay cho hieu ung
                // trang tri nay (thay vi mac dinh ~60fps cua ValueAnimator) -
                // mat nguoi hau nhu KHONG phan biet duoc su khac biet o 1
                // hieu ung "chay mau" muot, nhung GIAM DUOC MOT NUA toan bo
                // khoi luong tinh toan/redraw tren luong chinh mot cach lien
                // tuc, danh nhieu "khoang tho" hon cho luong chinh xu ly
                // cham/tha ngon tay dung luc, giam han tan suat bi "khung".
                val now = System.currentTimeMillis()
                if (now - lastLedFrameAt < 28) return@addUpdateListener
                lastLedFrameAt = now

                val globalT = animator.animatedValue as Float

                // Dải đèn mỏng trên cùng - hiển thị đúng màu đang "chạy" tới ngay lúc này (pha 0).
                ledStripView.setBackgroundColor(colorAtPhase(globalT, colorMode, singleHsv, ledColorHsvScratch))

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
                    slot.drawable.setStroke(ledBorderWidthPx, colorAtPhase(phase, colorMode, singleHsv, ledColorHsvScratch))
                }
            }
            start()
        }
        armLedIdleTimer()
    }

    /** Màu tại 1 pha (0..1) của hiệu ứng - "Nhiều màu" quét cầu vồng đủ 360 độ hue (giữ nguyên
     *  bảng màu gốc); "1 màu" dao động giữa phiên bản SÁNG HƠN và ĐẬM HƠN của màu đang chọn,
     *  tạo cảm giác "thở" rõ rệt hơn so với chỉ nhấp nháy độ sáng đơn thuần.
     *
     *  [scratch] TOI UU: mang FloatArray(3) DUNG CHUNG, tai su dung MOI LAN goi thay vi cap
     *  phat moi qua floatArrayOf(...) - ham nay bi goi RAT NHIEU LAN MOI GIAY (moi phim, moi
     *  khung hinh) nen giam cap phat o day co tac dong ro ret den do muot tong the. */
    private fun colorAtPhase(phase: Float, colorMode: LedEffectSettings.ColorMode, singleHsv: FloatArray, scratch: FloatArray): Int {
        return when (colorMode) {
            LedEffectSettings.ColorMode.MULTI_COLOR -> {
                // Giữ nguyên bảng màu cầu vồng như cũ
                scratch[0] = phase * 360f
                scratch[1] = 0.85f
                scratch[2] = 1f
                Color.HSVToColor(scratch)
            }
            LedEffectSettings.ColorMode.SINGLE_COLOR -> {
                // wave: 0..1 theo dạng sóng cos (đỉnh=1 tại phase=0, đáy=0 tại phase=0.5)
                val wave = ((kotlin.math.cos(phase * 2 * Math.PI) + 1) / 2).toFloat()
                // Đỉnh sóng (wave=1) = đúng màu accent gốc (giống màu viền tĩnh).
                // Đáy sóng (wave=0) = tối/đậm hơn (giảm value xuống ~20%) để tạo hiệu ứng "thở".
                // Saturation giữ nguyên suốt - không đổi tone màu.
                val valPeak = singleHsv[2]
                val valDark = (singleHsv[2] * 0.20f).coerceIn(0.05f, 1f)
                val value = valDark + (valPeak - valDark) * wave
                scratch[0] = singleHsv[0]
                scratch[1] = singleHsv[1]
                scratch[2] = value
                Color.HSVToColor(scratch)
            }
        }
    }

    // ============================== QUÉT MÃ QR / VẠCH ==============================

    private fun onScanButtonPressed() {
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

        currentInputConnection?.commitText(content, 1)
        // Yêu cầu: sau mỗi mã quét ra, tự động xuống dòng bằng ENTER CỨNG (gửi thẳng
        // KEYCODE_ENTER qua sendKeyEvent - giống hệt phím Enter thường của bàn phím),
        // KHÔNG dùng performEditorAction (vốn có thể bị ô nhập diễn giải thành "Xong"/
        // "Tìm kiếm"... tuỳ IME option của app, không phải xuống dòng thật).
        currentInputConnection?.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER))
        currentInputConnection?.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER))
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
