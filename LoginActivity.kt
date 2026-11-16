package com.albayda.court

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.albayda.court.data.AuthManager
import com.albayda.court.databinding.ActivityLoginBinding

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // جعل الشاشة كاملة مع احترام الهوامش
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        AuthManager.init(this)

        // التحقق من وجود جلسة محفوظة
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
                Toast.makeText(this, "يرجى إدخال اسم المستخدم أو رقم الجوال وكلمة المرور", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val user = AuthManager.login(id, pass)
            if (user == null) {
                Toast.makeText(this, "بيانات الدخول غير صحيحة", Toast.LENGTH_SHORT).show()
            } else if (!user.isApproved) {
                Toast.makeText(this, "حسابك قيد المراجعة ولم تتم الموافقة عليه من مدير النظام بعد", Toast.LENGTH_LONG).show()
            } else {
                AuthManager.saveSession(user, this)
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        }

        binding.tvRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }

        binding.btnBiometric.setOnClickListener {
            val biometricManager = BiometricManager.from(this)
            if (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS) {
                val promptInfo = BiometricPrompt.PromptInfo.Builder()
                    .setTitle("تسجيل الدخول بالبصمة")
                    .setSubtitle("محكمة البيضاء الابتدائية")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                    .build()

                val biometricPrompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            val session = AuthManager.getSession()
                            if (session != null && session.isApproved) {
                                startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                                finish()
                            } else {
                                Toast.makeText(this@LoginActivity, "يرجى تسجيل الدخول بكلمة المرور أولاً لتفعيل البصمة", Toast.LENGTH_SHORT).show()
                            }
                        }
                    })
                biometricPrompt.authenticate(promptInfo)
            } else {
                Toast.makeText(this, "البصمة غير مفعلة في هذا الجهاز", Toast.LENGTH_SHORT).show()
            }
        }
    }
}