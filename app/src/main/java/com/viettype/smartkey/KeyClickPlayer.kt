package com.viettype.smartkey

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.HandlerThread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Phát tiếng "tách" mỗi lần bấm phím, có chỉnh được âm lượng 0..100%.
 *
 * Trước đây dùng AudioManager.playSoundEffect() nên chỉ kêu khi máy bật "Âm thanh khi chạm"
 * trong Cài đặt hệ thống (rất nhiều máy tắt sẵn) và không chỉnh được độ lớn. Giờ tự phát 1 tiếng
 * click ngắn (tự sinh trong bộ nhớ, không cần file âm thanh) qua AudioTrack - không phụ thuộc
 * cài đặt "âm thanh chạm" của hệ thống, nhưng vẫn theo âm lượng MEDIA của máy.
 *
 * Phát trên luồng nền riêng để không làm trễ thao tác chạm phím.
 */
class KeyClickPlayer {

    private val sampleRate = 44100
    private val poolSize = 3 // xoay vòng nhiều track để gõ nhanh các tiếng không cắt nhau
    private val pcm: ShortArray = buildClickPcm()
    private val tracks: Array<AudioTrack?> = arrayOfNulls(poolSize)
    private var next = 0

    private val thread = HandlerThread("key-click").apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile
    private var released = false

    /** volumePercent <= 0: không phát gì. */
    fun play(volumePercent: Int) {
        if (released || volumePercent <= 0) return
        val volume = volumePercent.coerceIn(0, 100) / 100f
        handler.post { playOnWorker(volume) }
    }

    fun release() {
        released = true
        handler.post {
            for (i in tracks.indices) {
                try { tracks[i]?.release() } catch (ignored: Exception) { }
                tracks[i] = null
            }
        }
        thread.quitSafely()
    }

    private fun playOnWorker(volume: Float) {
        if (released) return
        try {
            val index = next
            next = (next + 1) % poolSize
            val track = tracks[index] ?: createTrack()?.also { tracks[index] = it } ?: return
            // Track tĩnh sau khi phát xong vẫn ở trạng thái PLAYING - phải stop() + nạp lại từ đầu.
            if (track.playState != AudioTrack.PLAYSTATE_STOPPED) track.stop()
            track.reloadStaticData()
            track.setVolume(volume)
            track.play()
        } catch (ignored: Exception) {
            // Thiết bị âm thanh chưa sẵn sàng - bỏ qua 1 tiếng, không ảnh hưởng việc gõ.
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
            val track = AudioTrack(
                attrs, format, pcm.size * 2, AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE
            )
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
}
