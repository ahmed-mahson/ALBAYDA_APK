package com.albayda.court

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
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
        runOnUiThread { loadNews() }
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

        updateWelcome(session?.username)

        binding.newsTicker.speedPxPerSec = 160f
        loadNews()
        SyncManager.refresh()

        binding.cardCase.setOnClickListener { openSearch("case") }
        binding.cardAttendance.setOnClickListener { openSearch("attendance") }
        binding.cardSessions.setOnClickListener { openSearch("sessions") }
        binding.cardSupreme.setOnClickListener { openSearch("supreme") }
        binding.cardPosts.setOnClickListener { startActivity(Intent(this, PostsActivity::class.java)) }

        binding.tvWhatsapp.setOnClickListener {
            openExternal("https://wa.me/967734840710", "tg://resolve?phone=967771421919")
        }
        binding.tvTelegram.setOnClickListener {
            openExternal("tg://resolve?phone=967771421919", "https://t.me/967771421919")
        }

        binding.ivBell.setOnClickListener { showNotifications(); selectTab(binding.navNotifications) }
        binding.navAdmin.setOnClickListener { startActivity(Intent(this, AdminActivity::class.java)) }
        binding.navAccount.setOnClickListener { showAccount(); selectTab(binding.navAccount) }
        binding.navNotifications.setOnClickListener { showNotifications(); selectTab(binding.navNotifications) }
        binding.navLogout.setOnClickListener { logout() }
    }

    override fun onResume() {
        super.onResume()
        loadNews()
        SyncManager.refresh()
    }

    private fun loadNews() {
        val synced = SyncManager.getNews().first
        val local = getSharedPreferences("app_prefs", MODE_PRIVATE)
            .getString("news_item", "") ?: ""
        val news = synced.ifBlank { local }.ifBlank { "مرحباً بكم في محكمة البيضاء الابتدائية" }
        binding.newsTicker.setNewsText(news)
    }

    private fun openExternal(primary: String, fallback: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(primary))
        try {
            startActivity(intent)
        } catch (_: Exception) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallback))) } catch (_: Exception) {}
        }
    }

    override fun onStart() {
        super.onStart()
        SyncManager.addListener(syncListener)
    }

    override fun onStop() {
        SyncManager.removeListener(syncListener)
        super.onStop()
    }

    private fun updateWelcome(username: String?) {
        binding.tvWelcome.text = "مرحباً بك  ${username?.ifBlank { "مستخدم" } ?: "مستخدم"}"
    }

    private fun selectTab(active: View) {
        val tabs = listOf(binding.navAdmin, binding.navAccount, binding.navNotifications, binding.navLogout)
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

    private fun showAccount() {
        val current = AuthManager.getSession()
        if (current == null) {
            logout()
            return
        }

        val view = layoutInflater.inflate(R.layout.dialog_account, null)
        val tvUsername = view.findViewById<TextView>(R.id.tvAccountUsername)
        val tvMobile = view.findViewById<TextView>(R.id.tvAccountMobile)
        val tvStatus = view.findViewById<TextView>(R.id.tvAccountStatus)
        val etNewUsername = view.findViewById<EditText>(R.id.etNewUsername)
        val etNewPassword = view.findViewById<EditText>(R.id.etNewPassword)
        val btnSave = view.findViewById<View>(R.id.btnSaveAccount)
        val btnClose = view.findViewById<View>(R.id.btnCloseAccount)

        fun bindInfo() {
            val user = AuthManager.getSession() ?: current
            tvUsername.text = "اسم المستخدم: ${user.username}"
            tvMobile.text = "رقم الجوال: ${user.mobile}"
            tvStatus.text = if (user.isApproved) "حالة الحساب: معتمد" else "حالة الحساب: بانتظار الاعتماد"
            etNewUsername.setText(user.username)
            etNewUsername.setSelection(etNewUsername.text?.length ?: 0)
        }
        bindInfo()

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(true)
            .create()

        btnSave.setOnClickListener {
            val user = AuthManager.getSession() ?: current
            val newUsername = etNewUsername.text.toString().trim()
            val newPassword = etNewPassword.text.toString()
            var changed = false

            if (newUsername.isNotBlank() && newUsername != user.username) {
                if (AuthManager.updateUsername(user.mobile, newUsername)) {
                    changed = true
                } else {
                    Toast.makeText(this, "اسم المستخدم مستخدم من قبل حساب آخر", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            }
            if (newPassword.isNotBlank()) {
                if (newPassword.length < 4) {
                    Toast.makeText(this, "كلمة المرور يجب ألا تقل عن 4 أحرف", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (AuthManager.updatePassword(user.mobile, newPassword)) {
                    changed = true
                } else {
                    Toast.makeText(this, "تعذر تحديث كلمة المرور", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            }

            if (!changed) {
                Toast.makeText(this, "لا توجد تغييرات للحفظ", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val updated = AuthManager.getSession() ?: user
            updateWelcome(updated.username)
            etNewPassword.text?.clear()
            bindInfo()
            Toast.makeText(this, "تم حفظ تعديلات الحساب", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun logout() {
        AuthManager.clearSession()
        startActivity(
            Intent(this, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    private fun openSearch(mode: String) {
        startActivity(Intent(this, SearchActivity::class.java).putExtra("MODE", mode))
    }
}
