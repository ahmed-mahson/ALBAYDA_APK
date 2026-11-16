package com.albayda.court

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.albayda.court.data.AuthManager
import com.albayda.court.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val news = getSharedPreferences("app_prefs", MODE_PRIVATE)
            .getString("news_item", "مرحباً بكم في محكمة البيضاء الابتدائية") ?: ""
        binding.newsTicker.setNews(listOf(news))
        binding.newsTicker.setOnLongClickListener {
            startActivity(Intent(this, AdminActivity::class.java))
            true
        }

        // تعيين الأيقونات والعناوين للبطاقات
        setupCard(binding.cardCase.root, android.R.drawable.ic_menu_search, "الاستعلام عن قضية")
        setupCard(binding.cardAttendance.root, android.R.drawable.ic_menu_edit, "إنشاء إعلان حضور")
        setupCard(binding.cardSessions.root, android.R.drawable.ic_menu_my_calendar, "الجلسات اليومية")
        setupCard(binding.cardSupreme.root, android.R.drawable.ic_menu_compass, "المحكمة العليا")

        binding.cardCase.root.setOnClickListener { openSearch("case") }
        binding.cardAttendance.root.setOnClickListener { openSearch("attendance") }
        binding.cardSessions.root.setOnClickListener { openSearch("sessions") }
        binding.cardSupreme.root.setOnClickListener { openSearch("supreme") }

        binding.tvLogout.setOnClickListener {
            AuthManager.clearSession()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    private fun setupCard(card: android.view.View, iconRes: Int, title: String) {
        card.findViewById<ImageView>(R.id.ivIcon).setImageResource(iconRes)
        card.findViewById<TextView>(R.id.tvTitle).text = title
    }

    private fun openSearch(mode: String) {
        startActivity(Intent(this, SearchActivity::class.java).putExtra("MODE", mode))
    }
}