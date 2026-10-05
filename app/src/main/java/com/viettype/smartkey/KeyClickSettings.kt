package com.viettype.smartkey

import android.content.Context
import android.media.AudioManager
import android.provider.Settings

/**
 * Bật/tắt + chỉnh âm lượng tiếng bấm phím (0..100%, 0% = tắt hẳn).
 *
 * Dùng tiếng bấm phím CỦA HỆ THỐNG (AudioManager.playSoundEffect) - giống Gboard / bàn phím gốc
 * Android: hệ thống tự phát nên ít trễ nhất và không cần tự quản lý âm thanh. Hệ quả: tiếng chỉ
 * kêu khi máy bật "Âm thanh khi chạm" trong Cài đặt hệ thống ([isSystemTouchSoundEnabled]), và thanh
 * kéo ở đây là hệ số nhân thêm lên trên mức âm lượng hệ thống.
 */
object KeyClickSettings {

    private const val PREFS_NAME = "key_click_settings"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_VOLUME_PERCENT = "volume_percent"

    private const val DEFAULT_VOLUME = 60

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getVolumePercent(context: Context): Int = prefs(context).getInt(KEY_VOLUME_PERCENT, DEFAULT_VOLUME)

    fun setVolumePercent(context: Context, percent: Int) {
        prefs(context).edit().putInt(KEY_VOLUME_PERCENT, percent.coerceIn(0, 100)).apply()
    }

    /** Âm lượng thực dùng khi gõ: 0 nếu đang tắt, ngược lại là mức người dùng chọn. */
    fun effectiveVolumePercent(context: Context): Int =
        if (isEnabled(context)) getVolumePercent(context) else 0

    /** Máy có đang bật "Âm thanh khi chạm" trong Cài đặt hệ thống không (không bật thì sẽ không có tiếng). */
    fun isSystemTouchSoundEnabled(context: Context): Boolean = try {
        Settings.System.getInt(context.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 1) != 0
    } catch (e: Exception) {
        true
    }

    /** Phát tiếng bấm phím của hệ thống với âm lượng [volumePercent] (0 = không phát). */
    fun play(context: Context, volumePercent: Int, effect: Int = AudioManager.FX_KEYPRESS_STANDARD) {
        if (volumePercent <= 0) return
        try {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val linear = volumePercent.coerceIn(0, 100) / 100f
            // Hệ số âm lượng của playSoundEffect nên tính theo đường cong (bình phương) cho dễ chỉnh.
            audio.playSoundEffect(effect, linear * linear)
        } catch (ignored: Exception) {
            // Audio chưa sẵn sàng - bỏ qua 1 tiếng, không ảnh hưởng việc gõ.
        }
    }
}
