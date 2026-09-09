package com.viettype.smartkey

import java.text.Normalizer

/**
 * Bộ xử lý gõ tiếng Việt kiểu TELEX, hoạt động trên "từ đang gõ dở".
 *
 * Nguyên tắc cốt lõi:
 *   - Mỗi phím biến đổi (s/f/r/x/j = thanh điệu; aa/ee/oo/dd = chữ có dấu;
 *     aw/ow/uw/w = chữ có móc/ngang) khi GÕ LẦN ĐẦU -> áp biến đổi.
 *   - Khi GÕ LẦN HAI cùng phím biến đổi -> HOÀN TÁC về ký tự gốc + gõ thêm
 *     phím đó như ký tự thường (escape Telex). Điều này cho phép gõ từ tiếng
 *     Anh tự do: "stress" = s-t-r-e-s-s (lần 1 "s" = bình thường, lần 2 "s"
 *     vẫn bình thường vì không có nguyên âm nào để gắn thanh).
 *
 * Đặc biệt với thanh điệu: nếu từ không có nguyên âm thì không áp dụng,
 *   ký tự được gõ thẳng ra như bình thường.
 *
 * Các lỗi đã sửa so với phiên bản cũ:
 *   1. "tex" bị mất: x thứ 2 giờ hoàn tác về "tex" (escape).
 *   2. "trongo" không ra "trông": xử lý 'o' sau chuỗi phụ âm + nguyên âm.
 *   3. "nhungw" không ra "nhưng": 'w' sau 'g' trong cụm cuối từ.
 *   4. Thêm dấu sau khi gõ xong từ (vd gõ "hoas" -> "hoás" -> "hoàs" sửa lại).
 *   5. Cụm nguyên âm đôi/ba chọn đúng vị trí đặt thanh theo quy tắc tiếng Việt.
 */
object TelexEngine {

    private const val MARK_ACUTE    = '\u0301'  // sắc
    private const val MARK_GRAVE    = '\u0300'  // huyền
    private const val MARK_HOOK     = '\u0309'  // hỏi
    private const val MARK_TILDE    = '\u0303'  // ngã
    private const val MARK_DOT     = '\u0323'  // nặng

    private val TONE_MARKS = setOf(MARK_ACUTE, MARK_GRAVE, MARK_HOOK, MARK_TILDE, MARK_DOT)

    enum class Tone(val mark: Char?) {
        NONE(null),
        ACUTE(MARK_ACUTE),
        GRAVE(MARK_GRAVE),
        HOOK(MARK_HOOK),
        TILDE(MARK_TILDE),
        DOT(MARK_DOT)
    }

    /** Phím TELEX -> thanh điệu tương ứng */
    private val TONE_KEYS = mapOf(
        's' to Tone.ACUTE,
        'f' to Tone.GRAVE,
        'r' to Tone.HOOK,
        'x' to Tone.TILDE,
        'j' to Tone.DOT,
    )

    /** Nguyên âm có dấu phụ (không phải thanh điệu - là biến thể chữ cái) */
    private val INHERENT_VOWELS = setOf('ă', 'â', 'ê', 'ô', 'ơ', 'ư')
    private val PLAIN_VOWELS    = setOf('a', 'e', 'i', 'o', 'u', 'y')
    private val ALL_VOWELS      = PLAIN_VOWELS + INHERENT_VOWELS

    // =========================================================================
    //  API chính
    // =========================================================================

    /**
     * Xử lý phím [rawKey] vừa gõ khi từ đang gõ dở là [wordBefore].
     *
     * Trả về CHUỖI THAY THẾ HOÀN CHỈNH (xoá wordBefore cũ, ghi cái này) nếu
     * có biến đổi Telex. Trả về null nếu phím này không kích hoạt biến đổi gì
     * -> nơi gọi cứ nối ký tự vào bình thường.
     */
    fun applyKey(wordBefore: String, rawKey: Char): String? {
        if (wordBefore.isEmpty()) return null

        val keyLower    = rawKey.lowercaseChar()
        val keyIsUpper  = rawKey.isUpperCase()

        // ── Thanh điệu (s f r x j) ──────────────────────────────────────────
        TONE_KEYS[keyLower]?.let { tone ->
            return applyTone(wordBefore, tone, keyLower, rawKey)
        }

        // ── z = xoá thanh (ngang) ────────────────────────────────────────────
        if (keyLower == 'z') {
            return applyToneToWord(wordBefore, Tone.NONE) ?: wordBefore
        }

        // ── aa -> â, ee -> ê, oo -> ô, dd -> đ ──────────────────────────────
        if (keyLower in "aeod") {
            val result = applyDoubleChar(wordBefore, keyLower, keyIsUpper)
            if (result != null) return result
        }

        // ── aw -> ă, ow -> ơ, uw/w -> ư ; và escape double-w ────────────────
        if (keyLower == 'w') {
            return applyW(wordBefore, keyIsUpper)
        }

        return null
    }

