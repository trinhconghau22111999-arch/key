package com.viettype.smartkey

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Sau khi chọn 1 ảnh bất kỳ từ thư viện/thư mục máy (xem SettingsActivity - nút "Đặt hình nền
 * bằng ảnh"), màn hình này cho phép CHỌN TỰ DO vùng ảnh muốn dùng làm nền bàn phím - KHÔNG cố
 * định sẵn 1 khung/tỉ lệ nào cả: người dùng tự kéo 4 góc để đổi kích thước, kéo bên trong khung
 * để di chuyển, chọn xong bấm "Đặt làm nền" là áp dụng ngay.
 */
class BackgroundImageCropActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE_URI = "extra_image_uri"

        /** Giới hạn cạnh dài nhất của ảnh gốc khi giải mã - ảnh nền bàn phím chỉ hiển thị ở 1
         *  dải cao vài trăm px, giữ nguyên ảnh gốc (có thể 4000x3000px từ camera hiện đại) chỉ
         *  tốn bộ nhớ vô ích, thậm chí OOM crash với ảnh rất lớn. */
        private const val MAX_SOURCE_DIMENSION = 2048
    }

    private lateinit var imageView: ImageView
    private lateinit var cropOverlay: CropOverlayView
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
            text = "Kéo góc để chỉnh kích thước, kéo giữa khung để di chuyển - chọn xong bấm \"Đặt làm nền\"."
            setTextColor(Color.LTGRAY)
            textSize = 14f
            setPadding(dp(20), dp(20), dp(20), dp(12))
        })

        val stage = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        imageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(bitmap)
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        cropOverlay = CropOverlayView(this)
        cropOverlay.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        stage.addView(imageView)
        stage.addView(cropOverlay)
        root.addView(stage)

        // Đợi layout đo xong kích thước thật của ImageView rồi mới tính khung ảnh hiển thị
        // (FIT_CENTER co giãn + căn giữa - vị trí/kích thước thật chỉ biết được SAU khi đo xong,
        // không tính trước được từ lúc build UI).
        imageView.post {
            val displayRect = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
            imageView.imageMatrix.mapRect(displayRect)
            cropOverlay.setImageDisplayRect(displayRect)
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

    /** Cắt đúng vùng người dùng đã chọn (toạ độ khung chọn map ngược về toạ độ pixel thật của
     *  ảnh gốc), lưu đè lên ĐÚNG 1 file cố định trong bộ nhớ app (xem giải thích ở
     *  ThemeSettings.BACKGROUND_IMAGE_FILE_NAME - tránh tích rác file ảnh cũ theo thời gian),
     *  rồi báo cho màn Cài đặt biết để áp dụng ngay lên bàn phím thật. */
    private fun onConfirmCrop() {
        val bitmap = sourceBitmap ?: return
        val displayRect = cropOverlay.imageDisplayRect ?: return
        val cropRectInView = cropOverlay.cropRect
        if (cropRectInView.width() < 4 || cropRectInView.height() < 4 || displayRect.width() <= 0f) {
            Toast.makeText(this, "Vùng chọn quá nhỏ, hãy kéo rộng khung ra.", Toast.LENGTH_SHORT).show()
            return
        }

        val scale = bitmap.width.toFloat() / displayRect.width()
        val left = ((cropRectInView.left - displayRect.left) * scale).toInt().coerceIn(0, bitmap.width - 1)
        val top = ((cropRectInView.top - displayRect.top) * scale).toInt().coerceIn(0, bitmap.height - 1)
        val right = ((cropRectInView.right - displayRect.left) * scale).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = ((cropRectInView.bottom - displayRect.top) * scale).toInt().coerceIn(top + 1, bitmap.height)

        val cropped = try {
            Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
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

        val matrix = android.graphics.Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (rotated != decoded) decoded.recycle()
        return rotated
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** Khung chọn vùng ảnh TỰ DO - vẽ lớp phủ mờ bên ngoài khung + viền/4 tay cầm góc bên trong.
     *  Kéo 1 trong 4 góc để đổi kích thước, kéo vùng bên trong khung để di chuyển cả khung -
     *  không giới hạn tỉ lệ khung, không có vùng cắt "mặc định cố định" nào người dùng buộc
     *  phải dùng nguyên - khung khởi tạo chỉ là gợi ý, kéo chỉnh lại tự do trước khi xác nhận. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private class CropOverlayView(context: Context) : View(context) {

        var imageDisplayRect: RectF? = null
            private set

        val cropRect = RectF()

        private val dimPaint = Paint().apply { color = Color.parseColor("#AA000000") }
        private val borderPaint = Paint().apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            strokeWidth = context.resources.displayMetrics.density * 2f
        }
        private val handlePaint = Paint().apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        private val handleRadiusPx = context.resources.displayMetrics.density * 8f
        private val touchSlopPx = context.resources.displayMetrics.density * 24f
        private val minCropSizePx = context.resources.displayMetrics.density * 40f

        private enum class DragMode { NONE, MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }
        private var dragMode = DragMode.NONE
        private var lastTouchX = 0f
        private var lastTouchY = 0f

        /** Gọi 1 LẦN DUY NHẤT sau khi biết khung ảnh hiển thị thật (sau layout) - đặt khung chọn
         *  MẶC ĐỊNH ở giữa ảnh, chiếm ~80% - CHỈ là điểm bắt đầu để có gì đó nhìn thấy ngay, người
         *  dùng kéo chỉnh lại tự do sau đó, không bắt buộc giữ nguyên. */
        fun setImageDisplayRect(rect: RectF) {
            imageDisplayRect = rect
            val insetX = rect.width() * 0.1f
            val insetY = rect.height() * 0.1f
            cropRect.set(rect.left + insetX, rect.top + insetY, rect.right - insetX, rect.bottom - insetY)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val display = imageDisplayRect ?: return

            // Phủ mờ TOÀN BỘ khung ảnh rồi "khoét" đúng vùng đang chọn (vẽ lại y hệt nền, coi
            // như xoá phần phủ mờ ở đó) - cách đơn giản không cần PorterDuff/layer riêng.
            canvas.drawRect(display.left, display.top, display.right, cropRect.top, dimPaint)
            canvas.drawRect(display.left, cropRect.bottom, display.right, display.bottom, dimPaint)
            canvas.drawRect(display.left, cropRect.top, cropRect.left, cropRect.bottom, dimPaint)
            canvas.drawRect(cropRect.right, cropRect.top, display.right, cropRect.bottom, dimPaint)

            canvas.drawRect(cropRect, borderPaint)
            for ((cx, cy) in listOf(
                cropRect.left to cropRect.top, cropRect.right to cropRect.top,
                cropRect.left to cropRect.bottom, cropRect.right to cropRect.bottom,
            )) {
                canvas.drawCircle(cx, cy, handleRadiusPx, handlePaint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val display = imageDisplayRect ?: return false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragMode = detectDragMode(event.x, event.y)
                    lastTouchX = event.x
                    lastTouchY = event.y
                    return dragMode != DragMode.NONE
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    lastTouchX = event.x
                    lastTouchY = event.y
                    when (dragMode) {
                        DragMode.MOVE -> {
                            // Kẹp việc di chuyển trong biên ảnh - không cho kéo khung ra ngoài
                            // vùng ảnh thật sự hiển thị (phần phủ mờ), tránh chọn nhầm vùng trống.
                            val clampedDx = dx.coerceIn(display.left - cropRect.left, display.right - cropRect.right)
                            val clampedDy = dy.coerceIn(display.top - cropRect.top, display.bottom - cropRect.bottom)
                            cropRect.offset(clampedDx, clampedDy)
                        }
                        DragMode.TOP_LEFT -> {
                            cropRect.left = min(cropRect.left + dx, cropRect.right - minCropSizePx).coerceAtLeast(display.left)
                            cropRect.top = min(cropRect.top + dy, cropRect.bottom - minCropSizePx).coerceAtLeast(display.top)
                        }
                        DragMode.TOP_RIGHT -> {
                            cropRect.right = max(cropRect.right + dx, cropRect.left + minCropSizePx).coerceAtMost(display.right)
                            cropRect.top = min(cropRect.top + dy, cropRect.bottom - minCropSizePx).coerceAtLeast(display.top)
                        }
                        DragMode.BOTTOM_LEFT -> {
                            cropRect.left = min(cropRect.left + dx, cropRect.right - minCropSizePx).coerceAtLeast(display.left)
                            cropRect.bottom = max(cropRect.bottom + dy, cropRect.top + minCropSizePx).coerceAtMost(display.bottom)
                        }
                        DragMode.BOTTOM_RIGHT -> {
                            cropRect.right = max(cropRect.right + dx, cropRect.left + minCropSizePx).coerceAtMost(display.right)
                            cropRect.bottom = max(cropRect.bottom + dy, cropRect.top + minCropSizePx).coerceAtMost(display.bottom)
                        }
                        DragMode.NONE -> return false
                    }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragMode = DragMode.NONE
                    return true
                }
            }
            return false
        }

        /** Chạm gần góc nào (trong bán kính [touchSlopPx], rộng hơn hẳn tay cầm vẽ ra để dễ
         *  bắt trúng bằng ngón tay) thì kéo-đổi-cỡ theo góc đó; chạm bên trong khung thì kéo
         *  di chuyển cả khung; chạm ra ngoài khung thì bỏ qua (không vẽ khung mới ở đây - phạm
         *  vi tính năng hiện tại là ĐIỀU CHỈNH khung có sẵn, không phải vẽ khung từ đầu). */
        private fun detectDragMode(x: Float, y: Float): DragMode {
            fun near(px: Float, py: Float) = (x - px) * (x - px) + (y - py) * (y - py) <= touchSlopPx * touchSlopPx
            return when {
                near(cropRect.left, cropRect.top) -> DragMode.TOP_LEFT
                near(cropRect.right, cropRect.top) -> DragMode.TOP_RIGHT
                near(cropRect.left, cropRect.bottom) -> DragMode.BOTTOM_LEFT
                near(cropRect.right, cropRect.bottom) -> DragMode.BOTTOM_RIGHT
                cropRect.contains(x, y) -> DragMode.MOVE
                else -> DragMode.NONE
            }
        }
    }
}
