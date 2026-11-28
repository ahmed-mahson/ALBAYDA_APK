package com.albayda.court

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.albayda.court.data.AuthManager
import com.albayda.court.data.SyncManager
import com.albayda.court.data.User
import com.albayda.court.databinding.ActivityAdminBinding

class AdminActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAdminBinding
    private var pending: List<User> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminBinding.inflate(layoutInflater)
        setContentView(binding.root)
        AuthManager.init(this)
        SyncManager.init(this)

        val session = AuthManager.getSession()
        if (!AuthManager.isPrimaryAdmin(session)) {
            Toast.makeText(this, "هذه الشاشة خاصة بمدير النظام فقط", Toast.LENGTH_SHORT).show()
            finish()
            return
        }


        binding.btnUserManagement.setOnClickListener {
            startActivity(Intent(this, AdminUsersActivity::class.java))
        }
        binding.btnOpenPosts.setOnClickListener {
            startActivity(Intent(this, PostsActivity::class.java))
        }
        binding.navHome.setOnClickListener { finish() }
        binding.navAdmin.setOnClickListener { AuthManager.auditAdmin("فتح لوحة الإدارة") }
        binding.navAccount.setOnClickListener { showAccountDialog() }

        binding.etNews.setText(SyncManager.getNews().first)

        binding.btnAddNews.setOnClickListener {
            val news = binding.etNews.text.toString().trim()
            if (news.isNotEmpty()) {
                SyncManager.setNews(news)
                getSharedPreferences("app_prefs", MODE_PRIVATE)
                    .edit().putString("news_item", news).apply()
                SyncManager.refresh()
                Toast.makeText(this, "تم تحديث الشريط الإخباري ومزامنته", Toast.LENGTH_SHORT).show()
            }
        }

        binding.lvPending.setOnItemClickListener { _, _, position, _ ->
            val u = pending.getOrNull(position) ?: return@setOnItemClickListener
            AlertDialog.Builder(this)
                .setTitle("إدارة طلب المستخدم")
                .setMessage("المستخدم: ${u.username}\nالجوال: ${u.mobile}\nالتاريخ: ${u.createdAt}")
                .setPositiveButton("اعتماد") { _, _ ->
                    AuthManager.approveUser(u.mobile, true)
                    Toast.makeText(this, "تم اعتماد المستخدم", Toast.LENGTH_SHORT).show()
                    refresh()
                }
                .setNegativeButton("رفض") { _, _ ->
                    AuthManager.rejectUser(u.mobile)
                    Toast.makeText(this, "تم رفض المستخدم وحذف الطلب", Toast.LENGTH_SHORT).show()
                    refresh()
                }
                .setNeutralButton("إلغاء", null)
                .show()
        }

        refresh()
        SyncManager.refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        pending = AuthManager.pendingUsers()
        val pendingDisplay = if (pending.isEmpty()) listOf("لا توجد طلبات جديدة") else pending.map {
            "${it.username} - ${it.mobile}\n${it.createdAt}"
        }
        binding.lvPending.adapter = ArrayAdapter(this, R.layout.spinner_item, pendingDisplay)

        val logs = AuthManager.getLogs().take(100)
        val users = AuthManager.getUsers().filter { !it.isAdmin }
        val header = "إجمالي المستخدمين (غير المدير): ${users.size}\nبانتظار الموافقة: ${pending.size}\n\nآخر السجلات:\n"
        val logText = if (logs.isEmpty()) "لا توجد سجلات بعد." else logs.joinToString("\n") {
            "• [${it.time}] ${it.username} (${it.mobile}) - ${it.action}${if (it.details.isNotBlank()) ": ${it.details}" else ""}"
        }
        binding.tvLogs.text = header + logText

        val notifications = SyncManager.notifications()
        binding.tvNotifications.text = if (notifications.isEmpty()) {
            "لا توجد إشعارات حالياً."
        } else {
            SyncManager.markNotificationsRead()
            notifications.joinToString("\n\n") {
                "• ${it.title}\n${it.body}\n${it.timestamp.replace('T', ' ')}"
            }
        }
    }

    private fun showAccountDialog() {
        val current = AuthManager.getSession() ?: return
        val view = layoutInflater.inflate(R.layout.dialog_account, null)
        val tvUsername = view.findViewById<android.widget.TextView>(R.id.tvAccountUsername)
        val tvMobile = view.findViewById<android.widget.TextView>(R.id.tvAccountMobile)
        val tvStatus = view.findViewById<android.widget.TextView>(R.id.tvAccountStatus)
        val etNewUsername = view.findViewById<android.widget.EditText>(R.id.etNewUsername)
        val etNewPassword = view.findViewById<android.widget.EditText>(R.id.etNewPassword)
        val btnSave = view.findViewById<android.view.View>(R.id.btnSaveAccount)
        val btnClose = view.findViewById<android.view.View>(R.id.btnCloseAccount)
        fun bindInfo() {
            val user = AuthManager.getSession() ?: current
            tvUsername.text = "اسم المستخدم: ${user.username}"
            tvMobile.text = "رقم الجوال: ${user.mobile}"
            tvStatus.text = if (user.isApproved) "حالة الحساب: معتمد" else "حالة الحساب: بانتظار الموافقة"
            etNewUsername.setText(user.username)
            etNewUsername.setSelection(etNewUsername.text?.length ?: 0)
        }
        bindInfo()
        val dialog = AlertDialog.Builder(this).setView(view).setCancelable(true).create()
        btnSave.setOnClickListener {
            val user = AuthManager.getSession() ?: current
            val newUsername = etNewUsername.text.toString().trim()
            val newPassword = etNewPassword.text.toString()
            var changed = false
            if (newUsername.isNotBlank() && newUsername != user.username) {
                if (AuthManager.updateUsername(user.mobile, newUsername)) changed = true
                else { Toast.makeText(this, "تعذر تحديث اسم المستخدم", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            }
            if (newPassword.isNotBlank()) {
                if (newPassword.length < 4) { Toast.makeText(this, "كلمة المرور قصيرة", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                if (AuthManager.updatePassword(user.mobile, newPassword)) changed = true
            }
            if (changed) { Toast.makeText(this, "تم حفظ التعديلات", Toast.LENGTH_SHORT).show(); bindInfo() }
            else Toast.makeText(this, "لا توجد تغييرات", Toast.LENGTH_SHORT).show()
        }
        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }
}
