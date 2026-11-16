package com.albayda.court

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
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
import java.util.Calendar
import java.util.Locale

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
        setupDatePicker()
        configureFields()

        binding.btnSearch.setOnClickListener { performSearch() }
    }

    private fun setupDatePicker() {
        val openPicker = {
            val cal = Calendar.getInstance()
            DatePickerDialog(this, { _, y, m, d ->
                binding.etDate.setText(String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d))
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }
        binding.etDate.isFocusable = false
        binding.etDate.isClickable = true
        binding.etDate.setOnClickListener { openPicker() }
    }

    private fun configureFields() {
        fun v(view: View, show: Boolean) { view.visibility = if (show) View.VISIBLE else View.GONE }
        when (mode) {
            "case" -> {
                v(binding.etCaseNumber, true); v(binding.etPlaintiff, true); v(binding.etDefendant, true)
                v(binding.spCaseType, true); v(binding.spJudge, true); v(binding.etDate, false); v(binding.etQuery, true)
                binding.etQuery.hint = "بحث عام (رقم/اسم)"
                binding.btnSearch.text = "بحث"
                loadDropdowns(loadCaseTypes = true, loadJudges = true)
            }
            "sessions" -> {
                v(binding.etCaseNumber, false); v(binding.etPlaintiff, false); v(binding.etDefendant, false)
                v(binding.spCaseType, false); v(binding.spJudge, true); v(binding.etDate, true); v(binding.etQuery, false)
                binding.etDate.hint = "اختر تاريخ الجلسة"
                binding.btnSearch.text = "بحث"
                loadDropdowns(loadCaseTypes = false, loadJudges = true)
            }
            "supreme" -> {
                v(binding.etCaseNumber, false); v(binding.etPlaintiff, false); v(binding.etDefendant, false)
                v(binding.spCaseType, false); v(binding.spJudge, false); v(binding.etDate, false); v(binding.etQuery, true)
                binding.etQuery.hint = "اسم الطاعن / الملتمس"
                binding.btnSearch.text = "بحث"
            }
            "attendance" -> {
                v(binding.etCaseNumber, true); v(binding.etPlaintiff, true); v(binding.etDefendant, true)
                v(binding.spCaseType, true); v(binding.spJudge, true); v(binding.etDate, true); v(binding.etQuery, false)
                binding.etDate.hint = "اختر تاريخ الجلسة"
                binding.btnSearch.text = "تنزيل الاعلان pdf"
                loadDropdowns(loadCaseTypes = true, loadJudges = true)
            }
            else -> {}
        }
    }

    private fun loadDropdowns(loadCaseTypes: Boolean, loadJudges: Boolean) {
        scope.launch {
            try {
                val records = CaseRecordsRepository.loadRecords()
                if (loadCaseTypes) {
                    val types = listOf("نوع القضية (الكل)") + records.map { it.caseType.trim() }.filter { it.isNotBlank() }.distinct().sorted()
                    binding.spCaseType.adapter = makeSpinnerAdapter(types)
                }
                if (loadJudges) {
                    val judges = listOf("اسم القاضي (الكل)") + records.map { it.judge.trim() }.filter { it.isNotBlank() }.distinct().sorted()
                    binding.spJudge.adapter = makeSpinnerAdapter(judges)
                }
            } catch (_: Exception) {
                if (loadCaseTypes) binding.spCaseType.adapter = makeSpinnerAdapter(listOf("نوع القضية (الكل)"))
                if (loadJudges) binding.spJudge.adapter = makeSpinnerAdapter(listOf("اسم القاضي (الكل)"))
            }
        }
    }

    private fun makeSpinnerAdapter(items: List<String>): ArrayAdapter<String> {
        return ArrayAdapter(this, R.layout.spinner_item, items).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item)
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

    private fun selected(sp: android.widget.Spinner): String = sp.selectedItem?.toString()?.let {
        if (it == "الكل" || it.contains("(الكل)")) "" else it
    } ?: ""

    private suspend fun searchCase() {
        val records = CaseRecordsRepository.loadRecords()
        val results = CaseRecordsRepository.search(
            records,
            caseNumber = binding.etCaseNumber.text.toString(),
            plaintiff = binding.etPlaintiff.text.toString(),
            defendant = binding.etDefendant.text.toString(),
            caseType = selected(binding.spCaseType),
            judge = selected(binding.spJudge),
            query = binding.etQuery.text.toString()
        )
        if (results.isEmpty()) { addResultText("لا توجد نتائج مطابقة."); return }
        addResultText("تم العثور على ${results.size} نتيجة:")
        results.forEach { addCaseCard(it) }
    }

    private suspend fun searchSessions() {
        val date = binding.etDate.text.toString().trim()
        val judge = selected(binding.spJudge)
        if (date.isBlank()) { addResultText("يرجى اختيار تاريخ الجلسة."); return }
        val records = CaseRecordsRepository.loadRecords()
        val results = records.filter { rec ->
            rec.nextSession.trim() == date && (judge.isBlank() || rec.judge.trim() == judge || rec.judge.contains(judge))
        }
        if (results.isEmpty()) { addResultText("لا توجد جلسات مسجلة بهذا التاريخ للقاضي المحدد."); return }
        val judgeLabel = if (judge.isBlank()) "جميع القضاة" else judge
        addResultText("عدد الجلسات: ${results.size} — $judgeLabel — $date")
        addSessionsDownloadCard(date, judgeLabel, results)
        // show compact list
        results.forEach { r ->
            val tv = TextView(this).apply {
                text = "• ${r.caseNumber} | ${r.plaintiff} × ${r.defendant} | ${r.caseType}"
                setTextColor(getColor(R.color.text_blue))
                textSize = 14f
                setPadding(8, 8, 8, 8)
            }
            binding.resultsContainer.addView(tv)
        }
    }

    private fun addSessionsDownloadCard(date: String, judgeLabel: String, results: List<CaseRecord>) {
        val card = layoutInflater.inflate(R.layout.item_result_card, binding.resultsContainer, false)
        card.findViewById<TextView>(R.id.tvMain).text = "قائمة الجلسات"
        card.findViewById<TextView>(R.id.tvSub).text = "$judgeLabel — $date — عدد القضايا: ${results.size}"
        card.findViewById<View>(R.id.btnDownloadPdf).setOnClickListener {
            try {
                val pdf = PdfReport.buildSessions(this, date, judgeLabel, results)
                val safeDate = date.replace("/", "-").replace("\\", "-")
                val safeJudge = if (judgeLabel == "جميع القضاة") "" else "_$judgeLabel"
                PdfReport.saveToDownloads(this, pdf, "قائمة جلسات ${safeDate}${safeJudge}")
            } catch (e: Exception) {
                Toast.makeText(this, "تعذر إنشاء الملف: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        binding.resultsContainer.addView(card)
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
        val caseType = selected(binding.spCaseType).ifBlank { "غير متوفر" }
        val plaintiff = binding.etPlaintiff.text.toString().trim().ifBlank { "غير متوفر" }
        val defendant = binding.etDefendant.text.toString().trim().ifBlank { "غير متوفر" }
        val judge = selected(binding.spJudge).ifBlank { "غير متوفر" }
        val date = binding.etDate.text.toString().trim()
        if (caseNo.isBlank() || date.isBlank()) { addResultText("يرجى إدخال رقم القضية وتاريخ الجلسة."); return }
        val data = linkedMapOf(
            "رقم القضية" to caseNo,
            "نوع القضية" to caseType,
            "المدعي" to plaintiff,
            "المدعى عليه" to defendant,
            "اسم القاضي" to judge,
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
            "القاضي المختص" to r.judge,
            "رقم القضية" to r.caseNumber,
            "تاريخ الورود" to r.receivedDate,
            "موضوع القضية" to r.subject,
            "نوع القضية" to r.caseType,
            "المدعي" to r.plaintiff,
            "المدعى عليه" to r.defendant,
            "آخر قرار للقضية" to r.status,
            "تاريخ الجلسة القادمة" to r.nextSession
        )
        addGenericCard(r.caseNumber.ifBlank { "سجل" }, data)
    }

    private fun addGenericCard(title: String, data: Map<String, String>) {
        val card = layoutInflater.inflate(R.layout.item_result_card, binding.resultsContainer, false)
        card.findViewById<TextView>(R.id.tvMain).text = title
        val sub = listOfNotNull(data["المدعي"], data["المدعى عليه"], data["القاضي"] ?: data["القاضي المختص"], data["حالة القضية"] ?: data["آخر قرار للقضية"]).filter { it.isNotBlank() }.joinToString(" | ")
        card.findViewById<TextView>(R.id.tvSub).text = sub.ifBlank { data.entries.take(2).joinToString(" | ") { "${it.key}: ${it.value}" } }

        val fileName = buildPdfFileName(title, data)
        card.findViewById<View>(R.id.btnDownloadPdf).setOnClickListener {
            try {
                val pdf = when (mode) {
                    "attendance" -> PdfReport.buildAttendance(this, data)
                    "supreme" -> {
                        val rows = data.map { arrayOf(it.key, it.value) }
                        PdfReport.build(this, "نتائج المحكمة العليا", rows)
                    }
                    else -> PdfReport.buildCaseInfo(this, data)
                }
                PdfReport.saveToDownloads(this, pdf, fileName)
            } catch (e: Exception) {
                Toast.makeText(this, "تعذر إنشاء الملف: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        binding.resultsContainer.addView(card)
    }

    private fun buildPdfFileName(title: String, data: Map<String, String>): String {
        fun safe(s: String) = s.replace("/", "-").replace("\\", "-").replace(":", "-").trim()
        val caseNo = data["رقم القضية"] ?: data["الرقم الخاص بخدمة الرسائل القصيرة"] ?: title
        val date = data["تاريخ الجلسة"] ?: data["الجلسة القادمة"] ?: binding.etDate.text.toString().trim()
        return when (mode) {
            "sessions" -> "قائمة جلسات ${safe(if (date.isNotBlank()) date else "اليوم")}"
            "attendance" -> "اعلان حضور القضية رقم ${safe(caseNo)}"
            "supreme" -> "معلومات القضية ${safe(caseNo)} بالمحكمة العليا"
            else -> "معلومات القضية ${safe(caseNo)}"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
