package com.albayda.court

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.albayda.court.data.AuthManager
import com.albayda.court.databinding.ActivityRegisterBinding

class RegisterActivity : AppCompatActivity() {
    private lateinit var binding: ActivityRegisterBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnRegister.setOnClickListener {
            val username = binding.etUsername.text.toString().trim()
            val mobile = binding.etMobile.text.toString().trim()
            val pass = binding.etPassword.text.toString().trim()

            if (username.isEmpty() || mobile.isEmpty() || pass.isEmpty()) {
                Toast.makeText(this, "جميع الحقول مطلوبة", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val success = AuthManager.register(username, mobile, pass)
            if (success) {
                Toast.makeText(this, "تم إرسال طلب إنشاء الحساب. بانتظار موافقة مدير النظام (أحمد محسون)", Toast.LENGTH_LONG).show()
                finish()
            } else {
                Toast.makeText(this, "اسم المستخدم أو رقم الجوال مسجل مسبقاً", Toast.LENGTH_SHORT).show()
            }
        }
    }
}