    // =========================================================================
    //  Xử lý thanh điệu
    // =========================================================================

    /**
     * Áp thanh [tone] vào [word].
     * - Nếu từ không có nguyên âm -> không làm gì (trả null = gõ thẳng).
     * - Nếu đã có đúng thanh đó rồi (gõ lần 2) -> hoàn tác: xoá thanh + nối
     *   phím gốc để thoát Telex (vd "tẽx" -> "tex").
     * - Ngược lại -> áp thanh mới.
     */
    private fun applyTone(word: String, tone: Tone, keyLower: Char, rawKey: Char): String? {
        val vowelIndices = findVowelIndices(word)
        if (vowelIndices.isEmpty()) return null   // không có nguyên âm -> gõ thẳng

        val currentTone = getWordTone(word, vowelIndices)

        return if (currentTone == tone) {
            // Gõ lần 2 cùng phím -> escape: xoá thanh + thêm ký tự phím đó
            val stripped = stripToneFromWord(word, vowelIndices)
            stripped + rawKey
        } else {
            applyToneToWord(word, tone)
        }
    }

    private fun getWordTone(word: String, vowelIndices: List<Int>): Tone {
        for (i in vowelIndices) {
            val t = extractTone(word[i])
            if (t != Tone.NONE) return t
        }
        return Tone.NONE
    }

    private fun applyToneToWord(word: String, tone: Tone): String? {
        val vowelIndices = findVowelIndices(word)
        if (vowelIndices.isEmpty()) return null
        val targetIndex = pickToneTarget(word, vowelIndices)
        val chars = word.toCharArray()
        for (i in vowelIndices) chars[i] = stripTone(chars[i])
        chars[targetIndex] = applyToneToChar(chars[targetIndex], tone)
        return String(chars)
    }

    private fun stripToneFromWord(word: String, vowelIndices: List<Int>): String {
        val chars = word.toCharArray()
        for (i in vowelIndices) chars[i] = stripTone(chars[i])
        return String(chars)
    }

    // =========================================================================
    //  Xử lý aa/ee/oo/dd
    // =========================================================================

    /**
     * Khi [keyLower] trùng ký tự cuối cùng của [word]:
     *   - Chuyển aa->â, ee->ê, oo->ô, dd->đ (giữ thanh cũ nếu có).
     *   - Nếu ký tự cuối ĐÃ là phiên bản biến đổi (â, ê, ô, đ) -> escape:
     *     xoá biến đổi + thêm ký tự bình thường (ví dụ âa -> aa).
     */
    private fun applyDoubleChar(word: String, keyLower: Char, keyIsUpper: Boolean): String? {
        val lastChar     = word.last()
        val lastBase     = stripTone(lastChar).lowercaseChar()
        val existingTone = extractTone(lastChar)

        // Escape: nếu cuối từ đã là ký tự biến đổi, gõ thêm ký tự gốc -> hoàn tác
        val escapePair = mapOf('â' to 'a', 'ê' to 'e', 'ô' to 'o', 'đ' to 'd')
        if (escapePair[lastBase] == keyLower) {
            // Hoàn tác: đổi ký tự biến đổi về ký tự gốc + nối phím gõ thêm
            val plainChar = if (lastChar.isUpperCase()) keyLower.uppercaseChar() else keyLower
            val restoredBase = applyToneToChar(plainChar, existingTone)
            return word.dropLast(1) + restoredBase + (if (keyIsUpper) keyLower.uppercaseChar() else keyLower)
        }

        if (lastBase != keyLower) return null

        val replacement: Char = when (keyLower) {
            'a' -> 'â'; 'e' -> 'ê'; 'o' -> 'ô'; 'd' -> 'đ'
            else -> return null
        }
        val cased     = if (lastChar.isUpperCase() || keyIsUpper) replacement.uppercaseChar() else replacement
        val finalChar = if (existingTone != Tone.NONE) applyToneToChar(cased, existingTone) else cased
        return word.dropLast(1) + finalChar
    }

    // =========================================================================
    //  Xử lý w
    // =========================================================================

