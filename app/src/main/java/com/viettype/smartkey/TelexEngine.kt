package com.viettype.smartkey

import java.text.Normalizer

/**
 * Bộ xử lý gõ tiếng Việt kiểu TELEX, hoạt động trên "từ đang gõ dở" (chuỗi ký tự
 * kể từ sau khoảng trắng/dấu câu gần nhất).
 *
 * Cách tiếp cận: thay vì dùng 1 bảng tra cứu khổng lồ liệt kê sẵn từng ký tự có
 * dấu (bảng chữ cái Việt có 134 tổ hợp nguyên âm+dấu), engine này dùng chuẩn hoá
 * Unicode NFD/NFC của chính nền tảng Java:
 *   - NFD (Normalization Form D) tách 1 ký tự có dấu thành CHỮ GỐC + các dấu phụ
 *     rời (vd 'ấ' tách thành 'a' + dấu mũ (^) + dấu sắc).
 *   - Muốn đổi dấu, chỉ cần: tách ra, XOÁ dấu THANH ĐIỆU cũ (nếu có) mà GIỮ NGUYÊN
 *     dấu MŨ/MÓC (ă, â, ê, ô, ơ, ư là biến thể CHỮ CÁI, không phải thanh điệu),
 *     rồi gắn dấu thanh điệu MỚI vào, cuối cùng NFC gộp lại thành 1 ký tự hoàn
 *     chỉnh. Cách này gọn hơn nhiều so với liệt kê tay từng trường hợp, và tự
 *     đúng luôn cho MỌI chữ cái mà không cần khai báo thủ công từng ký tự.
 */
object TelexEngine {

    private const val MARK_ACUTE = '\u0301'      // sắc
    private const val MARK_GRAVE = '\u0300'      // huyền
    private const val MARK_HOOK = '\u0309'       // hỏi
    private const val MARK_TILDE = '\u0303'      // ngã
    private const val MARK_DOT_BELOW = '\u0323'  // nặng

    private val TONE_MARKS = setOf(MARK_ACUTE, MARK_GRAVE, MARK_HOOK, MARK_TILDE, MARK_DOT_BELOW)

    enum class Tone(val mark: Char?) {
        NONE(null), ACUTE(MARK_ACUTE), GRAVE(MARK_GRAVE), HOOK(MARK_HOOK), TILDE(MARK_TILDE), DOT_BELOW(MARK_DOT_BELOW)
    }

    private val TONE_KEYS = mapOf(
        's' to Tone.ACUTE, 'f' to Tone.GRAVE, 'r' to Tone.HOOK, 'x' to Tone.TILDE, 'j' to Tone.DOT_BELOW,
    )

    private val INHERENT_MARK_VOWELS = setOf('ă', 'â', 'ê', 'ô', 'ơ', 'ư')
    private val PLAIN_VOWELS = setOf('a', 'e', 'i', 'o', 'u', 'y')
    private val ALL_VOWELS = PLAIN_VOWELS + INHERENT_MARK_VOWELS

