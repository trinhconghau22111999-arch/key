package com.viettype.smartkey

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

    /** Kết quả xử lý 1 phím Telex. [newWord] là chuỗi thay thế hoàn chỉnh cho cả từ đang gõ.
     *  [wasEscape] = true khi đây là 1 lần "escape" (gõ LẶP LẠI phím biến đổi để HOÀN TÁC về
     *  chữ gốc - dấu hiệu người dùng KHÔNG muốn từ này tiếp tục biến đổi kiểu tiếng Việt nữa,
     *  ví dụ đang gõ xen từ tiếng Anh/tên riêng). Nơi gọi (SmartKeyboardService) dùng cờ này để
     *  nhớ "từ hiện tại đã escape", tắt hẳn Telex cho phần CÒN LẠI của từ đó - xem giải thích
     *  đầy đủ ở [applyKey]. */
    data class TelexResult(val newWord: String, val wasEscape: Boolean)

    /**
     * Xử lý phím [rawKey] vừa gõ khi từ đang gõ dở là [wordBefore].
     *
     * Trả về [TelexResult] (chuỗi thay thế hoàn chỉnh + có phải escape hay không) nếu có biến
     * đổi Telex. Trả về null nếu phím này không kích hoạt biến đổi gì -> nơi gọi cứ nối ký tự
     * vào bình thường.
     *
     * SỬA LỖI (người dùng phản ánh: gõ "ngông" -> gõ thêm "o" ra "ngongo" [đã sửa ở lần trước]
     * -> gõ thêm "f" thì phải ra "ngongof" chứ không phải "ngòngo" - tức KHÔNG được bỏ dấu nữa):
     * Chữ 'f' (thanh huyền) trước đây LUÔN cố áp thanh điệu vào 1 nguyên âm nào đó tìm thấy
     * trong cả TỪ, không quan tâm từ đó có còn "giống tiếng Việt" hay không sau khi người dùng
     * đã escape (thoát Telex) 1 phần của nó - "ngongo" không phải 1 âm tiết tiếng Việt hợp lệ
     * (2 nguyên âm 'o' tách rời bởi "ng" ở giữa, không phải 1 cụm nguyên âm), nhưng
     * pickToneTarget() vẫn máy móc chọn đại 1 nguyên âm ('o' đầu) để gắn dấu vào, cho ra kết
     * quả vô nghĩa "ngòngo". Việc TRẢ VỀ [TelexResult] có cờ [TelexResult.wasEscape] ở đây cho
     * phép nơi gọi GHI NHỚ "đã escape trong từ này" và tự động BỎ QUA hẳn việc gọi [applyKey]
     * cho các phím tiếp theo trong CÙNG từ đó - không cần dạy hàm này "hiểu" thế nào là 1 âm
     * tiết tiếng Việt hợp lệ (phức tạp, dễ sai), chỉ cần 1 lần escape là đủ tín hiệu "từ này
     * không còn là tiếng Việt nữa, đừng động vào nữa".
     */
    fun applyKey(wordBefore: String, rawKey: Char): TelexResult? {
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
        // 'z' là phím XOÁ DẤU CHỦ ĐỘNG (không phải gõ lặp lại 1 phím biến đổi) nên
        // KHÔNG tính là escape - người gõ 'z' vẫn có thể đang gõ tiếp tiếng Việt.
        if (keyLower == 'z') {
            val stripped = applyToneToWord(wordBefore, Tone.NONE)
            return if (stripped != null && stripped != wordBefore) {
                TelexResult(stripped, wasEscape = false)
            } else {
                TelexResult(wordBefore + rawKey, wasEscape = false)
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
    private fun applyTone(word: String, tone: Tone, keyLower: Char, rawKey: Char): TelexResult? {
        val vowelIndices = findVowelIndices(word)
        if (vowelIndices.isEmpty()) return null   // không có nguyên âm -> gõ thẳng

        val currentTone = getWordTone(word, vowelIndices)

        if (currentTone == tone) {
            // Gõ lần 2 cùng phím -> escape: xoá thanh + thêm ký tự phím đó
            val stripped = stripToneFromWord(word, vowelIndices)
            return TelexResult(stripped + rawKey, wasEscape = true)
        }
        val applied = applyToneToWord(word, tone) ?: return null
        return TelexResult(applied, wasEscape = false)
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
    private fun applyDoubleChar(word: String, keyLower: Char, keyIsUpper: Boolean): TelexResult? {
        val lastChar     = word.last()
        val lastBase     = stripTone(lastChar).lowercaseChar()
        val existingTone = extractTone(lastChar)

        // Escape: nếu cuối từ đã là ký tự biến đổi, gõ thêm ký tự gốc -> hoàn tác
        val escapePair = mapOf('â' to 'a', 'ê' to 'e', 'ô' to 'o', 'đ' to 'd')
        if (escapePair[lastBase] == keyLower) {
            // Hoàn tác: đổi ký tự biến đổi về ký tự gốc + nối phím gõ thêm
            val plainChar = if (lastChar.isUpperCase()) keyLower.uppercaseChar() else keyLower
            val restoredBase = applyToneToChar(plainChar, existingTone)
            val result = word.dropLast(1) + restoredBase + (if (keyIsUpper) keyLower.uppercaseChar() else keyLower)
            return TelexResult(result, wasEscape = true)
        }

        if (lastBase != keyLower) return null

        val replacement: Char = when (keyLower) {
            'a' -> 'â'; 'e' -> 'ê'; 'o' -> 'ô'; 'd' -> 'đ'
            else -> return null
        }
        val cased     = if (lastChar.isUpperCase() || keyIsUpper) replacement.uppercaseChar() else replacement
        val finalChar = if (existingTone != Tone.NONE) applyToneToChar(cased, existingTone) else cased
        return TelexResult(word.dropLast(1) + finalChar, wasEscape = false)
    }

    /**
     * Tìm ký tự GẦN CUỐI TỪ nhất có LIÊN QUAN tới [keyLower] ('a'/'e'/'o') - có thể có ký tự
     * khác (nguyên âm hoặc phụ âm) xen giữa nó và cuối từ, KHÔNG cần liền kề - rồi xử lý theo
     * 1 trong 2 trường hợp:
     *
     *   1. Ký tự đó là CHỮ GỐC chưa biến đổi (a/e/o, có thể mang thanh như à/è/ò) -> NHÂN ĐÔI
     *      thành â/ê/ô (giữ nguyên thanh cũ), KHÔNG nối thêm ký tự vừa gõ - giống hệt cách
     *      nhân đôi liền kề hoạt động ("aa" -> 1 chữ "â" duy nhất).
     *      Dùng cho trường hợp gõ nhân đôi KHÔNG liền kề, ví dụ "nau" + 'a' (lần 2) -> "nâu".
     *
     *   2. Ký tự đó ĐÃ LÀ dạng biến đổi RỒI (â/ê/ô, có thể mang thanh như ồ/ấ/ế...) - tức đây
     *      là lần gõ THỨ 3 (không liền kề) cho đúng nguyên âm đó -> ESCAPE: hoàn tác ký tự đó
     *      về chữ gốc (giữ nguyên thanh cũ) NGAY TẠI VỊ TRÍ CŨ, rồi nối thêm CHÍNH ký tự vừa gõ
     *      vào CUỐI TỪ - đúng quy ước "gõ lần 3 = hoàn tác + gõ thêm" đã dùng ở applyDoubleChar()
     *      cho trường hợp liền kề, áp dụng tương tự cho trường hợp KHÔNG liền kề.
     *      SỬA LỖI (người dùng phản ánh: gõ "ngông" rồi gõ thêm "o" phải ra "ngongo" chứ không
     *      phải "ngôngo"): TRƯỚC ĐÂY trường hợp 2 này hoàn toàn KHÔNG được xử lý - vòng lặp chỉ
     *      so khớp trường hợp 1 (chữ gốc), gặp chữ ĐÃ biến đổi (như 'ô' trong "ngông") thì bỏ
     *      qua im lặng, không làm gì - khiến cả hàm trả về null, rồi bị coi như "không có Telex
     *      nào áp dụng" nên chữ 'o' vừa gõ chỉ được nối thẳng vào cuối như ký tự thường, để lại
     *      nguyên chữ 'ô' cũ không hoàn tác - ra "ngôngo" thay vì "ngongo".
     */
    private fun transformLastVowelWithDoubleLetter(word: String, keyLower: Char, keyIsUpper: Boolean): TelexResult? {
        val replacement: Char = when (keyLower) {
            'a' -> 'â'; 'e' -> 'ê'; 'o' -> 'ô'
            else -> return null
        }
        for (i in word.indices.reversed()) {
            val c = word[i]
            val bareLower = stripTone(c).lowercaseChar() // chữ gốc, bỏ thanh điệu, chữ thường

            if (bareLower == keyLower) {
                // Trường hợp 1: chữ gốc CHƯA biến đổi - nhân đôi thành â/ê/ô tại chỗ.
                val cased = if (c.isUpperCase() || keyIsUpper) replacement.uppercaseChar() else replacement
                val tone  = extractTone(c)
                val fin   = if (tone != Tone.NONE) applyToneToChar(cased, tone) else cased
                return TelexResult(word.substring(0, i) + fin + word.substring(i + 1), wasEscape = false)
            }

            if (bareLower == replacement) {
                // Trường hợp 2 (MỚI SỬA): chữ ĐÃ biến đổi rồi (â/ê/ô, có thể mang thanh) - hoàn
                // tác về chữ gốc TẠI VỊ TRÍ CŨ (giữ nguyên thanh đang có), rồi nối thêm chính
                // ký tự vừa gõ vào CUỐI TỪ.
                val tone = extractTone(c)
                val restoredCased = if (c.isUpperCase()) keyLower.uppercaseChar() else keyLower
                val restored = applyToneToChar(restoredCased, tone)
                val appended = if (keyIsUpper) keyLower.uppercaseChar() else keyLower
                return TelexResult(word.substring(0, i) + restored + word.substring(i + 1) + appended, wasEscape = true)
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
    private fun applyW(word: String, keyIsUpper: Boolean): TelexResult? {
        val lastChar     = word.last()
        val lastBase     = stripTone(lastChar).lowercaseChar()
        val existingTone = extractTone(lastChar)

        // Escape: cuối từ đã là ă/ơ/ư -> hoàn tác về a/o/u + thêm 'w'
        val escapeMap = mapOf('ă' to 'a', 'ơ' to 'o', 'ư' to 'u')
        if (lastBase in escapeMap) {
            val originalChar = escapeMap[lastBase]!!
            val restoredChar = if (lastChar.isUpperCase()) originalChar.uppercaseChar() else originalChar
            val restored = applyToneToChar(restoredChar, existingTone)
            val result = word.dropLast(1) + restored + (if (keyIsUpper) 'W' else 'w')
            return TelexResult(result, wasEscape = true)
        }

        // aw -> ă
        if (lastBase == 'a') {
            val rep = if (lastChar.isUpperCase()) 'Ă' else 'ă'
            val fin = if (existingTone != Tone.NONE) applyToneToChar(rep, existingTone) else rep
            return TelexResult(word.dropLast(1) + fin, wasEscape = false)
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
            return TelexResult(String(chars), wasEscape = false)
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
                return TelexResult(String(chars), wasEscape = false)
            }
            val rep = if (lastChar.isUpperCase()) 'Ư' else 'ư'
            val fin = if (existingTone != Tone.NONE) applyToneToChar(rep, existingTone) else rep
            return TelexResult(word.dropLast(1) + fin, wasEscape = false)
        }

        // 'w' sau phụ âm cuối: tìm nguyên âm o/u gần nhất từ cuối, biến đổi nó
        // Ví dụ: "trong" + w -> "trông", "nhung" + w -> "nhưng"
        // Nếu nguyên âm cuối là a/e/i/y (vd "tat","set") -> không áp w, trả null
        // để 'w' được gõ thẳng ra như ký tự bình thường, tránh chèn 'ư' nhầm.
        val deferred = transformLastVowelWithW(word, keyIsUpper) ?: return null
        return TelexResult(deferred, wasEscape = false)
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
    /**
     * SỬA LỖI (người dùng phản ánh: gõ "guiwr" ra "guỉw" thay vì "gửi"): TRƯỚC ĐÂY vòng lặp
     * tìm ngược trong từ, hễ gặp NGUYÊN ÂM ĐẦU TIÊN không đủ điều kiện áp 'w' (không phải a/o/u,
     * hoặc đang ở đúng vị trí cuối từ) là `return null` LUÔN - thoát HẲN khỏi hàm, không tìm
     * tiếp các vị trí xa hơn về phía trước nữa. Với từ "gui" (g,u,i), gõ 'w' thì ký tự GẶP ĐẦU
     * TIÊN (quét từ cuối) là 'i' - không đủ điều kiện (i không có dạng biến đổi qua 'w') - hàm
     * thoát ngay tại đây, KHÔNG BAO GIỜ đi tiếp tới 'u' ở vị trí trước đó (dù 'u' hoàn toàn hợp
     * lệ để biến thành 'ư'). Kết quả: 'w' không làm gì cả, bị gõ thẳng ra như ký tự thường ->
     * "guiw", rồi phím 'r' (dấu hỏi) tìm nguyên âm trong "guiw" (không có 'ư') nên áp nhầm dấu
     * hỏi vào 'u' hoặc 'i' -> ra kết quả sai "guỉw"/tương tự, không phải "gửi".
     *
     * Sửa: đổi các `return null` giữa chừng vòng lặp thành `continue` - GẶP nguyên âm không
     * đủ điều kiện thì chỉ BỎ QUA vị trí đó, tiếp tục tìm NGƯỢC XA HƠN về đầu từ, chỉ thật sự
     * bỏ cuộc (return null) khi đã quét hết cả từ mà không tìm được nguyên âm nào hợp lệ.
     */
    private fun transformLastVowelWithW(word: String, keyIsUpper: Boolean): String? {
        for (i in word.indices.reversed()) {
            val c    = word[i]
            val base = stripTone(c).lowercaseChar()
            val rep: Char = when (base) {
                'a'  -> if (c.isUpperCase()) 'Ă' else 'ă'
                'o'  -> if (c.isUpperCase()) 'Ơ' else 'ơ'
                'u'  -> if (c.isUpperCase()) 'Ư' else 'ư'
                // Không phải a/o/u (kể cả e/i/y hay phụ âm) - bỏ qua VỊ TRÍ NÀY thôi, tìm
                // tiếp lên các ký tự PHÍA TRƯỚC nó, không thoát hẳn khỏi hàm ở đây.
                else -> continue
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
        return null
    }

    // =========================================================================
    //  Unicode helpers
    // =========================================================================

    // TOI UU (nguoi dung yeu cau ra soat lai hieu nang sau khi da sua hieu ung LED): TRUOC DAY
    // applyToneToChar()/stripTone()/extractTone() dung Normalizer.normalize() (tach roi ghep
    // lai Unicode NFD<->NFC) - moi LAN GOI cap phat 3-4 String MOI (c.toString(), decomposed,
    // keptMarks qua .filter{}, newSequence noi chuoi, recomposed). Cac ham nay bi goi RAT NHIEU
    // LAN cho MOI KY TU go (tim nguyen am, doi thanh, go lai chu goc...) - vd go 1 tu co dau
    // qua Telex co the goi toi 5-10 lan/phim, cong don ca phien go la hang tram/nghin String
    // rac, dung kieu van de da sua o hieu ung LED (chi khac quy mo: moi PHIM go thay vi moi
    // KHUNG HINH). Thay bang BANG TRA CUU TINH (tinh san 1 lan luc load class) - tra Map O(1),
    // KHONG cap phat String nao (Char.lowercaseChar()/uppercaseChar() chi doi 1 KY TU, khong
    // tao String moi, khac han String.lowercase()/uppercase()).
    private val TONE_TABLE: Map<Char, CharArray> = mapOf(
        // Moi hang: [khong dau, sac, huyen, hoi, nga, nang] - dung DUNG thu tu enum Tone phia
        // tren (NONE, ACUTE, GRAVE, HOOK, TILDE, DOT) de tra bang qua tone.ordinal truc tiep.
        'a' to charArrayOf('a', 'á', 'à', 'ả', 'ã', 'ạ'),
        'ă' to charArrayOf('ă', 'ắ', 'ằ', 'ẳ', 'ẵ', 'ặ'),
        'â' to charArrayOf('â', 'ấ', 'ầ', 'ẩ', 'ẫ', 'ậ'),
        'e' to charArrayOf('e', 'é', 'è', 'ẻ', 'ẽ', 'ẹ'),
        'ê' to charArrayOf('ê', 'ế', 'ề', 'ể', 'ễ', 'ệ'),
        'i' to charArrayOf('i', 'í', 'ì', 'ỉ', 'ĩ', 'ị'),
        'o' to charArrayOf('o', 'ó', 'ò', 'ỏ', 'õ', 'ọ'),
        'ô' to charArrayOf('ô', 'ố', 'ồ', 'ổ', 'ỗ', 'ộ'),
        'ơ' to charArrayOf('ơ', 'ớ', 'ờ', 'ở', 'ỡ', 'ợ'),
        'u' to charArrayOf('u', 'ú', 'ù', 'ủ', 'ũ', 'ụ'),
        'ư' to charArrayOf('ư', 'ứ', 'ừ', 'ử', 'ữ', 'ự'),
        'y' to charArrayOf('y', 'ý', 'ỳ', 'ỷ', 'ỹ', 'ỵ'),
    )

    /** Bảng NGƯỢC (dựng 1 lần từ [TONE_TABLE] ở trên) - tra 1 ký tự nguyên âm CÓ THỂ ĐANG MANG
     *  THANH (vd 'ố') ra (chữ gốc không thanh 'ô', thanh điệu ACUTE) - dùng cho extractTone()/
     *  applyToneToChar() để biết chữ gốc trước khi đổi sang thanh khác. */
    private class VowelInfo(val base: Char, val tone: Tone)
    private val DECOMPOSE_TABLE: Map<Char, VowelInfo> = buildMap {
        for ((base, variantsByTone) in TONE_TABLE) {
            for ((toneOrdinal, ch) in variantsByTone.withIndex()) {
                put(ch, VowelInfo(base, Tone.entries[toneOrdinal]))
            }
        }
    }

    /** Đổi [c] (nguyên âm, có thể đang mang thanh khác hoặc không mang thanh nào) sang mang
     *  đúng thanh [tone] - giữ nguyên chữ HOA/thường của [c]. Nếu [c] không phải 1 trong các
     *  nguyên âm tiếng Việt có thể mang thanh (vd phụ âm, số, ký tự khác) thì trả về NGUYÊN
     *  [c] không đổi gì - y hệt hành vi cũ dùng Normalizer (NFD của 1 phụ âm không có dấu tổ
     *  hợp nào để tách/gắn thêm, nên gắn dấu vào cũng không tạo ra ký tự tổ hợp sẵn nào). */
    private fun applyToneToChar(c: Char, tone: Tone): Char {
        val info = DECOMPOSE_TABLE[c.lowercaseChar()] ?: return c
        val result = TONE_TABLE.getValue(info.base)[tone.ordinal]
        return if (c.isUpperCase()) result.uppercaseChar() else result
    }

    private fun stripTone(c: Char): Char = applyToneToChar(c, Tone.NONE)

    private fun extractTone(c: Char): Tone = DECOMPOSE_TABLE[c.lowercaseChar()]?.tone ?: Tone.NONE

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