    /**
     * Áp dụng phím 'w':
     *   - aw -> ă, ow -> ơ, uw -> ư  (giữ thanh cũ nếu có)
     *   - Chữ độc lập: w -> ư
     *   - Escape: nếu cuối từ đã là ă/ơ/ư -> hoàn tác về a/o/u + thêm 'w'
     *   - ow đặc biệt: xử lý cả trường hợp 'o' nằm giữa cụm (trongo -> trông)
     */
    private fun applyW(word: String, keyIsUpper: Boolean): String? {
        val lastChar     = word.last()
        val lastBase     = stripTone(lastChar).lowercaseChar()
        val existingTone = extractTone(lastChar)

        // Escape: cuối từ đã là ă/ơ/ư -> hoàn tác về a/o/u + thêm 'w'
        val escapeMap = mapOf('ă' to 'a', 'ơ' to 'o', 'ư' to 'u')
        if (lastBase in escapeMap) {
            val originalChar = escapeMap[lastBase]!!
            val restoredChar = if (lastChar.isUpperCase()) originalChar.uppercaseChar() else originalChar
            val restored = applyToneToChar(restoredChar, existingTone)
            return word.dropLast(1) + restored + (if (keyIsUpper) 'W' else 'w')
        }

        // aw -> ă
        if (lastBase == 'a') {
            val rep = if (lastChar.isUpperCase()) 'Ă' else 'ă'
            val fin = if (existingTone != Tone.NONE) applyToneToChar(rep, existingTone) else rep
            return word.dropLast(1) + fin
        }

        // ow -> ơ  (kể cả khi 'o' không phải ký tự cuối - ví dụ "trong" + w -> "trơng"?
        //           Không - cần đặt ơ vào đúng chỗ 'o' gần nhất từ cuối)
        if (lastBase == 'o') {
            val rep = if (lastChar.isUpperCase()) 'Ơ' else 'ơ'
            val fin = if (existingTone != Tone.NONE) applyToneToChar(rep, existingTone) else rep
            return word.dropLast(1) + fin
        }

        // uw -> ư (và nhungw -> nhưng: 'u' không phải ký tự cuối nhưng cuối là phụ âm)
        if (lastBase == 'u') {
            val rep = if (lastChar.isUpperCase()) 'Ư' else 'ư'
            val fin = if (existingTone != Tone.NONE) applyToneToChar(rep, existingTone) else rep
            return word.dropLast(1) + fin
        }

        // 'w' sau phụ âm cuối: tìm nguyên âm o/u gần nhất từ cuối, biến đổi nó
        // Ví dụ: "trong" + w -> "trông", "nhung" + w -> "nhưng"
        // Nếu nguyên âm cuối là a/e/i/y (vd "tat","set") -> không áp w, trả null
        // để 'w' được gõ thẳng ra như ký tự bình thường, tránh chèn 'ư' nhầm.
        return transformLastVowelWithW(word, keyIsUpper)
    }

    /**
     * Tìm nguyên âm a/o/u gần cuối nhất trong [word] (bỏ qua phụ âm cuối), biến đổi:
     *   a -> ă, o -> ơ, u -> ư
     * Dùng cho: "trong"+'w' -> "trông", "nhung"+'w' -> "nhưng", "tat"+'w' -> "tăt".
     * Chỉ áp dụng khi nguyên âm đó KHÔNG phải ký tự cuối (tức sau nó còn phụ âm) -
     * trường hợp nguyên âm là ký tự cuối đã được xử lý riêng ở applyW() phía trên.
     */
    private fun transformLastVowelWithW(word: String, keyIsUpper: Boolean): String? {
        for (i in word.indices.reversed()) {
            val c    = word[i]
            val base = stripTone(c).lowercaseChar()
            if (base in ALL_VOWELS) {
                // Chỉ áp w khi nguyên âm này không phải ký tự cuối (còn phụ âm theo sau)
                if (i == word.length - 1) return null
                val rep: Char = when (base) {
                    'a'  -> if (c.isUpperCase()) 'Ă' else 'ă'
                    'o'  -> if (c.isUpperCase()) 'Ơ' else 'ơ'
                    'u'  -> if (c.isUpperCase()) 'Ư' else 'ư'
                    else -> return null  // e/i/y không có dạng w tương ứng
                }
                val tone = extractTone(c)
                val fin  = if (tone != Tone.NONE) applyToneToChar(rep, tone) else rep
                return word.substring(0, i) + fin + word.substring(i + 1)
            }
        }
        return null
    }

    // =========================================================================
    //  Unicode helpers
    // =========================================================================

