package com.albayda.court

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.albayda.court.data.AuthManager
import com.albayda.court.databinding.ActivityLoginBinding

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        AuthManager.init(this)

        val session = AuthManager.getSession()
        if (session != null && session.isApproved) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
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
                    if (binding.cbRemember.isChecked) AuthManager.saveSession(user)
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }
            }
        }

        binding.tvRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }

        binding.btnBiometric.setOnClickListener {
            if (BiometricManager.from(this).canAuthenticate(
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
                ) == BiometricManager.BIOMETRIC_SUCCESS
            ) {
                val prompt = BiometricPrompt.PromptInfo.Builder()
                    .setTitle("دخول بالبصمة")
                    .setSubtitle("محكمة البيضاء الابتدائية")
                    .setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    )
                    .build()
                BiometricPrompt(this, ContextCompat.getMainExecutor(this),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            val s = AuthManager.getSession()
                            if (s?.isApproved == true) {
                                startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                                finish()
                            } else {
                                Toast.makeText(
                                    this@LoginActivity,
                                    "سجل الدخول بكلمة المرور أولاً لتفعيل البصمة",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }).authenticate(prompt)
            } else {
                Toast.makeText(this, "البصمة غير مفعلة في هذا الجهاز", Toast.LENGTH_SHORT).show()
            }
        }
    }
}