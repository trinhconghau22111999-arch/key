@file:OptIn(androidx.camera.core.ExperimentalGetImage::class)

package com.viettype.smartkey

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Khung quét QR/mã vạch dạng NỔI, nằm riêng trên màn hình (vẽ đè lên mọi app), KHÔNG thuộc cửa
 * sổ bàn phím. Nhờ vậy khi bàn phím tự ẩn (ví dụ trang web ẩn bàn phím sau khi nhận Enter) thì
 * khung quét và camera vẫn chạy bình thường - chỉ tắt khi người dùng bấm "Huỷ" (trên khung quét
 * hoặc ở nút "Huỷ quét" trong thông báo).
 *
 * - Chạy dưới dạng dịch vụ nền loại CAMERA (foreground service) để hệ thống cho phép camera
 *   hoạt động khi app đang không ở màn hình chính, kèm 1 thông báo cố định.
 * - Cần quyền "Hiển thị trên các ứng dụng khác" (SYSTEM_ALERT_WINDOW) - xem [canDrawOverlays].
 * - Cửa sổ KHÔNG nhận focus (FLAG_NOT_FOCUSABLE) nên không làm ô nhập đang gõ mất focus / ẩn
 *   bàn phím. Kéo vào vùng hình để di chuyển khung.
 * - Mã đọc được chuyển cho bàn phím qua [ScanBridge] để gõ vào ô nhập đang chọn.
 */
class FloatingScanService : Service(), LifecycleOwner {

