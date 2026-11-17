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
import com.albayda.court.data.AuthManager
import com.albayda.court.data.SyncManager
import com.albayda.court.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val syncListener: () -> Unit = {
        runOnUiThread {
            val current = SyncManager.getNews().first
            if (current.isNotBlank()) binding.newsTicker.setNewsText(current)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        AuthManager.init(this)
        SyncManager.init(this)

        val session = AuthManager.getSession()
        val isAdmin = AuthManager.isPrimaryAdmin(session)
        binding.navAdmin.visibility = if (isAdmin) View.VISIBLE else View.GONE

        val userName = session?.username?.ifBlank { "مستخدم" } ?: "مستخدم"
        binding.tvWelcome.text = "مرحباً بك  $userName"

        val syncedNews = SyncManager.getNews().first
        val localNews = getSharedPreferences("app_prefs", MODE_PRIVATE)
            .getString("news_item", "") ?: ""
        val news = syncedNews.ifBlank { localNews }.ifBlank { "مرحباً بكم في محكمة البيضاء الابتدائية" }
        binding.newsTicker.speedPxPerSec = 80f
        binding.newsTicker.setNewsText(news)
        SyncManager.refresh()

        // Order in grid: sessions, case, supreme, attendance
        setupCard(binding.cardSessions.root, android.R.drawable.ic_menu_my_calendar, "الاستعلام عن الجلسات اليومية")
        setupCard(binding.cardCase.root, android.R.drawable.ic_menu_search, "الاستعلام عن قضية")
        setupCard(binding.cardSupreme.root, android.R.drawable.ic_menu_compass, "الاستعلام عن القضايا بالمحكمة العليا")
        setupCard(binding.cardAttendance.root, android.R.drawable.ic_menu_edit, "إنشاء إعلان حضور")

        binding.cardCase.root.setOnClickListener { openSearch("case") }
        binding.cardAttendance.root.setOnClickListener { openSearch("attendance") }
        binding.cardSessions.root.setOnClickListener { openSearch("sessions") }
        binding.cardSupreme.root.setOnClickListener { openSearch("supreme") }
        binding.cardPosts.setOnClickListener { startActivity(Intent(this, PostsActivity::class.java)) }

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
        selectTab(binding.navHome)
    }

    override fun onStart() {
        super.onStart()
        SyncManager.addListener(syncListener)
    }

    override fun onStop() {
        SyncManager.removeListener(syncListener)
        super.onStop()
    }

    private fun selectTab(active: View) {
        val tabs = listOf(binding.navHome, binding.navServices, binding.navNotifications, binding.navAccount, binding.navAdmin)
        tabs.forEach { tab ->
            tab.alpha = if (tab == active) 1.0f else 0.75f
            tab.setBackgroundColor(if (tab == active) getColor(R.color.navy_800) else android.graphics.Color.TRANSPARENT)
        }
    }

    private fun showNotifications() {
        val notifications = SyncManager.notifications()
        val news = SyncManager.getNews().first
        val body = buildString {
            if (news.isNotBlank()) append("آخر الأخبار: $news\n\n")
            if (notifications.isEmpty()) {
                append("لا توجد إشعارات حالياً.")
            } else {
                notifications.take(20).forEach {
                    append("• ${it.title}: ${it.body}\n${it.timestamp.replace('T', ' ')}\n\n")
                }
            }
        }
        AlertDialog.Builder(this)
            .setTitle("الإشعارات والتنبيهات")
            .setMessage(body)
            .setPositiveButton("إغلاق", null)
            .show()
        SyncManager.markNotificationsRead()
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
