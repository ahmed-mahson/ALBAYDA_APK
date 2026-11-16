package com.albayda.court

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.albayda.court.databinding.ActivityMainBinding
import com.albayda.court.util.NewsTicker // الكلاس السابق الذي زودتك به

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // إعداد الشريط الإخباري
        binding.newsTicker.setNews(listOf("مرحباً بكم في البوابة الإلكترونية لمحكمة البيضاء الابتدائية", "يرجى مراجعة قسم الجلسات اليومية للاطلاع على آخر التحديثات"))
        
        // نافذة مدير النظام عند الضغط المطول على الشريط
        binding.newsTicker.setOnLongClickListener {
            startActivity(Intent(this, AdminActivity::class.java))
            true
        }

        // ترتيب الخدمات كما طلبت: 1. الجلسات, 2. الاستعلام عن قضية, 3. إعلان حضور, 4. المحكمة العليا
        binding.cardSessions.setOnClickListener { openSearch("sessions") }
        binding.cardCase.setOnClickListener { openSearch("case") }
        binding.cardAttendance.setOnClickListener { openSearch("attendance") } // تم إضافته هنا تحت الاستعلام عن قضية
        binding.cardSupreme.setOnClickListener { openSearch("supreme") }

        binding.tvLogout.setOnClickListener {
            getSharedPreferences("auth_secure_prefs", MODE_PRIVATE).edit().remove("session_user").apply()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    private fun openSearch(mode: String) {
        val intent = Intent(this, SearchActivity::class.java)
        intent.putExtra("MODE", mode)
        startActivity(intent)
    }
}