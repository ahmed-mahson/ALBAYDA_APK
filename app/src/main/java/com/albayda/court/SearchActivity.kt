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
        CaseRecordsRepository.init(this)

        mode = intent.getStringExtra("MODE") ?: "case"
        binding.tvTitle.text = when (mode) {
            "case" -> "الاستعلام والبحث عن القضايا"
            "sessions" -> "قائمة الجلسات اليومية"
            "supreme" -> "الاستعلام والبحث بالمحكمة العليا"
            "attendance" -> "انشاء اعلان حضور"
            else -> "بحث"
        }
        binding.btnBack.setOnClickListener { finish() }
        setHeaderIcon()
        setupDatePicker()
        configureFields()

        binding.btnSearch.setOnClickListener { performSearch() }
    }

    private fun setupDatePicker() {
        val openPicker = {
            val cal = Calendar.getInstance()
            DatePickerDialog(this, { _, y, m, d ->
                binding.etDate.setText(String.format(Locale.US, "%02d/%02d/%04d", d, m + 1, y))
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }
        binding.etDate.isFocusable = false
        binding.etDate.isClickable = true
        binding.etDate.setOnClickListener { openPicker() }
    }

    private fun configureFields() {
        fun v(view: View, show: Boolean) { view.visibility = if (show) View.VISIBLE else View.GONE }
        fun pair(field: View, label: View, show: Boolean) { v(field, show); v(label, show) }
        when (mode) {
            "case" -> {
                pair(binding.etCaseNumber, binding.lblCaseNumber, true)
                pair(binding.etPlaintiff, binding.lblPlaintiff, false) // md case search: رقم القضية أو اسم أحد الأطراف, نوع القضية, القاضي المختص
                pair(binding.etDefendant, binding.lblDefendant, false)
                pair(binding.spCaseType, binding.lblCaseType, true)
                pair(binding.spJudge, binding.lblJudge, true)
                pair(binding.etDate, binding.lblDate, false)
                pair(binding.etQuery, binding.lblQuery, false)
                binding.lblCaseNumber.text = "رقم القضية أو اسم أحد الأطراف"
                binding.lblCaseType.text = "نوع القضية"
                binding.lblJudge.text = "القاضي المختص"
                binding.btnSearch.text = "بحث"
                loadDropdowns(loadCaseTypes = true, loadJudges = true)
            }
            "sessions" -> {
                pair(binding.etCaseNumber, binding.lblCaseNumber, false)
                pair(binding.etPlaintiff, binding.lblPlaintiff, false)
                pair(binding.etDefendant, binding.lblDefendant, false)
                pair(binding.spCaseType, binding.lblCaseType, false)
                pair(binding.spJudge, binding.lblJudge, true)
                pair(binding.etDate, binding.lblDate, true)
                pair(binding.etQuery, binding.lblQuery, false)
                binding.lblDate.text = "تاريخ الجلسات"
                binding.lblJudge.text = "القاضي المختص"
                binding.etDate.hint = "اختر التاريخ"
                binding.btnSearch.text = "بحث"
                loadDropdowns(loadCaseTypes = false, loadJudges = true)
            }
            "supreme" -> {
                pair(binding.etCaseNumber, binding.lblCaseNumber, false)
                pair(binding.etPlaintiff, binding.lblPlaintiff, false)
                pair(binding.etDefendant, binding.lblDefendant, false)
                pair(binding.spCaseType, binding.lblCaseType, false)
                pair(binding.spJudge, binding.lblJudge, false)
                pair(binding.etDate, binding.lblDate, false)
                pair(binding.etQuery, binding.lblQuery, true)
                binding.lblQuery.text = "اسم الطاعن أو الملتمس"
                binding.etQuery.hint = ""
                binding.btnSearch.text = "بحث"
            }
            "attendance" -> {
                pair(binding.etCaseNumber, binding.lblCaseNumber, true)
                pair(binding.etPlaintiff, binding.lblPlaintiff, true)
                pair(binding.etDefendant, binding.lblDefendant, true)
                pair(binding.spCaseType, binding.lblCaseType, true)
                pair(binding.spJudge, binding.lblJudge, true)
                pair(binding.etDate, binding.lblDate, true)
                pair(binding.etQuery, binding.lblQuery, false)
                binding.lblCaseNumber.text = "رقم القضية"
                binding.lblCaseType.text = "نوع القضية"
                binding.lblPlaintiff.text = "اسم المدعي"
                binding.lblDefendant.text = "اسم المدعى عليه"
                binding.lblJudge.text = "القاضي المختص"
                binding.lblDate.text = "تاريخ الحضور"
                binding.etDate.hint = "اختر التاريخ"
                binding.btnSearch.text = "تنزيل الاعلان PDF"
                loadDropdowns(loadCaseTypes = true, loadJudges = true)
                applyAttendancePrefillIfAny()
            }
            else -> {}
        }
    }

    private fun setHeaderIcon() {
        val iconRes = when (mode) {
            "case" -> android.R.drawable.ic_menu_search
            "sessions" -> android.R.drawable.ic_menu_my_calendar
            "supreme" -> android.R.drawable.ic_menu_compass
            "attendance" -> android.R.drawable.ic_menu_edit
            else -> android.R.drawable.ic_menu_info_details
        }
        binding.ivHeaderIcon.setImageResource(iconRes)
    }

    private fun loadDropdowns(loadCaseTypes: Boolean, loadJudges: Boolean) {
        scope.launch {
            try {
                val records = CaseRecordsRepository.loadRecords()
                if (loadCaseTypes) {
                    val types = listOf("نوع القضية (الكل)") + CaseRecordsRepository.orderedCaseTypes(records)
                    binding.spCaseType.adapter = makeSpinnerAdapter(types)
                    if (mode == "attendance") {
                        selectSpinnerValue(binding.spCaseType, intent.getStringExtra("prefill_case_type"))
                    }
                }
                if (loadJudges) {
                    val judges = listOf("اسم القاضي (الكل)") + records
                        .map { CaseRecordsRepository.displayJudge(it.judge) }
                        .filter { it != "-" }
                        .distinct()
                        .sorted()
                    binding.spJudge.adapter = makeSpinnerAdapter(judges)
                    if (mode == "attendance") {
                        val pj = intent.getStringExtra("prefill_judge")
                        selectSpinnerValue(binding.spJudge, pj, pj?.let { CaseRecordsRepository.displayJudge(it) })
                    }
                }
            } catch (e: Exception) {
                if (loadCaseTypes) binding.spCaseType.adapter = makeSpinnerAdapter(listOf("نوع القضية (الكل)"))
                if (loadJudges) binding.spJudge.adapter = makeSpinnerAdapter(listOf("اسم القاضي (الكل)"))
                Toast.makeText(this@SearchActivity, "تعذر جلب بيانات القضايا: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun makeSpinnerAdapter(items: List<String>): ArrayAdapter<String> {
        val tf = try {
            androidx.core.content.res.ResourcesCompat.getFont(this, R.font.sultan_medium)
                ?: android.graphics.Typeface.createFromAsset(assets, "fonts/sultan-medium.ttf")
        } catch (_: Exception) { null }
        return object : ArrayAdapter<String>(this, R.layout.spinner_item, items) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val v = super.getView(position, convertView, parent)
                (v as? TextView)?.typeface = tf ?: (v as? TextView)?.typeface
                return v
            }
            override fun getDropDownView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val v = super.getDropDownView(position, convertView, parent)
                (v as? TextView)?.typeface = tf ?: (v as? TextView)?.typeface
                return v
            }
        }.apply {
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

    private fun cleanDateInput(raw: String): String {
        return raw.trim()
            .replace('\u200E', ' ')
            .replace('\u200F', ' ')
            .replace('\u202A', ' ')
            .replace('\u202B', ' ')
            .replace('\u202C', ' ')
            .replace('\u202D', ' ')
            .replace('\u202E', ' ')
            .replace(Regex("\\s+"), "")
    }

    private fun applyAttendancePrefillIfAny() {
        val caseNo = intent.getStringExtra("prefill_case_number") ?: return
        binding.etCaseNumber.setText(caseNo)
        binding.etPlaintiff.setText(intent.getStringExtra("prefill_plaintiff") ?: "")
        binding.etDefendant.setText(intent.getStringExtra("prefill_defendant") ?: "")
        val date = intent.getStringExtra("prefill_date") ?: ""
        if (date.isNotBlank()) binding.etDate.setText(CaseRecordsRepository.formatDisplayDate(date))
        // spinner selection is applied after dropdowns load (see loadDropdowns)
    }

    private fun selectSpinnerValue(sp: android.widget.Spinner, wantedRaw: String?, displayWanted: String? = null) {
        val wanted = wantedRaw?.trim().orEmpty()
        val wantedDisp = displayWanted?.trim().orEmpty()
        if (wanted.isBlank() && wantedDisp.isBlank()) return
        val adapter = sp.adapter ?: return
        for (i in 0 until adapter.count) {
            val item = adapter.getItem(i)?.toString()?.trim().orEmpty()
            if (item == wanted || item == wantedDisp || (wanted.isNotBlank() && item.contains(wanted)) || (wantedDisp.isNotBlank() && item.contains(wantedDisp))) {
                sp.setSelection(i)
                return
            }
        }
    }

    private suspend fun searchCase() {
        val caseNumberInput = binding.etCaseNumber.text.toString().trim()
        if (caseNumberInput.contains('/') &&
            CaseRecordsRepository.parseCaseQuery(caseNumberInput) == null
        ) {
            addResultText("صيغة رقم القضية غير صحيحة. استخدم مثلاً 1446/6.")
            return
        }
        val records = CaseRecordsRepository.loadRecords()
        val results = CaseRecordsRepository.search(
            records,
            caseNumber = binding.etCaseNumber.text.toString(),
            plaintiff = binding.etPlaintiff.text.toString(),
            defendant = binding.etDefendant.text.toString(),
            caseType = selected(binding.spCaseType),
            judge = selected(binding.spJudge),
            query = "" // حقل البحث العام أزيل من صفحة الاستعلام عن قضية
        )
        if (results.isEmpty()) { addResultText("لا توجد نتائج مطابقة."); return }
        addResultText("تم العثور على ${results.size} نتيجة:")
        results.forEach { addCaseCard(it) }
    }

    private suspend fun searchSessions() {
        val date = cleanDateInput(binding.etDate.text.toString())
        val judge = selected(binding.spJudge)
        if (date.isBlank()) { addResultText("يرجى اختيار تاريخ الجلسة."); return }
        if (CaseRecordsRepository.normalizeDate(date) == null) {
            addResultText("صيغة التاريخ غير صحيحة. استخدم يوم/شهر/سنة مثل 18/11/2026.")
            return
        }
        val records = CaseRecordsRepository.loadRecords()
        val results = CaseRecordsRepository.searchSessions(records, date, judge)
        if (results.isEmpty()) { addResultText("لا توجد جلسات مسجلة بهذا التاريخ للقاضي المحدد."); return }
        val judgeLabel = if (judge.isBlank()) "جميع القضاة" else CaseRecordsRepository.displayJudge(judge)
        addResultText("عدد الجلسات: ${results.size} — $judgeLabel — ${CaseRecordsRepository.formatDisplayDate(date)}")
        addSessionsDownloadCard(date, judgeLabel, results)
        // show compact list
        results.forEach { r ->
            val tv = TextView(this).apply {
                text = "• ${r.caseNumber} | ${r.plaintiff} × ${r.defendant} | ${r.caseType}"
                setTextColor(getColor(R.color.NavyText))
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
        val date = cleanDateInput(binding.etDate.text.toString())
        if (caseNo.isBlank() || date.isBlank()) { addResultText("يرجى إدخال رقم القضية وتاريخ الجلسة."); return }
        if (CaseRecordsRepository.normalizeDate(date) == null) {
            addResultText("التاريخ غير صحيح. اختر التاريخ بصيغة 18/11/2026.")
            return
        }
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
            setTextColor(getColor(R.color.NavyText))
            textSize = 16f
            setPadding(16, 16, 16, 8)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        binding.resultsContainer.addView(tv)
    }

    private fun addCaseCard(r: CaseRecord) {
        val data = linkedMapOf(
            "رقم القضية" to r.caseNumber,
            "نوع القضية" to CaseRecordsRepository.displayCaseType(r.caseType),
            "موضوعها" to r.subject,
            "المدعي" to r.plaintiff,
            "المدعى عليه" to r.defendant,
            "برئاسة القاضي" to CaseRecordsRepository.displayJudge(r.judge),
            "حالة القضية" to r.status,
            "الجلسة القادمة" to CaseRecordsRepository.formatDisplayDate(r.nextSession),
            "تاريخ الورود" to CaseRecordsRepository.formatDisplayDate(r.receivedDate)
        )
        addGenericCard(
            r.caseNumber.ifBlank { "سجل" },
            data,
            attendanceData = linkedMapOf(
                "prefill_case_number" to r.caseNumber,
                "prefill_case_type" to r.caseType,
                "prefill_plaintiff" to r.plaintiff,
                "prefill_defendant" to r.defendant,
                "prefill_judge" to r.judge,
                "prefill_date" to r.nextSession
            )
        )
    }

    private fun addGenericCard(title: String, data: Map<String, String>, attendanceData: Map<String, String>? = null) {
        val card = layoutInflater.inflate(R.layout.item_result_card, binding.resultsContainer, false)
        card.findViewById<TextView>(R.id.tvMain).text = title
        val sub = listOfNotNull(
            data["المدعي"], data["المدعى عليه"],
            data["القاضي"] ?: data["برئاسة القاضي"] ?: data["القاضي المختص"],
            data["حالة القضية"] ?: data["آخر قرار للقضية"]
        ).filter { it.isNotBlank() }.joinToString(" | ")
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

        val btnAttendance = card.findViewById<View>(R.id.btnCreateAttendance)
        if (mode == "case" && attendanceData != null) {
            btnAttendance.visibility = View.VISIBLE
            btnAttendance.setOnClickListener {
                val intent = android.content.Intent(this, SearchActivity::class.java).apply {
                    putExtra("MODE", "attendance")
                    attendanceData.forEach { (k, v) -> putExtra(k, v) }
                }
                startActivity(intent)
            }
        } else {
            btnAttendance.visibility = View.GONE
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
