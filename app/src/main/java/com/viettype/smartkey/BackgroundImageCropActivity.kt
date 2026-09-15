package com.viettype.smartkey

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * Sau khi chọn 1 ảnh bất kỳ từ thư viện/thư mục máy (xem SettingsActivity - nút "Đặt hình nền
 * bằng ảnh"), màn hình này cho hiện khung cắt CỐ ĐỊNH đúng TỈ LỆ CHUẨN của bàn phím thật (không
 * đổi hình dạng/tỉ lệ khung được) - người dùng CHỈ được DI CHUYỂN ảnh (kéo 1 ngón) và
 * PHÓNG TO/THU NHỎ ảnh (chụm/mở 2 ngón) bên trong khung đó, không kéo góc để đổi khung. Chọn
 * xong bấm "Đặt làm nền" là cắt đúng phần đang hiện trong khung và áp dụng ngay.
 */
class BackgroundImageCropActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE_URI = "extra_image_uri"

        /** Giới hạn cạnh dài nhất của ảnh gốc khi giải mã - ảnh nền bàn phím chỉ hiển thị ở 1
         *  dải cao vài trăm px, giữ nguyên ảnh gốc (có thể 4000x3000px từ camera hiện đại) chỉ
         *  tốn bộ nhớ vô ích, thậm chí OOM crash với ảnh rất lớn. */
        private const val MAX_SOURCE_DIMENSION = 2048
    }

    private lateinit var cropCanvas: CropCanvasView
    private var sourceBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = intent.getParcelableExtra<Uri>(EXTRA_IMAGE_URI)
        if (uri == null) {
            finish()
            return
        }

        val bitmap = try {
            loadRotatedAndDownsampledBitmap(uri)
        } catch (e: Exception) {
            CrashLogger.log(this, "BackgroundImageCropActivity: loi doc anh", e)
            null
        }
        if (bitmap == null) {
            Toast.makeText(this, "Không đọc được ảnh này, thử chọn ảnh khác.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        sourceBitmap = bitmap

        setContentView(buildUi(bitmap))
    }

    private fun buildUi(bitmap: Bitmap): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1A0F2E"))
        }

        root.addView(TextView(this).apply {
            text = "Kéo 1 ngón để di chuyển ảnh, chụm/mở 2 ngón để phóng to/thu nhỏ - khung cắt " +
                "giữ đúng tỉ lệ bàn phím thật, chọn xong bấm \"Đặt làm nền\"."
            setTextColor(Color.LTGRAY)
            textSize = 14f
            setPadding(dp(20), dp(20), dp(20), dp(12))
        })

        val stage = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            clipToPadding = false
        }
        cropCanvas = CropCanvasView(this)
        cropCanvas.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        cropCanvas.bitmap = bitmap
        stage.addView(cropCanvas)
        root.addView(stage)

        // Đợi layout đo xong kích thước thật của khung "sân khấu" chứa canvas rồi mới tính
        // khung cắt CỐ ĐỊNH (đúng tỉ lệ bàn phím thật, chiếm hết bề rộng khả dụng, cao theo
        // đúng tỉ lệ đó, canh giữa) - kích thước thật chỉ biết được SAU khi đo xong layout.
        cropCanvas.post {
            val ratio = keyboardAspectRatio()
            val maxW = cropCanvas.width.toFloat()
            val maxH = cropCanvas.height.toFloat()
            var frameW = maxW
            var frameH = frameW / ratio
            if (frameH > maxH) {
                frameH = maxH
                frameW = frameH * ratio
            }
            val left = (maxW - frameW) / 2f
            val top = (maxH - frameH) / 2f
            cropCanvas.setFixedFrame(RectF(left, top, left + frameW, top + frameH))
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        buttonRow.addView(flatButton("Huỷ") { finish() }.also {
            it.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also { lp -> lp.setMargins(0, 0, dp(8), 0) }
        })
        buttonRow.addView(flatButton("✓  Đặt làm nền", highlighted = true) { onConfirmCrop() }.also {
            it.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also { lp -> lp.setMargins(dp(8), 0, 0, 0) }
        })
        root.addView(buttonRow)

        return root
    }

    /** Tỉ lệ CHUẨN (rộng/cao) của khung cắt - tính ĐÚNG theo cách bàn phím thật tự dựng kích
     *  thước của nó (xem SmartKeyboardService.keyRowHeightDp()/utilityRowHeightDp() - 4 hàng
     *  phím trang chữ mặc định + hàng tiện ích + dải đèn LED trên cùng, ở chế độ đứng), để ảnh
     *  đặt làm nền vừa khít khung bàn phím thật, không bị kéo giãn/méo khi hiển thị. */
    private fun keyboardAspectRatio(): Float {
        val widthPx = resources.displayMetrics.widthPixels.toFloat()
        val keyRowHeightDp = 48
        val utilityRowHeightDp = 38
        val ledStripHeightDp = 5
        val letterRowCount = 4
        val heightPx = dp(letterRowCount * keyRowHeightDp + utilityRowHeightDp + ledStripHeightDp).toFloat()
        return widthPx / heightPx
    }

    private fun flatButton(label: String, highlighted: Boolean = false, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(if (highlighted) Color.parseColor("#1A0F2E") else Color.WHITE)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(if (highlighted) ThemeSettings.getAccentColor(this@BackgroundImageCropActivity) else Color.parseColor("#332A1F4A"))
            }
            setOnClickListener { onClick() }
        }
    }

    /** Cắt đúng vùng đang hiện TRONG KHUNG (map ngược toạ độ khung, qua ma trận nghịch đảo của
     *  ma trận di chuyển/phóng to hiện tại, về toạ độ pixel thật của ảnh gốc), lưu đè lên ĐÚNG 1
     *  file cố định trong bộ nhớ app (xem ThemeSettings.BACKGROUND_IMAGE_FILE_NAME - tránh tích
     *  rác file ảnh cũ theo thời gian), rồi báo cho màn Cài đặt biết để áp dụng ngay lên bàn
     *  phím thật. */
    private fun onConfirmCrop() {
        val bitmap = sourceBitmap ?: return
        val cropBoxInBitmap = cropCanvas.computeCropRectInBitmap()
        if (cropBoxInBitmap == null) {
            Toast.makeText(this, "Chưa xác định được vùng cắt, thử lại.", Toast.LENGTH_SHORT).show()
            return
        }

        val cropped = try {
            Bitmap.createBitmap(
                bitmap, cropBoxInBitmap.left, cropBoxInBitmap.top,
                cropBoxInBitmap.width(), cropBoxInBitmap.height()
            )
        } catch (e: Exception) {
            CrashLogger.log(this, "BackgroundImageCropActivity: loi cat anh", e)
            Toast.makeText(this, "Không cắt được ảnh, thử lại.", Toast.LENGTH_SHORT).show()
            return
        }

        val outFile = File(filesDir, ThemeSettings.BACKGROUND_IMAGE_FILE_NAME)
        try {
            FileOutputStream(outFile).use { out ->
                cropped.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
        } catch (e: Exception) {
            CrashLogger.log(this, "BackgroundImageCropActivity: loi luu anh", e)
            Toast.makeText(this, "Không lưu được ảnh nền, thử lại.", Toast.LENGTH_SHORT).show()
            return
        } finally {
            if (cropped != bitmap) cropped.recycle()
        }

        ThemeSettings.setBackgroundImagePath(this, outFile.absolutePath)
        setResult(RESULT_OK)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Bitmap gốc (có thể vài MB) chỉ cần trong lúc màn này còn mở - giải phóng ngay khi
        // đóng màn, không để phụ thuộc GC tự dọn sau, tránh giữ bộ nhớ ảnh thừa không cần thiết.
        sourceBitmap?.recycle()
        sourceBitmap = null
    }

    /** Đọc ảnh từ Uri đã chọn, TỰ ĐỘNG GIẢM KÍCH THƯỚC nếu ảnh gốc quá lớn (tránh tốn bộ nhớ/OOM)
     *  và XOAY LẠI đúng chiều theo thông tin Exif (ảnh chụp từ camera điện thoại thường lưu kèm
     *  cờ xoay trong Exif chứ không xoay sẵn pixel - không đọc cờ này ảnh dễ hiện bị nghiêng
     *  90°/180° so với lúc xem trong thư viện ảnh). */
    private fun loadRotatedAndDownsampledBitmap(uri: Uri): Bitmap? {
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, boundsOptions) }
        val (rawWidth, rawHeight) = boundsOptions.outWidth to boundsOptions.outHeight
        if (rawWidth <= 0 || rawHeight <= 0) return null

        var sampleSize = 1
        while (rawWidth / (sampleSize * 2) >= MAX_SOURCE_DIMENSION || rawHeight / (sampleSize * 2) >= MAX_SOURCE_DIMENSION) {
            sampleSize *= 2
        }
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOptions) } ?: return null

        val rotationDegrees = try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        } catch (ignored: Exception) {
            0 // Không đọc được Exif (vài định dạng/nguồn ảnh không hỗ trợ) - dùng ảnh gốc, không chặn cả luồng chọn ảnh vì lỗi phụ này.
        }
        if (rotationDegrees == 0) return decoded

        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (rotated != decoded) decoded.recycle()
        return rotated
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** Vẽ ảnh bằng 1 ma trận (scale + di chuyển) tự quản lý, cộng với khung cắt CỐ ĐỊNH (kích
     *  thước/tỉ lệ không đổi trong suốt quá trình, set 1 lần qua [setFixedFrame]). Kéo 1 ngón
     *  = di chuyển ảnh (dịch ma trận); chụm/mở 2 ngón = phóng to/thu nhỏ ảnh quanh điểm chụm
     *  (ScaleGestureDetector chuẩn của Android) - ảnh luôn bị KẸP không cho nhỏ hơn khung (viền
     *  khung sẽ hiện khoảng trống nếu cho phép) và không phóng to quá 1 mức hợp lý (tránh ảnh
     *  vỡ nét vì phóng to vượt xa độ phân giải gốc). KHÔNG có tay cầm góc/cạnh nào để kéo đổi
     *  khung - khung giữ nguyên tỉ lệ/kích thước suốt từ đầu tới lúc xác nhận. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private class CropCanvasView(context: Context) : View(context) {

        var bitmap: Bitmap? = null
            set(value) {
                field = value
                if (value != null && !frameRect.isEmpty) resetTransformForBitmap(value)
                invalidate()
            }

        private val frameRect = RectF()
        private val imageMatrix = Matrix()
        private val inverseMatrix = Matrix()
        private var minScale = 1f
        private var maxScale = 1f
        private var currentScale = 1f

        private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val dimPaint = Paint().apply { color = Color.parseColor("#AA000000") }
        private val borderPaint = Paint().apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            strokeWidth = context.resources.displayMetrics.density * 2f
        }

        private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val bmp = bitmap ?: return false
                val targetScale = (currentScale * detector.scaleFactor).coerceIn(minScale, maxScale)
                val factor = targetScale / currentScale
                if (factor != 1f) {
                    imageMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
                    currentScale = targetScale
                    clampTranslation(bmp)
                    invalidate()
                }
                return true
            }
        })
        private var isSinglePointerPanning = false
        private var lastTouchX = 0f
        private var lastTouchY = 0f

        /** Gọi 1 LẦN sau khi layout xong - khung cắt CỐ ĐỊNH tỉ lệ/kích thước từ đây trở đi,
         *  không đổi lại nữa trong suốt phiên cắt ảnh (đúng yêu cầu: không kéo góc đổi khung). */
        fun setFixedFrame(rect: RectF) {
            frameRect.set(rect)
            bitmap?.let { resetTransformForBitmap(it) }
            invalidate()
        }

        /** Đặt lại vị trí/độ phóng BAN ĐẦU của ảnh: scale nhỏ nhất để ảnh LẤP ĐẦY khung (không
         *  chừa khoảng trống), canh giữa khung - đúng hành vi "center crop" quen thuộc, người
         *  dùng phóng to thêm/di chuyển tự do từ điểm bắt đầu này. */
        private fun resetTransformForBitmap(bmp: Bitmap) {
            if (frameRect.isEmpty || bmp.width <= 0 || bmp.height <= 0) return
            val scaleX = frameRect.width() / bmp.width
            val scaleY = frameRect.height() / bmp.height
            minScale = max(scaleX, scaleY)
            maxScale = minScale * 6f
            currentScale = minScale

            imageMatrix.reset()
            imageMatrix.postScale(minScale, minScale)
            val scaledW = bmp.width * minScale
            val scaledH = bmp.height * minScale
            val dx = frameRect.left + (frameRect.width() - scaledW) / 2f
            val dy = frameRect.top + (frameRect.height() - scaledH) / 2f
            imageMatrix.postTranslate(dx, dy)
        }

        /** Không cho kéo/phóng khiến khung "lòi ra" ngoài ảnh (viền khung sẽ lộ khoảng trống
         *  không có ảnh nếu không kẹp) - sau mỗi lần đổi ma trận, đẩy ảnh lại vừa đủ để 4 cạnh
         *  khung luôn nằm trọn trong biên ảnh đã biến đổi. */
        private fun clampTranslation(bmp: Bitmap) {
            val bounds = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
            imageMatrix.mapRect(bounds)
            var dx = 0f
            var dy = 0f
            if (bounds.left > frameRect.left) dx = frameRect.left - bounds.left
            if (bounds.right < frameRect.right) dx = frameRect.right - bounds.right
            if (bounds.top > frameRect.top) dy = frameRect.top - bounds.top
            if (bounds.bottom < frameRect.bottom) dy = frameRect.bottom - bounds.bottom
            if (dx != 0f || dy != 0f) imageMatrix.postTranslate(dx, dy)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val bmp = bitmap ?: return
            if (frameRect.isEmpty) return

            canvas.drawBitmap(bmp, imageMatrix, bitmapPaint)

            // Phủ mờ 4 dải ngoài khung cắt - phần bên trong khung để nguyên, thấy rõ đang chọn gì.
            canvas.drawRect(0f, 0f, width.toFloat(), frameRect.top, dimPaint)
            canvas.drawRect(0f, frameRect.bottom, width.toFloat(), height.toFloat(), dimPaint)
            canvas.drawRect(0f, frameRect.top, frameRect.left, frameRect.bottom, dimPaint)
            canvas.drawRect(frameRect.right, frameRect.top, width.toFloat(), frameRect.bottom, dimPaint)
            canvas.drawRect(frameRect, borderPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val bmp = bitmap ?: return false
            scaleDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isSinglePointerPanning = true
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    // Ngón thứ 2 vừa chạm xuống - chuyển hẳn sang chế độ phóng to (ScaleGestureDetector
                    // lo phần đó), tạm ngưng di chuyển bằng 1 ngón để 2 việc không chồng lên nhau
                    // gây giật hình.
                    isSinglePointerPanning = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isSinglePointerPanning && event.pointerCount == 1 && !scaleDetector.isInProgress) {
                        val dx = event.x - lastTouchX
                        val dy = event.y - lastTouchY
                        imageMatrix.postTranslate(dx, dy)
                        clampTranslation(bmp)
                        invalidate()
                    }
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    // 1 trong 2 ngón vừa nhấc lên, còn lại đúng 1 ngón - quay về chế độ di chuyển,
                    // lấy mốc toạ độ MỚI từ ngón còn lại để không bị "giật" 1 nhịp do lệch mốc cũ.
                    if (event.pointerCount - 1 == 1) {
                        val remainingIndex = if (event.actionIndex == 0) 1 else 0
                        lastTouchX = event.getX(remainingIndex)
                        lastTouchY = event.getY(remainingIndex)
                        isSinglePointerPanning = true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isSinglePointerPanning = false
                }
            }
            return true
        }

        /** Map khung cắt (toạ độ trên màn hình) NGƯỢC lại thành toạ độ pixel thật trên ảnh gốc,
         *  qua ma trận nghịch đảo của đúng ma trận đang dùng để vẽ ảnh hiện tại - luôn khớp
         *  chính xác 100% với những gì mắt đang thấy trong khung, bất kể đã kéo/phóng thế nào. */
        fun computeCropRectInBitmap(): Rect? {
            val bmp = bitmap ?: return null
            if (frameRect.isEmpty) return null
            if (!imageMatrix.invert(inverseMatrix)) return null
            val mapped = RectF(frameRect)
            inverseMatrix.mapRect(mapped)
            val left = mapped.left.toInt().coerceIn(0, bmp.width - 1)
            val top = mapped.top.toInt().coerceIn(0, bmp.height - 1)
            val right = mapped.right.toInt().coerceIn(left + 1, bmp.width)
            val bottom = mapped.bottom.toInt().coerceIn(top + 1, bmp.height)
            return Rect(left, top, right, bottom)
        }
    }
}
