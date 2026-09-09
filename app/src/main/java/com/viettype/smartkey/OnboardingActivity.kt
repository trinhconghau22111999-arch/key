package com.viettype.smartkey

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Màn hình hướng dẫn 2 bước để bật + chọn bàn phím này làm bàn phím đang dùng -
 *  Android không cho app tự động bật IME của mình, phải dẫn người dùng qua đúng
 *  2 màn hình cài đặt hệ thống. */
class OnboardingActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            setBackgroundColor(Color.parseColor("#1A0F2E"))
        }

        root.addView(TextView(this).apply {
            text = "Bật QR Keyboard gaming 2"
            textSize = 24f
            setTextColor(Color.WHITE)
        })

        root.addView(TextView(this).apply {
            text = "\nBước 1: Bật bàn phím trong danh sách bàn phím hệ thống.\n" +
                "Bước 2: Chọn QR Keyboard gaming 2 làm bàn phím đang gõ.\n"
            textSize = 15f
            setTextColor(Color.LTGRAY)
        })

        root.addView(Button(this).apply {
            text = "Bước 1: Mở Cài đặt bàn phím"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
        })

        root.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 32) })

        root.addView(Button(this).apply {
            text = "Bước 2: Chọn bàn phím đang dùng"
            setOnClickListener {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
            }
        })

        val scrollRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            addView(root)
        }
        setContentView(scrollRoot)
    }
}
