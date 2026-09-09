package com.viettype.smartkey

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.app.ActivityCompat

/**
 * InputMethodService (bàn phím) không tự hiện được hộp thoại xin quyền runtime -
 * Activity này chỉ tồn tại trong 1 khoảnh khắc để bật hộp thoại xin quyền Camera,
 * rồi tự đóng ngay, không có giao diện gì hiển thị (theme trong suốt).
 */
class CameraPermissionRelay : Activity() {

    companion object {
        const val REQUEST_CODE = 501
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isCameraPermissionGranted()) {
            finish()
            return
        }
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        finish()
    }

    private fun isCameraPermissionGranted(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
}
