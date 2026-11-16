package com.albayda.court

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.albayda.court.data.CaseRecord
import com.albayda.court.data.CaseRecordsRepository
import com.albayda.court.data.SupremeCourtRepository
import com.albayda.court.databinding.ActivitySearchBinding
import com.albayda.court.util.PdfReport
import kotlinx.coroutines.*

class SearchActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySearchBinding
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var mode: String = "case"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mode = intent.getStringExtra("MODE") ?: "case"
        binding.tvTitle.text = when (mode) {
            "case" -> "الاستعلام عن قضية"
            "sessions" -> "الجلسات اليومية"
            "supreme" -> "المحكمة العليا"
            "attendance" -> "إنشاء إعلان حضور"
            else -> "بحث"
        }
        configureFields()

        binding.btnSearch.setOnClickListener { performSearch() }
    }

    private fun configureFields() {
        fun v(view: View, show: Boolean) { view.visibility = if (show) View.VISIBLE else View.GONE }
        when (mode) {
            "case" -> {
                v(binding.etCaseNumber, true); v(binding.etPlaintiff, true); v(binding.etDefendant, true)
                v(binding.etCaseType, true); v(binding.etJudge, true); v(binding.etDate, false); v(binding.etQuery, true)
                binding.etQuery.hint = "بحث عام (رقم/اسم)"
            }
            "sessions" -> {
                v(binding.etCaseNumber, false); v(binding.etPlaintiff, false); v(binding.etDefendant, false)
                v(binding.etCaseType, false); v(binding.etJudge, true); v(binding.etDate, true); v(binding.etQuery, false)
                binding.etDate.hint = "تاريخ الجلسة (YYYY-MM-DD)"
            }
            "supreme" -> {
                v(binding.etCaseNumber, false); v(binding.etPlaintiff, false); v(binding.etDefendant, false)
                v(binding.etCaseType, false); v(binding.etJudge, false); v(binding.etDate, false); v(binding.etQuery, true)
                binding.etQuery.hint = "اسم الطاعن / الملتمس"
            }
            "attendance" -> {
                v(binding.etCaseNumber, true); v(binding.etPlaintiff, true); v(binding.etDefendant, true)
                v(binding.etCaseType, false); v(binding.etJudge, true); v(binding.etDate, true); v(binding.etQuery, false)
                binding.etDate.hint = "تاريخ الجلسة (YYYY-MM-DD)"
            }
            else -> {}
        }
    }

    private fun performSearch() {
        binding.pb.isVisible = true
        binding.resultsContainer.removeAllViews()
        scope.launch {
            try {
                when (mode) {
                    "case" -> searchCase()
                    "sessions" -> searchSessions()
                    "supreme" -> searchSupreme()
                    "attendance" -> buildAttendance()
                    else -> addResultText("وضع غير مدعوم")
                }
            } catch (e: Exception) {
                addResultText("حدث خطأ: ${e.message ?: "غير معروف"}")
            } finally {
                binding.pb.isVisible = false
            }
        }
    }

    private suspend fun searchCase() {
        val records = CaseRecordsRepository.loadRecords()
        val results = CaseRecordsRepository.search(
            records,
            caseNumber = binding.etCaseNumber.text.toString(),
            plaintiff = binding.etPlaintiff.text.toString(),
            defendant = binding.etDefendant.text.toString(),
            caseType = binding.etCaseType.text.toString(),
            judge = binding.etJudge.text.toString(),
            query = binding.etQuery.text.toString()
        )
        if (results.isEmpty()) { addResultText("لا توجد نتائج مطابقة."); return }
        addResultText("تم العثور على ${results.size} نتيجة:")
        results.forEach { addCaseCard(it) }
    }

    private suspend fun searchSessions() {
        val date = binding.etDate.text.toString().trim()
        val judge = binding.etJudge.text.toString().trim()
        if (date.isBlank()) { addResultText("يرجى إدخال تاريخ الجلسة."); return }
        val records = CaseRecordsRepository.loadRecords()
        val results = records.filter { it.nextSession.trim() == date && (judge.isBlank() || it.judge.contains(judge)) }.take(100)
        if (results.isEmpty()) { addResultText("لا توجد جلسات مسجلة بهذا التاريخ."); return }
        addResultText("عدد الجلسات: ${results.size}")
        results.forEach { addCaseCard(it) }
    }

    private suspend fun searchSupreme() {
        val name = binding.etQuery.text.toString().trim()
        if (name.isBlank()) { addResultText("يرجى إدخال اسم الطاعن/الملتمس."); return }
        val results = SupremeCourtRepository.search(name)
        addResultText("عدد النتائج: ${results.size}")
        results.forEachIndexed { idx, r ->
            val map = linkedMapOf<String, String>()
            r.rows.forEach { map[it.first] = it.second }
            addGenericCard("نتيجة ${idx + 1}", map)
        }
    }

    private fun buildAttendance() {
        val caseNo = binding.etCaseNumber.text.toString().trim()
        val plaintiff = binding.etPlaintiff.text.toString().trim().ifBlank { "غير متوفر" }
        val defendant = binding.etDefendant.text.toString().trim().ifBlank { "غير متوفر" }
        val judge = binding.etJudge.text.toString().trim().ifBlank { "غير متوفر" }
        val date = binding.etDate.text.toString().trim()
        if (caseNo.isBlank() || date.isBlank()) { addResultText("يرجى إدخال رقم القضية وتاريخ الجلسة."); return }
        val data = linkedMapOf(
            "رقم القضية" to caseNo,
            "المدعي" to plaintiff,
            "المدعى عليه" to defendant,
            "القاضي" to judge,
            "تاريخ الجلسة" to date
        )
        addResultText("تم تجهيز بيانات إعلان الحضور:")
        addGenericCard("إعلان حضور", data)
    }

    private fun addResultText(text: String) {
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.text_blue))
            textSize = 16f
            setPadding(16, 16, 16, 8)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        binding.resultsContainer.addView(tv)
    }

    private fun addCaseCard(r: CaseRecord) {
        val data = linkedMapOf(
            "رقم القضية" to r.caseNumber,
            "نوع القضية" to r.caseType,
            "موضوعها" to r.subject,
            "المدعي" to r.plaintiff,
            "المدعى عليه" to r.defendant,
            "القاضي" to r.judge,
            "حالة القضية" to r.status,
            "الجلسة القادمة" to r.nextSession
        )
        addGenericCard(r.caseNumber.ifBlank { "سجل" }, data)
    }

    private fun addGenericCard(title: String, data: Map<String, String>) {
        val card = layoutInflater.inflate(R.layout.item_result_card, binding.resultsContainer, false)
        card.findViewById<TextView>(R.id.tvMain).text = title
        val sub = listOfNotNull(data["المدعي"], data["المدعى عليه"], data["القاضي"], data["حالة القضية"]).filter { it.isNotBlank() }.joinToString(" | ")
        card.findViewById<TextView>(R.id.tvSub).text = sub.ifBlank { data.entries.take(2).joinToString(" | ") { "${it.key}: ${it.value}" } }

        card.findViewById<View>(R.id.btnDownloadPdf).setOnClickListener {
            val rows = data.map { arrayOf(it.key, it.value) }
            val pdf = PdfReport.build(this, title, rows)
            PdfReport.saveToDownloads(this, pdf, "تقرير_${System.currentTimeMillis()}.pdf")
        }
        card.findViewById<View>(R.id.btnPrint).setOnClickListener {
            val rows = data.map { arrayOf(it.key, it.value) }
            val pdf = PdfReport.build(this, title, rows)
            PdfReport.print(this, pdf, "طباعة تقرير المحكمة")
        }
        binding.resultsContainer.addView(card)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
