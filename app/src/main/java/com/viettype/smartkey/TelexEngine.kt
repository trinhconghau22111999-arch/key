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
        // Nếu từ có thanh điệu -> z xoá thanh đó (về thanh ngang). Nếu từ KHÔNG có
        // thanh nào để xoá (hoặc không có nguyên âm) -> applyToneToWord() trả về y
        // hệt wordBefore (không đổi gì) -> trước đây bị coi như "đã xử lý xong" nên
        // chữ 'z' biến mất không dấu vết (gõ mà không thấy chữ nào ra). Giờ trong
        // trường hợp đó tự chèn thẳng ký tự 'z' vào cuối từ, giống hệt gõ 1 chữ cái
        // thường (không có Telex nào cần escape ở đây vì z không dùng để tạo dấu).
        if (keyLower == 'z') {
            val stripped = applyToneToWord(wordBefore, Tone.NONE)
            return if (stripped != null && stripped != wordBefore) {
                stripped
            } else {
                wordBefore + rawKey
            }
        }

        // ── aa -> â, ee -> ê, oo -> ô, dd -> đ ──────────────────────────────
        if (keyLower in "aeod") {
            val result = applyDoubleChar(wordBefore, keyLower, keyIsUpper)
            if (result != null) return result
            // SUA LOI (nguoi dung phan anh: go "naua" khong ra "nâu"): TRUOC
            // DAY applyDoubleChar() CHi nhan doi khi 2 chu lien tiep NGAY
            // SAT nhau (vd "naa"). Nhung "nâu" go telex thuong la "n,a,u,a" -
            // chu 'a' thu 2 KHONG dung ngay sau chu 'a' dau (co 'u' xen
            // giua) vi nguoi go thuong go xong ca cum nguyen am "au" roi moi
            // bam THEM 'a' de "nhan doi nguoc". Ap dung CHi cho a/e/o (khong
            // ap dung cho 'd' vi đ luon la chu dau am tiet, khong co tinh
            // huong go rieng le nhu vay) - xem [transformLastVowelWithDoubleLetter].
            if (keyLower in "aeo") {
                val deferred = transformLastVowelWithDoubleLetter(wordBefore, keyLower, keyIsUpper)
                if (deferred != null) return deferred
            }
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

    /**
     * Tìm ký tự GẦN CUỐI TỪ nhất có gốc (bỏ dấu, chữ thường) TRÙNG với
     * [keyLower] ('a'/'e'/'o') - có thể có ký tự khác (nguyên âm hoặc phụ âm)
     * xen giữa nó và cuối từ, KHÔNG cần liền kề - rồi biến đổi ký tự đó
     * (a->â, e->ê, o->ô), KHÔNG nối thêm ký tự vừa gõ (giống hệt cách nhân
     * đôi liền kề hoạt động: "aa" -> 1 chữ "â" duy nhất).
     *
     * Dùng cho trường hợp gõ nhân đôi KHÔNG liền kề, ví dụ "nau" + 'a' (lần
     * 2) -> "nâu" (chữ 'a' đầu tiên được biến đổi, dù có 'u' xen giữa).
     *
     * Chỉ khớp đúng CHỮ GỐC chưa biến đổi (vd 'a' thường) - KHÔNG khớp với
     * chính dạng đã có móc/mũ (â/ê/ô), tránh biến đổi lặp lại ký tự đã xong.
     */
    private fun transformLastVowelWithDoubleLetter(word: String, keyLower: Char, keyIsUpper: Boolean): String? {
        val replacement: Char = when (keyLower) {
            'a' -> 'â'; 'e' -> 'ê'; 'o' -> 'ô'
            else -> return null
        }
        for (i in word.indices.reversed()) {
            val c    = word[i]
            val base = stripTone(c).lowercaseChar()
            if (base == keyLower) {
                val cased = if (c.isUpperCase() || keyIsUpper) replacement.uppercaseChar() else replacement
                val tone  = extractTone(c)
                val fin   = if (tone != Tone.NONE) applyToneToChar(cased, tone) else cased
                return word.substring(0, i) + fin + word.substring(i + 1)
            }
        }
        return null
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
            val chars = word.toCharArray()
            chars[chars.size - 1] = fin
            // SUA LOI (nguoi dung phan anh: "phuongw" ra "phuơng" thay vi
            // "phương"): cum "uo" bien thanh "ươ" phai doi CA 2 chu (u->ư VA
            // o->ơ) chu khong chi rieng o->ơ - xem [alsoConvertPrecedingUIfNeeded].
            alsoConvertPrecedingUIfNeeded(chars, chars.size - 1)
            return String(chars)
        }

        // uw -> ư (và nhungw -> nhưng: 'u' không phải ký tự cuối nhưng cuối là phụ âm)
        if (lastBase == 'u') {
            val chars = word.toCharArray()
            val lastIdx = chars.size - 1
            // SUA LOI (nguoi dung phan anh: "luuw" ra "luư" thay vi "lưu"):
            // neu 2 chu 'u' dung LIEN TIEP nhau ("uu"), cum nay phai thanh
            // "ưu" (chu U DAU chuyen thanh ư, chu u SAU giu nguyen) - TRUOC
            // DAY code luon doi CHU CUOI CUNG, sai thu tu am tiet thanh "uư".
            val prevIsU = lastIdx > 0 && stripTone(chars[lastIdx - 1]).lowercaseChar() == 'u'
            if (prevIsU) {
                val prevChar = chars[lastIdx - 1]
                val rep = if (prevChar.isUpperCase()) 'Ư' else 'ư'
                val tone = extractTone(prevChar)
                chars[lastIdx - 1] = if (tone != Tone.NONE) applyToneToChar(rep, tone) else rep
                return String(chars)
            }
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
     * Sau khi đã chuyển 1 nguyên âm tại [targetIndex] trong [chars] (chính nó
     * là 'o' hoặc 'u') sang dạng có móc (ơ/ư) qua phím w, kiểm tra ký tự
     * NGAY TRƯỚC đó - nếu là 'u' (chưa biến đổi) thì chuyển LUÔN nó thành ư,
     * tạo thành đúng 2 cụm nguyên âm kép cần cả 2 chữ khi dùng 'w':
     * "uo" -> "ươ" (vd "phuong"+w -> "phương") và "uu" -> "ưu" (trường hợp
     * "uu" đứng giữa từ, có phụ âm theo sau). Hàm này SỬA TRỰC TIẾP trên
     * mảng [chars] (không trả về giá trị).
     */
    private fun alsoConvertPrecedingUIfNeeded(chars: CharArray, targetIndex: Int) {
        if (targetIndex <= 0) return
        val prevChar = chars[targetIndex - 1]
        val prevBase = stripTone(prevChar).lowercaseChar()
        if (prevBase == 'u') {
            val rep = if (prevChar.isUpperCase()) 'Ư' else 'ư'
            val tone = extractTone(prevChar)
            chars[targetIndex - 1] = if (tone != Tone.NONE) applyToneToChar(rep, tone) else rep
        }
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
                val chars = word.toCharArray()
                chars[i] = fin
                // SUA LOI (nguoi dung phan anh: "phuongw" ra "phuơng" thay vi
                // "phương"): cum "uo" (vd "phuong") phai doi CA 2 chu u->ư
                // VA o->ơ - khong chi rieng chu tim thay. Ap dung ca khi chu
                // tim thay la 'o' (uo->ươ) lan 'u' (uu->ưu, truong hop hiem
                // co phu am theo sau) de nhat quan voi 2 nhanh truc tiep o
                // tren ([applyW]).
                if (base == 'o' || base == 'u') {
                    alsoConvertPrecedingUIfNeeded(chars, i)
                }
                return String(chars)
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
