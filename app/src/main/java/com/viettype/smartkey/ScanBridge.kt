package com.viettype.smartkey

/**
 * Cầu nối giữa cửa sổ quét NỔI ([FloatingScanService]) và bàn phím ([SmartKeyboardService]).
 *
 * Cửa sổ quét chạy ĐỘC LẬP với cửa sổ bàn phím (nên bàn phím tự ẩn đi thì khung quét vẫn còn),
 * nhưng việc gõ mã quét được vào ô nhập vẫn phải do bàn phím làm (chỉ IME mới có
 * InputConnection). Bàn phím đăng ký [sink] khi được tạo và gỡ khi bị huỷ; cả hai service chạy
 * chung 1 tiến trình nên dùng biến tĩnh là đủ. Mọi hàm đều gọi trên luồng chính.
 */
object ScanBridge {

    interface Sink {
        /** Khung quét nổi vừa mở - đặt lại bộ đếm quét trùng lặp. */
        fun onScanSessionStarted()

        /** Khung quét nổi vừa tắt (bấm Huỷ...) - dọn trạng thái. */
        fun onScanSessionEnded()

        /** Đọc được 1 mã. Trả về số mili-giây khung quét cần "nghỉ" trước khi nhận mã kế tiếp. */
        fun onScanned(content: String): Long
    }

    @Volatile
    var sink: Sink? = null
}