    /**
     * Xử lý 1 phím vừa gõ, dựa trên [wordBefore] (từ đang gõ dở TRƯỚC phím này,
     * chưa gồm phím [rawKey]). Trả về CHUỖI THAY THẾ HOÀN CHỈNH cho cả từ nếu
     * phím này kích hoạt biến đổi (gộp dấu đôi, thêm 'w', hoặc thanh điệu) - trả
     * về null nếu phím này không có tác dụng đặc biệt, gọi nơi dùng cứ nối thẳng
     * ký tự vào bình thường.
     */
    fun applyKey(wordBefore: String, rawKey: Char): String? {
        if (wordBefore.isEmpty()) return null
        val keyLower = rawKey.lowercaseChar()
        val keyIsUpper = rawKey.isUpperCase()

        TONE_KEYS[keyLower]?.let { tone ->
            return applyToneToWord(wordBefore, tone)
        }

        if (keyLower == 'z') {
            val cleared = applyToneToWord(wordBefore, Tone.NONE)
            return cleared ?: wordBefore
        }

        if (keyLower in charArrayOf('a', 'e', 'o', 'd')) {
            val lastChar = wordBefore.last()
            if (lastChar.lowercaseChar() == keyLower) {
                val replacement = when (keyLower) {
                    'a' -> 'â'; 'e' -> 'ê'; 'o' -> 'ô'; 'd' -> 'đ'
                    else -> return null
                }
                val cased = if (lastChar.isUpperCase() || keyIsUpper) replacement.uppercaseChar() else replacement
                val existingTone = extractTone(lastChar)
                val finalChar = if (existingTone != Tone.NONE) applyToneToChar(cased, existingTone) else cased
                return wordBefore.dropLast(1) + finalChar
            }
        }

        if (keyLower == 'w') {
            val lastChar = wordBefore.last()
            val replacement = when (lastChar.lowercaseChar()) {
                'a' -> 'ă'; 'o' -> 'ơ'; 'u' -> 'ư'
                else -> null
            }
            if (replacement != null) {
                val cased = if (lastChar.isUpperCase()) replacement.uppercaseChar() else replacement
                val existingTone = extractTone(lastChar)
                val finalChar = if (existingTone != Tone.NONE) applyToneToChar(cased, existingTone) else cased
                return wordBefore.dropLast(1) + finalChar
            }
            return wordBefore + if (keyIsUpper) 'Ư' else 'ư'
        }

        return null
    }

    private fun applyToneToWord(word: String, tone: Tone): String? {
        val vowelIndices = findVowelIndices(word)
        if (vowelIndices.isEmpty()) return null
        val targetIndex = pickToneTargetIndex(word, vowelIndices)

        val chars = word.toCharArray()
        for (i in vowelIndices) chars[i] = stripTone(chars[i])
        chars[targetIndex] = applyToneToChar(chars[targetIndex], tone)
        return String(chars)
    }

    private fun applyToneToChar(c: Char, tone: Tone): Char {
        val decomposed = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
        val keptMarks = decomposed.drop(1).filter { it !in TONE_MARKS }
        val newSequence = decomposed[0] + keptMarks + (tone.mark?.toString() ?: "")
        val recomposed = Normalizer.normalize(newSequence, Normalizer.Form.NFC)
        return recomposed[0]
    }

    private fun stripTone(c: Char): Char = applyToneToChar(c, Tone.NONE)

    private fun extractTone(c: Char): Tone {
        val decomposed = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
        val foundMark = decomposed.drop(1).firstOrNull { it in TONE_MARKS }
        return Tone.entries.find { it.mark == foundMark } ?: Tone.NONE
    }

    private fun findVowelIndices(word: String): List<Int> {
        val indices = mutableListOf<Int>()
        for (i in word.indices) {
            val base = stripTone(word[i]).lowercaseChar()
            if (base in ALL_VOWELS) indices.add(i)
        }
        return removeGlideConsonantVowels(word, indices)
    }

    private fun removeGlideConsonantVowels(word: String, vowelIndices: List<Int>): List<Int> {
        if (vowelIndices.size < 2) return vowelIndices
        val lower = word.lowercase()
        val firstVowelPos = vowelIndices[0]
        val isQu = firstVowelPos == 1 && lower.getOrNull(0) == 'q' && lower.getOrNull(1) == 'u'
        val isGi = firstVowelPos == 1 && lower.getOrNull(0) == 'g' && lower.getOrNull(1) == 'i'
        return if (isQu || isGi) vowelIndices.drop(1) else vowelIndices
    }

    private fun pickToneTargetIndex(word: String, vowelIndices: List<Int>): Int {
        val inherentIndex = vowelIndices.lastOrNull { stripTone(word[it]).lowercaseChar() in INHERENT_MARK_VOWELS }
        if (inherentIndex != null) return inherentIndex

        if (vowelIndices.size == 1) return vowelIndices[0]

        val hasCoda = vowelIndices.last() < word.length - 1

        if (vowelIndices.size == 2) {
            val pair = "${word[vowelIndices[0]].lowercaseChar()}${word[vowelIndices[1]].lowercaseChar()}"
            return when {
                hasCoda -> vowelIndices[1]
                pair == "ia" || pair == "ua" || pair == "ưa" -> vowelIndices[0]
                else -> vowelIndices[1]
            }
        }

        return vowelIndices[vowelIndices.size - 2]
    }
}
