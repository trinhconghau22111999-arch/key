package com.viettype.smartkey

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Phát tiếng "tách" mỗi lần bấm phím, có chỉnh âm lượng 0..100%, ĐỘ TRỄ THẤP.
 *
 * Các điểm giảm trễ so với bản đầu:
 *  1. Track mở ở chế độ LOW_LATENCY (đường mixer nhanh của Android 8+) và dùng đúng tần số lấy mẫu
 *     gốc của loa máy (thường 48000Hz) - sai tần số thì hệ thống phải resample và mất đường nhanh.
 *  2. Tạo sẵn các track NGAY khi khởi tạo (không đợi tới phím đầu tiên mới tạo - việc tạo track
 *     mất vài chục ms).
 *  3. Mỗi lần bấm chỉ gọi play(). Việc dừng + nạp lại buffer cho lần sau được làm ngầm SAU KHI
 *     tiếng click phát xong, không nằm trong đường đi của lần bấm.
 *  4. Luồng phát chạy mức ưu tiên âm thanh (URGENT_AUDIO).
 */
class KeyClickPlayer(context: Context) {

    private val sampleRate: Int = nativeSampleRate(context)
    private val poolSize = 3 // xoay vòng nhiều track để gõ nhanh các tiếng không cắt nhau
    private val pcm: ShortArray = buildClickPcm()
    private val tracks: Array<AudioTrack?> = arrayOfNulls(poolSize)
    private val primed = BooleanArray(poolSize)
    private var next = 0

    private val thread = HandlerThread("key-click", Process.THREAD_PRIORITY_URGENT_AUDIO).apply { start() }
    private val handler = Handler(thread.looper)

    // Thời gian chờ trước khi nạp lại track: tiếng click dài ~22ms, chờ dư để chắc chắn đã phát xong.
    private val reprimeDelayMs = 70L
    private val reprimeRunnables: Array<Runnable> = Array(poolSize) { index -> Runnable { reprime(index) } }

    @Volatile
    private var released = false

    init {
        // Tạo sẵn track trên luồng nền để lần bấm đầu tiên không phải chờ.
        handler.post {
            for (i in 0 until poolSize) {
                if (released) return@post
                ensureTrack(i)
            }
        }
    }

    /** volumePercent <= 0: không phát gì. */
    fun play(volumePercent: Int) {
        if (released || volumePercent <= 0) return
        val volume = volumePercent.coerceIn(0, 100) / 100f
        handler.postAtFrontOfQueue { playOnWorker(volume) }
    }

    fun release() {
        released = true
        handler.post {
            for (i in tracks.indices) {
                handler.removeCallbacks(reprimeRunnables[i])
                try { tracks[i]?.release() } catch (ignored: Exception) { }
                tracks[i] = null
            }
        }
        thread.quitSafely()
    }

    private fun ensureTrack(index: Int): AudioTrack? {
        val existing = tracks[index]
        if (existing != null) return existing
        val track = createTrack() ?: return null
        tracks[index] = track
        primed[index] = true
        return track
    }

    private fun playOnWorker(volume: Float) {
        if (released) return
        try {
            val index = next
            next = (next + 1) % poolSize
            val track = ensureTrack(index) ?: return
            if (!primed[index]) {
                // Hiếm: track này chưa kịp nạp lại (gõ cực nhanh) - nạp ngay rồi phát.
                track.stop()
                track.reloadStaticData()
            }
            track.setVolume(volume)
            track.play()
            primed[index] = false
            handler.removeCallbacks(reprimeRunnables[index])
            handler.postDelayed(reprimeRunnables[index], reprimeDelayMs)
        } catch (ignored: Exception) {
            // Thiết bị âm thanh chưa sẵn sàng - bỏ qua 1 tiếng, không ảnh hưởng việc gõ.
        }
    }

    /** Chạy ngầm sau khi click phát xong: dừng + nạp lại buffer để lần bấm sau chỉ cần play(). */
    private fun reprime(index: Int) {
        if (released) return
        try {
            val track = tracks[index] ?: return
            track.stop()
            track.reloadStaticData()
            primed[index] = true
        } catch (ignored: Exception) {
        }
    }

    private fun createTrack(): AudioTrack? {
        return try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val sizeBytes = pcm.size * 2
            val track = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(sizeBytes)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build()
            } else {
                AudioTrack(attrs, format, sizeBytes, AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE)
            }
            track.write(pcm, 0, pcm.size)
            if (track.state == AudioTrack.STATE_INITIALIZED) track else {
                track.release()
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Tiếng click ~22ms: 1 nhịp nhiễu rất ngắn (tiếng "tạch") + sóng sin ~1.9kHz tắt nhanh. */
    private fun buildClickPcm(): ShortArray {
        val count = (sampleRate * 0.022).toInt()
        val out = ShortArray(count)
        for (i in 0 until count) {
            val t = i / sampleRate.toDouble()
            val tone = sin(2.0 * PI * 1900.0 * t) * exp(-t / 0.0045)
            val noise = (Random.nextDouble() * 2.0 - 1.0) * exp(-t / 0.0012)
            var s = tone * 0.75 + noise * 0.35
            if (i < 8) s *= i / 8.0 // vào dần trong ~0.2ms để không bị "bụp" đầu tiếng
            out[i] = (s.coerceIn(-1.0, 1.0) * 32000.0).toInt().toShort()
        }
        return out
    }

    private companion object {
        /** Tần số lấy mẫu gốc của đầu ra âm thanh máy (thường 48000). */
        fun nativeSampleRate(context: Context): Int {
            return try {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000
            } catch (e: Exception) {
                48000
            }
        }
    }
}
