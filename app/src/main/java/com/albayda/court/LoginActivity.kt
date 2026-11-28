package com.albayda.court

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.albayda.court.data.AuthManager
import com.albayda.court.data.SyncManager
import com.albayda.court.databinding.ActivityLoginBinding

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        AuthManager.init(this)
        SyncManager.init(this)

        val session = AuthManager.getSession()
        if (session != null && session.isApproved) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        var passwordVisible = false
        binding.ivEye.setOnClickListener {
            passwordVisible = !passwordVisible
            binding.etPassword.inputType =
                android.text.InputType.TYPE_CLASS_TEXT or
                        if (passwordVisible) android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        else android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            binding.etPassword.setSelection(binding.etPassword.text?.length ?: 0)
        }

        binding.btnLogin.setOnClickListener {
            val id = binding.etIdentifier.text.toString().trim()
            val pass = binding.etPassword.text.toString().trim()
            if (id.isEmpty() || pass.isEmpty()) {
                Toast.makeText(this, "يرجى إدخال جميع البيانات", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val user = AuthManager.login(id, pass)
            when {
                user == null -> Toast.makeText(this, "بيانات الدخول غير صحيحة", Toast.LENGTH_SHORT).show()
                !user.isApproved -> Toast.makeText(this, "حسابك قيد المراجعة من مدير النظام", Toast.LENGTH_LONG).show()
                else -> {
                    AuthManager.saveSession(user)
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }
            }
        }

        binding.tvRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
    }
}
