package com.albayda.court

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.albayda.court.data.AuthManager
import com.albayda.court.data.SyncManager
import com.albayda.court.data.User
import com.albayda.court.data.UserLog
import com.albayda.court.util.PdfReport
import java.text.SimpleDateFormat
import java.util.Locale

/** This management screen is restricted to the primary administrator account. */
class AdminUsersActivity : AppCompatActivity() {
    private val teal = 0xFF168457.toInt()
    private lateinit var content: LinearLayout
    private lateinit var search: EditText
    private lateinit var usersList: ListView
    private lateinit var logsText: TextView
    private lateinit var statsText: TextView
    private var users: List<User> = emptyList()
    private var reportLogs: List<UserLog> = emptyList()
    private var dateFilter: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AuthManager.init(this)
        SyncManager.init(this)
        if (!AuthManager.isPrimaryAdmin(AuthManager.getSession())) {
            Toast.makeText(this, "هذه الصفحة خاصة بمسؤول النظام أحمد محسون", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        createScreen()
        reload()
        AuthManager.auditAdmin("فتح إدارة المستخدمين والتقارير")
    }

    private fun createScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(0xFFF3F6F8.toInt())
        }
        root.addView(TextView(this).apply {
            text = "إدارة المستخدمين والتقارير والإشعارات"
            textSize = 18f
            setTextColor(-1)
            gravity = Gravity.CENTER
            setPadding(12, 18, 12, 18)
            setBackgroundColor(teal)
        })
        val scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(24))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(Button(this).apply {
            text = "العودة إلى نافذة الإدارة"
            setOnClickListener { finish() }
        })
        setContentView(root)

        heading("لوحة الإحصائيات")
        statsText = label("...")
        heading("إدارة المستخدمين")
        search = EditText(this).apply {
            hint = "بحث بالاسم أو رقم الهاتف"
            textSize = 14f
            setSingleLine(true)
        }
        content.addView(search, full())
        usersList = ListView(this)
        content.addView(usersList, LinearLayout.LayoutParams(-1, dp(230)))
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { showUsers(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: Editable?) {}
        })
        usersList.setOnItemClickListener { _, _, position, _ ->
            val found = visibleUsers(search.text.toString()).getOrNull(position) ?: return@setOnItemClickListener
            showUserMenu(found)
        }

        heading("التقارير التفصيلية وسجلات المستخدمين")
        val filter = EditText(this).apply {
            hint = "تصفية بالاسم أو الهاتف أو نوع العملية"
            textSize = 14f
            singleLine = true
        }
        content.addView(filter, full())
        val dateActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        dateActions.addView(Button(this).apply {
            text = "تصفية حسب التاريخ"
            setOnClickListener {
                val now = java.util.Calendar.getInstance()
                android.app.DatePickerDialog(this@AdminUsersActivity, { _, year, month, day ->
                    dateFilter = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day)
                    showLogs(filter.text.toString())
                }, now.get(java.util.Calendar.YEAR), now.get(java.util.Calendar.MONTH), now.get(java.util.Calendar.DAY_OF_MONTH)).show()
            }
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
        dateActions.addView(Button(this).apply {
            text = "إلغاء التاريخ"
            setOnClickListener { dateFilter = ""; showLogs(filter.text.toString()) }
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
        content.addView(dateActions, full())
        logsText = label("لا توجد سجلات")
        content.addView(logsText, full())
        filter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { showLogs(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: Editable?) {}
        })
        val exports = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        exports.addView(Button(this).apply { text = "تصدير CSV / Excel"; setOnClickListener { exportCsv() } },
            LinearLayout.LayoutParams(0, dp(48), 1f))
        exports.addView(Button(this).apply { text = "طباعة PDF"; setOnClickListener { exportPdf() } },
            LinearLayout.LayoutParams(0, dp(48), 1f))
        content.addView(exports, full())

        heading("مركز الإشعارات")
        val notificationTitle = EditText(this).apply { hint = "عنوان الإشعار"; textSize = 14f }
        val notificationBody = EditText(this).apply { hint = "نص الإشعار"; minLines = 3; gravity = Gravity.TOP; textSize = 14f }
        content.addView(notificationTitle, full())
        content.addView(notificationBody, full())
        content.addView(actionButton("إرسال إلى جميع المستخدمين") {
            send(notificationTitle.text.toString(), notificationBody.text.toString(), listOf("ALL"))
        }, full())
        content.addView(actionButton("إرسال إلى مستخدم محدد") {
            val choices = users.filter { !it.isAdmin }
            if (choices.isEmpty()) toast("لا يوجد مستخدمون")
            else AlertDialog.Builder(this).setTitle("اختر المستخدم")
                .setItems(choices.map { it.username + " - " + it.mobile }.toTypedArray()) { _, index ->
                    send(notificationTitle.text.toString(), notificationBody.text.toString(), listOf(choices[index].mobile))
                }.show()
        }, full())
        content.addView(actionButton("إرسال إلى مجموعة المستخدمين المعتمدين") {
            val targets = users.filter { !it.isAdmin && it.isApproved && !it.isSuspended }.map { it.mobile }
            send(notificationTitle.text.toString(), notificationBody.text.toString(), targets)
        }, full())
    }

    private fun showUserMenu(user: User) {
        val suspended = user.isSuspended || !user.isApproved
        val actions = arrayOf(
            if (suspended) "إعادة تفعيل الحساب" else "إيقاف الحساب مؤقتاً",
            "عرض السجل التفصيلي",
            "حذف الحساب"
        )
        AlertDialog.Builder(this).setTitle(user.username + "\n" + user.mobile)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> AlertDialog.Builder(this)
                        .setMessage(if (suspended) "هل تريد إعادة تفعيل الحساب؟" else "هل تريد إيقاف هذا المستخدم؟")
                        .setPositiveButton("تأكيد") { _, _ ->
                            val stop = !suspended
                            if (AuthManager.setUserSuspended(user.mobile, stop)) {
                                AuthManager.auditAdmin(if (stop) "إيقاف مستخدم" else "إعادة تفعيل مستخدم", user.mobile)
                                toast("تم تحديث حالة المستخدم")
                                reload()
                            }
                        }.setNegativeButton("إلغاء", null).show()
                    1 -> {
                        AuthManager.auditAdmin("عرض سجل مستخدم", user.mobile)
                        val rows = AuthManager.getLogs().filter { it.mobile == user.mobile }
                        AlertDialog.Builder(this).setTitle("سجل " + user.username)
                            .setMessage(rows.joinToString("\n\n") { it.time + "\n" + it.action + " " + it.details }
                                .ifBlank { "لا توجد سجلات لهذا المستخدم" })
                            .setPositiveButton("إغلاق", null).show()
                    }
                    2 -> AlertDialog.Builder(this)
                        .setMessage("هل تريد حذف حساب " + user.username + "؟ لا يمكن التراجع عن هذا الإجراء.")
                        .setPositiveButton("حذف") { _, _ ->
                            if (AuthManager.deleteUser(user.mobile)) {
                                AuthManager.auditAdmin("حذف حساب مستخدم", user.mobile)
                                toast("تم حذف الحساب")
                                reload()
                            }
                        }.setNegativeButton("إلغاء", null).show()
                }
            }.setNegativeButton("إغلاق", null).show()
    }

    private fun send(title: String, body: String, targets: List<String>) {
        if (title.isBlank() || body.isBlank()) { toast("أدخل عنوان الإشعار ونصه"); return }
        if (targets.isEmpty()) { toast("لا يوجد مستلمون مطابقون"); return }
        SyncManager.sendAdminNotification(title, body, targets)
        AuthManager.auditAdmin("إرسال إشعار", "عدد المستلمين: " + if (targets.firstOrNull() == "ALL") users.size else targets.size)
        toast("تم إرسال الإشعار إلى نظام المزامنة")
    }

    private fun reload() {
        users = AuthManager.allNonAdminUsers()
        val active = users.count { it.isApproved && !it.isSuspended }
        val stopped = users.count { it.isSuspended || !it.isApproved }
        val logs = AuthManager.getLogs()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val monthAgo = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        val recent = logs.count { (runCatching { dateFormat.parse(it.time)?.time }.getOrNull() ?: 0L) >= monthAgo }
        statsText.text = "إجمالي المستخدمين: " + users.size +
            "\nالنشطون: " + active + "\nالموقوفون أو غير المعتمدين: " + stopped +
            "\nإجمالي السجلات: " + logs.size + "\nالنشاط خلال آخر 30 يوماً: " + recent
        showUsers(search.text.toString())
        showLogs("")
    }

    private fun visibleUsers(query: String) = users.filter {
        query.isBlank() || it.username.contains(query, true) || it.mobile.contains(query)
    }

    private fun showUsers(query: String) {
        val rows = visibleUsers(query)
        usersList.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows.map {
            it.username + " - " + it.mobile + "\n" +
                when { it.isSuspended -> "موقوف"; !it.isApproved -> "بانتظار الموافقة"; else -> "نشط" }
        })
    }

    private fun showLogs(query: String) {
        reportLogs = AuthManager.getLogs().filter {
            (dateFilter.isBlank() || it.time.startsWith(dateFilter)) &&
                (query.isBlank() || it.mobile.contains(query) || it.username.contains(query, true) || it.action.contains(query, true))
        }
        logsText.text = reportLogs.take(300).joinToString("\n\n") {
            it.time + " | " + it.username + " (" + it.mobile + ")\n" + it.action +
                if (it.details.isBlank()) "" else " - " + it.details
        }.ifBlank { "لا توجد سجلات مطابقة" }
    }

    private fun exportCsv() {
        val csv = buildString {
            append("التاريخ,اسم المستخدم,رقم الهاتف,العملية,التفاصيل\n")
            reportLogs.forEach { log ->
                append(listOf(log.time, log.username, log.mobile, log.action, log.details)
                    .joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" })
                append("\n")
            }
        }
        val share = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(android.content.Intent.EXTRA_SUBJECT, "سجلات المستخدمين")
            putExtra(android.content.Intent.EXTRA_TEXT, csv)
        }
        startActivity(android.content.Intent.createChooser(share, "تصدير CSV (يمكن فتحه في Excel)"))
        AuthManager.auditAdmin("تصدير تقرير CSV/Excel", "عدد السجلات: " + reportLogs.size)
    }

    private fun exportPdf() {
        val rows = reportLogs.map { arrayOf(it.time, it.username, it.mobile, it.action, it.details) }
        PdfReport.print(this, PdfReport.build(this, "تقرير سجلات المستخدمين", rows), "سجلات المستخدمين")
        AuthManager.auditAdmin("طباعة تقرير PDF", "عدد السجلات: " + reportLogs.size)
    }

    private fun heading(value: String) {
        content.addView(TextView(this).apply {
            text = value; textSize = 16f; setTextColor(teal)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(8))
        })
    }
    private fun label(value: String) = TextView(this).apply {
        text = value; textSize = 13f; setTextColor(0xFF263238.toInt())
        setPadding(dp(10), dp(10), dp(10), dp(10)); setBackgroundColor(-1)
    }
    private fun actionButton(value: String, action: () -> Unit) = Button(this).apply {
        text = value; setOnClickListener { action() }
    }
    private fun full() = LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()
}
