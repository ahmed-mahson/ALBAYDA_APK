package com.albayda.court

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
        AuthManager.init(this)

        val session = AuthManager.getSession()
        val isAdmin = AuthManager.isPrimaryAdmin(session)
        binding.navAdmin.visibility = if (isAdmin) View.VISIBLE else View.GONE

        val news = getSharedPreferences("app_prefs", MODE_PRIVATE)
            .getString("news_item", "مرحباً بكم في محكمة البيضاء الابتدائية") ?: "مرحباً بكم في محكمة البيضاء الابتدائية"
        binding.newsTicker.speedPxPerSec = 80f
        binding.newsTicker.setNewsText(news)

        // Order in grid: sessions, case, supreme, attendance
        setupCard(binding.cardSessions.root, android.R.drawable.ic_menu_my_calendar, "الاستعلام عن الجلسات اليومية")
        setupCard(binding.cardCase.root, android.R.drawable.ic_menu_search, "الاستعلام عن قضية")
        setupCard(binding.cardSupreme.root, android.R.drawable.ic_menu_compass, "الاستعلام عن القضايا بالمحكمة العليا")
        setupCard(binding.cardAttendance.root, android.R.drawable.ic_menu_edit, "إنشاء إعلان حضور")

        binding.cardCase.root.setOnClickListener { openSearch("case") }
        binding.cardAttendance.root.setOnClickListener { openSearch("attendance") }
        binding.cardSessions.root.setOnClickListener { openSearch("sessions") }
        binding.cardSupreme.root.setOnClickListener { openSearch("supreme") }

        binding.tvWhatsapp.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/qr/QIQRW7LA325GG1")))
        }
        binding.tvTelegram.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/Ahmed_Mahson")))
        }

        binding.ivBell.setOnClickListener { showNotifications() }
        binding.navHome.setOnClickListener { binding.mainScroll.smoothScrollTo(0, 0); selectTab(binding.navHome) }
        binding.navServices.setOnClickListener { binding.mainScroll.smoothScrollTo(0, binding.servicesGrid.top); selectTab(binding.navServices) }
        binding.navNotifications.setOnClickListener { showNotifications(); selectTab(binding.navNotifications) }
        binding.navAccount.setOnClickListener { showAccount(session?.username ?: "مستخدم"); selectTab(binding.navAccount) }
        binding.navAdmin.setOnClickListener { startActivity(Intent(this, AdminActivity::class.java)) }
    }

    private fun selectTab(active: TextView) {
        val tabs = listOf(binding.navHome, binding.navServices, binding.navNotifications, binding.navAccount, binding.navAdmin)
        tabs.forEach { it.setTextColor(getColor(if (it == active) R.color.navy_700 else R.color.text_muted)) }
    }

    private fun showNotifications() {
        val news = getSharedPreferences("app_prefs", MODE_PRIVATE).getString("news_item", "") ?: ""
        AlertDialog.Builder(this)
            .setTitle("الإشعارات والتنبيهات")
            .setMessage(if (news.isBlank()) "لا توجد إشعارات حالياً." else news)
            .setPositiveButton("إغلاق", null)
            .show()
    }

    private fun showAccount(name: String) {
        AlertDialog.Builder(this)
            .setTitle("حسابي")
            .setMessage("المستخدم: $name")
            .setPositiveButton("تسجيل الخروج") { _, _ ->
                AuthManager.clearSession()
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun setupCard(card: View, iconRes: Int, title: String) {
        card.findViewById<ImageView>(R.id.ivIcon).setImageResource(iconRes)
        card.findViewById<TextView>(R.id.tvTitle).text = title
    }

    private fun openSearch(mode: String) {
        startActivity(Intent(this, SearchActivity::class.java).putExtra("MODE", mode))
    }
}
