package com.albayda.court

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.albayda.court.data.AuthManager
import com.albayda.court.databinding.ActivityAdminBinding

class AdminActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAdminBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val session = AuthManager.getSession()
        if (session == null || !session.isAdmin) {
            Toast.makeText(this, "غير مصرح لك بالدخول", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        refreshUserList()

        binding.btnAddNews.setOnClickListener {
            val news = binding.etNews.text.toString().trim()
            if (news.isNotEmpty()) {
                getSharedPreferences("app_prefs", MODE_PRIVATE).edit()
                    .putString("news_item", news).apply()
                binding.etNews.text.clear()
                Toast.makeText(this, "تم إضافة الخبر للشريط العلوي", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshUserList() {
        val users = AuthManager.getUsers().filter { !it.isAdmin }
        val displayList = users.map {
            "${it.username} (${it.mobile}) - ${if (it.isApproved) "معتمد" else "قيد الانتظار"}"
        }.toMutableList()

        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, displayList)
        binding.lvUsers.adapter = adapter

        binding.lvUsers.setOnItemClickListener { _, _, position, _ ->
            val targetUser = users[position]
            if (!targetUser.isApproved) {
                AlertDialog.Builder(this)
                    .setTitle("موافقة على حساب")
                    .setMessage("هل توافق على تفعيل حساب: ${targetUser.username}؟")
                    .setPositiveButton("موافق") { _, _ ->
                        AuthManager.approveUser(targetUser.mobile, true)
                        refreshUserList()
                        Toast.makeText(this, "تم اعتماد الحساب", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("إلغاء", null)
                    .show()
            }
        }
    }
}