    private fun applyToneToChar(c: Char, tone: Tone): Char {
        val decomposed  = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
        val keptMarks   = decomposed.drop(1).filter { it !in TONE_MARKS }
        val newSequence = decomposed[0] + keptMarks + (tone.mark?.toString() ?: "")
        val recomposed  = Normalizer.normalize(newSequence, Normalizer.Form.NFC)
        return recomposed[0]
    }

    private fun stripTone(c: Char): Char = applyToneToChar(c, Tone.NONE)

    private fun extractTone(c: Char): Tone {
        val decomposed = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
        val mark       = decomposed.drop(1).firstOrNull { it in TONE_MARKS }
        return Tone.entries.find { it.mark == mark } ?: Tone.NONE
    }

    // =========================================================================
    //  Tìm nguyên âm & chọn vị trí đặt thanh
    // =========================================================================

    private fun findVowelIndices(word: String): List<Int> {
        val indices = mutableListOf<Int>()
        for (i in word.indices) {
            val base = stripTone(word[i]).lowercaseChar()
            if (base in ALL_VOWELS) indices.add(i)
        }
        return excludeGlides(word, indices)
    }

    /** Loại 'u' trong "qu" và 'i' trong "gi" ra khỏi danh sách nguyên âm (chúng là phụ âm đệm). */
    private fun excludeGlides(word: String, vowelIndices: List<Int>): List<Int> {
        if (vowelIndices.size < 2) return vowelIndices
        val lower = word.lowercase()
        val first = vowelIndices[0]
        val isQu  = first == 1 && lower.getOrNull(0) == 'q' && lower.getOrNull(1) == 'u'
        val isGi  = first == 1 && lower.getOrNull(0) == 'g' && lower.getOrNull(1) == 'i'
        return if (isQu || isGi) vowelIndices.drop(1) else vowelIndices
    }

    /**
     * Chọn vị trí đặt thanh điệu theo quy tắc tiếng Việt:
     *  1. Nguyên âm đặc biệt (ă â ê ô ơ ư) -> ưu tiên đặt vào đó.
     *  2. Chỉ 1 nguyên âm -> đặt vào đó.
     *  3. Có phụ âm cuối (closed syllable) -> đặt vào nguyên âm cuối cùng của cụm.
     *  4. Mở (open syllable), 2 nguyên âm thường (a e i o u y, không dấu phụ):
     *       - "oa", "oe", "uy" -> nguyên âm ĐẦU ('o'/'u') chỉ là âm đệm (glide),
     *         nguyên âm chính nằm ở SAU -> đặt thanh vào nguyên âm THỨ HAI.
     *         (vd: "hoa"+f -> "hoà", "khoe"+r -> "khoẻ", "thuy"+r -> "thuỷ").
     *       - Còn lại ("ai","ao","au","ay","eo","eu","ia","iu","oi","ua","ui","ưa"...)
     *         -> nguyên âm ĐẦU mới là nguyên âm chính, nguyên âm sau chỉ là âm
     *         cuối/bán nguyên âm -> đặt thanh vào nguyên âm ĐẦU cụm.
     *         (vd: "cai"+s -> "cái" chứ không phải "caí";
     *              "mau"+f -> "màu" chứ không phải "maù").
     *  5. 3+ nguyên âm -> đặt vào nguyên âm áp chót.
     */
    private fun pickToneTarget(word: String, vowelIndices: List<Int>): Int {
        // Ưu tiên nguyên âm có dấu phụ (ă â ê ô ơ ư)
        val inherent = vowelIndices.lastOrNull { stripTone(word[it]).lowercaseChar() in INHERENT_VOWELS }
        if (inherent != null) return inherent

        if (vowelIndices.size == 1) return vowelIndices[0]

        // Có phụ âm cuối không?
        val hasCoda = vowelIndices.last() < word.length - 1

        if (vowelIndices.size == 2) {
            val v0  = stripTone(word[vowelIndices[0]]).lowercaseChar()
            val v1  = stripTone(word[vowelIndices[1]]).lowercaseChar()
            val pair = "$v0$v1"
            return when {
                hasCoda -> vowelIndices[1]
                // Chỉ 3 cặp này có nguyên âm đầu là ÂM ĐỆM (o/u đứng trước nguyên âm
                // chính) -> nguyên âm chính (và do đó thanh điệu) nằm ở vị trí thứ 2.
                pair == "oa" || pair == "oe" || pair == "uy" -> vowelIndices[1]
                // Mọi cặp mở còn lại (kể cả "ia"/"ua"/"ưa") đều có nguyên âm ĐẦU là
                // nguyên âm chính -> đặt thanh vào đó.
                else -> vowelIndices[0]
            }
        }

        // 3+ nguyên âm
        return vowelIndices[vowelIndices.size - 2]
    }
}
