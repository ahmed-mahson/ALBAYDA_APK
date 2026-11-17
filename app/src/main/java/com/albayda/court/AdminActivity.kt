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

        binding.etSyncUrl.setText(SyncManager.endpoint())
        updateSyncStatus()

        binding.btnSaveSync.setOnClickListener {
            val url = binding.etSyncUrl.text.toString().trim()
            if (url.isBlank()) {
                Toast.makeText(this, "يرجى إدخال رابط خادم المزامنة", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            SyncManager.configure(url)
            updateSyncStatus()
            Toast.makeText(this, "تم تفعيل المزامنة الفورية", Toast.LENGTH_SHORT).show()
        }

        binding.btnOpenPosts.setOnClickListener {
            startActivity(Intent(this, PostsActivity::class.java))
        }

        binding.etNews.setText(SyncManager.getNews().first)

        binding.btnAddNews.setOnClickListener {
            val news = binding.etNews.text.toString().trim()
            if (news.isNotEmpty()) {
                SyncManager.setNews(news)
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
                    SyncManager.notifyUserApproved(u.mobile, true)
                    Toast.makeText(this, "تم اعتماد المستخدم", Toast.LENGTH_SHORT).show()
                    refresh()
                }
                .setNegativeButton("رفض") { _, _ ->
                    AuthManager.rejectUser(u.mobile)
                    SyncManager.notifyUserApproved(u.mobile, false)
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

    private fun updateSyncStatus() {
        binding.tvSyncStatus.text = if (SyncManager.configured())
            "المزامنة مفعّلة ✓ — البيانات تُرسل وتُستقبل تلقائياً"
        else
            "غير مفعّلة — ضع رابط الخادم ثم اضغط حفظ (راجع SERVER_SETUP.md)"
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
}