    companion object {
        private const val CHANNEL_ID = "floating_scan"
        private const val NOTIFICATION_ID = 7101
        private const val ACTION_START = "com.viettype.smartkey.action.START_FLOATING_SCAN"
        private const val ACTION_STOP = "com.viettype.smartkey.action.STOP_FLOATING_SCAN"

        /** true từ lúc yêu cầu mở cho tới lúc tắt hẳn. */
        @Volatile
        var active = false
            private set

        @Volatile
        private var instance: FloatingScanService? = null

        fun canDrawOverlays(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

        /** Mở khung quét nổi. Trả về false nếu hệ thống từ chối khởi động dịch vụ. */
        fun start(context: Context): Boolean {
            if (active) return true
            active = true
            return try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, FloatingScanService::class.java).setAction(ACTION_START)
                )
                true
            } catch (e: Exception) {
                active = false
                false
            }
        }

        /** Tắt khung quét nổi (gọi từ bàn phím khi bị huỷ hẳn). An toàn khi không có gì đang chạy. */
        fun stop() {
            instance?.requestStop()
        }
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val mainHandler = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var previewView: PreviewView? = null
    private var torchButton: TextView? = null

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var torchOn = false

    private var scanExecutor: ExecutorService? = null
    private var barcodeScanner: BarcodeScanner? = null
    // true = đang "nghỉ" sau khi xử lý 1 mã: bỏ qua mọi khung/kết quả tới cho tới khi hết nghỉ.
    private val frameHandled = AtomicBoolean(false)
    private var rearmRunnable: Runnable? = null
    private var stopped = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopScanning()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (overlayView != null) return START_NOT_STICKY // đã đang chạy
                stopped = false // phòng trường hợp dịch vụ vừa tắt xong được dùng lại ngay
                // Phải gọi startForeground() ngay (hệ thống chỉ cho vài giây sau startForegroundService).
                if (!enterForeground()) {
                    stopScanning()
                    return START_NOT_STICKY
                }
                if (!showOverlay()) {
                    stopScanning()
                    return START_NOT_STICKY
                }
                lifecycleRegistry.currentState = Lifecycle.State.RESUMED
                startCamera()
                ScanBridge.sink?.onScanSessionStarted()
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    // ============================== DỊCH VỤ NỀN + THÔNG BÁO ==============================

    private fun enterForeground(): Boolean {
        return try {
            createNotificationChannel()
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            toast("Không bật được dịch vụ quét nền: ${e.message}")
            false
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Quét mã QR nổi", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, FloatingScanService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Đang quét mã QR")
            .setContentText("Khung quét nổi đang chạy. Bấm Huỷ để tắt.")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Huỷ quét", stopIntent)
            .build()
    }

    // ============================== KHUNG QUÉT NỔI ==============================

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun showOverlay(): Boolean {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val themed = ContextThemeWrapper(this, R.style.Theme_VNSmartKey)

        val metrics = resources.displayMetrics
        val width = minOf(metrics.widthPixels - dp(24), dp(340))
        val height = dp(240)

        val preview = PreviewView(themed).apply {
            // TextureView (COMPATIBLE) hoạt động ổn định hơn SurfaceView trong cửa sổ nổi.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        previewView = preview

        val root = FrameLayout(themed).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.BLACK)
                setStroke(dp(2), Color.WHITE)
            }
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            addView(preview, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(pillButton(themed, "🔦") { toggleTorch() }.also { torchButton = it },
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).also {
                    it.gravity = Gravity.TOP or Gravity.START
                    it.setMargins(dp(8), dp(8), 0, 0)
                })
            addView(pillButton(themed, "Huỷ") { stopScanning() },
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).also {
                    it.gravity = Gravity.TOP or Gravity.END
                    it.setMargins(0, dp(8), dp(8), 0)
                })
            addView(TextView(themed).apply {
                text = "Kéo để di chuyển"
                setTextColor(Color.parseColor("#CCFFFFFF"))
                textSize = 11f
                setShadowLayer(4f, 0f, 0f, Color.BLACK)
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).also {
                it.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                it.setMargins(0, 0, 0, dp(6))
            })
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val lp = WindowManager.LayoutParams(
            width, height, type,
            // KHÔNG nhận focus: ô nhập đang gõ giữ nguyên focus, bàn phím không bị ẩn vì khung quét.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (metrics.widthPixels - width) / 2
            y = dp(80)
        }

        // Kéo vùng hình để di chuyển khung (2 nút bấm vẫn nhận chạm của riêng chúng).
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        root.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX; downRawY = e.rawY
                    startX = lp.x; startY = lp.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val m = resources.displayMetrics
                    lp.x = (startX + (e.rawX - downRawX)).toInt().coerceIn(0, maxOf(0, m.widthPixels - width))
                    lp.y = (startY + (e.rawY - downRawY)).toInt().coerceIn(0, maxOf(0, m.heightPixels - height))
                    try { wm.updateViewLayout(root, lp) } catch (ignored: Exception) { }
                }
            }
            true
        }

        return try {
            wm.addView(root, lp)
            overlayView = root
            overlayParams = lp
            true
        } catch (e: Exception) {
            toast("Không hiện được khung quét nổi - kiểm tra quyền \"Hiển thị trên các ứng dụng khác\".")
            false
        }
    }

    /** Xoay màn hình: kéo khung về lại trong vùng nhìn thấy (vị trí cũ có thể nằm ngoài màn hình mới). */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val view = overlayView ?: return
        val lp = overlayParams ?: return
        val m = resources.displayMetrics
        lp.x = lp.x.coerceIn(0, maxOf(0, m.widthPixels - lp.width))
        lp.y = lp.y.coerceIn(0, maxOf(0, m.heightPixels - lp.height))
        try { windowManager?.updateViewLayout(view, lp) } catch (ignored: Exception) { }
    }

    private fun pillButton(ctx: Context, label: String, onClick: () -> Unit): TextView = TextView(ctx).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 15f
        setPadding(dp(16), dp(8), dp(16), dp(8))
        background = GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setColor(Color.parseColor("#88000000"))
        }
        setOnClickListener { onClick() }
    }

    // ============================== CAMERA + ĐỌC MÃ ==============================

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val view = previewView
                if (stopped || overlayView == null || view == null) return@addListener
                val provider = providerFuture.get()
                cameraProvider = provider
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                val scanner = BarcodeScanning.getClient()
                barcodeScanner = scanner
                frameHandled.set(false)
                // Phân tích khung hình trên luồng NỀN riêng, không chiếm luồng chính.
                val executor = Executors.newSingleThreadExecutor()
                scanExecutor = executor
                analysis.setAnalyzer(executor) { imageProxy -> analyzeFrame(imageProxy, scanner) }
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                toast("Không mở được camera: ${e.message}")
                stopScanning()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Chạy liên tục (~30 lần/giây) trên luồng nền - LUÔN đóng imageProxy (kể cả khi lỗi), nếu không
     *  camera sẽ "tắc" (ngừng gửi khung mới). */
    private fun analyzeFrame(imageProxy: ImageProxy, scanner: BarcodeScanner) {
        try {
            val mediaImage = imageProxy.image
            if (mediaImage == null || frameHandled.get()) {
                imageProxy.close()
                return
            }
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            scanner.process(image)
                .addOnSuccessListener { barcodes -> handleBarcodes(barcodes) }
                .addOnFailureListener { /* bỏ qua 1 khung lỗi - sẽ có khung kế tiếp */ }
                .addOnCompleteListener { imageProxy.close() }
        } catch (e: Exception) {
            try { imageProxy.close() } catch (ignored: Exception) { }
        }
    }

    /** Ưu tiên rawValue, rồi displayValue, cuối cùng giải mã rawBytes (UTF-8, lỗi thì ISO-8859-1). */
    private fun extractBarcodeText(barcode: Barcode): String? {
        val raw = barcode.rawValue
        if (!raw.isNullOrEmpty()) return raw
        val shown = barcode.displayValue
        if (!shown.isNullOrEmpty()) return shown
        val bytes = barcode.rawBytes ?: return null
        if (bytes.isEmpty()) return null
        return try {
            val utf8 = String(bytes, Charsets.UTF_8)
            if (utf8.contains('\uFFFD')) String(bytes, Charsets.ISO_8859_1) else utf8
        } catch (e: Exception) {
            String(bytes, Charsets.ISO_8859_1)
        }
    }

    private fun handleBarcodes(barcodes: List<Barcode>) {
        if (stopped || overlayView == null || frameHandled.get()) return
        val content = barcodes.asSequence().mapNotNull { extractBarcodeText(it) }.firstOrNull() ?: return
        // Cờ nguyên tử: chỉ 1 kết quả được xử lý mỗi lượt, dù nhiều khung về cùng lúc.
        if (!frameHandled.compareAndSet(false, true)) return
        var delayMs = 1500L
        try {
            val sink = ScanBridge.sink
            delayMs = sink?.onScanned(content) ?: 3000L // bàn phím không còn -> chờ lâu hơn, đỡ tốn công
        } catch (e: Exception) {
            // Không để lỗi xử lý 1 mã làm sập dịch vụ.
        } finally {
            if (!stopped) scheduleRearm(delayMs)
        }
    }

    private fun scheduleRearm(delayMs: Long) {
        rearmRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable { frameHandled.set(false) }
        rearmRunnable = r
        mainHandler.postDelayed(r, delayMs)
    }

    private fun toggleTorch() {
        val cam = camera ?: return
        if (!cam.cameraInfo.hasFlashUnit()) {
            toast("Thiết bị không có đèn flash.")
            return
        }
        torchOn = !torchOn
        cam.cameraControl.enableTorch(torchOn)
        torchButton?.apply {
            text = if (torchOn) "💡" else "🔦"
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(if (torchOn) ThemeSettings.getAccentColor(this@FloatingScanService) else Color.parseColor("#88000000"))
            }
        }
    }

    // ============================== TẮT ==============================

    /** Gọi từ luồng bất kỳ. */
    private fun requestStop() {
        mainHandler.post { stopScanning() }
    }

    /** Tắt hẳn: gỡ khung, đóng camera + ML Kit, dừng dịch vụ nền. An toàn khi gọi nhiều lần. */
    private fun stopScanning() {
        if (stopped) return
        stopped = true
        active = false
        rearmRunnable?.let { mainHandler.removeCallbacks(it) }
        rearmRunnable = null
        if (torchOn) {
            try { camera?.cameraControl?.enableTorch(false) } catch (ignored: Exception) { }
        }
        torchOn = false
        camera = null
        try { cameraProvider?.unbindAll() } catch (ignored: Exception) { }
        try { barcodeScanner?.close() } catch (ignored: Exception) { }
        barcodeScanner = null
        scanExecutor?.shutdown()
        scanExecutor = null
        overlayView?.let { v -> try { windowManager?.removeView(v) } catch (ignored: Exception) { } }
        overlayView = null
        overlayParams = null
        previewView = null
        torchButton = null
        ScanBridge.sink?.onScanSessionEnded()
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (!stopped) stopScanning() // hệ thống huỷ dịch vụ -> vẫn phải trả camera + gỡ khung
        instance = null
        active = false
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    private fun toast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
    }
}
