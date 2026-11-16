package com.albayda.court

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.albayda.court.data.CaseRepository
import com.albayda.court.data.SupremeCourtRepository
import com.albayda.court.databinding.ActivitySearchBinding
import com.albayda.court.util.PdfReport
import kotlinx.coroutines.*

class SearchActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySearchBinding
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val mode = intent.getStringExtra("MODE") ?: "case"
        binding.tvTitle.text = when (mode) {
            "case" -> "الاستعلام عن قضية"
            "sessions" -> "الجلسات اليومية"
            "supreme" -> "المحكمة العليا"
            "attendance" -> "إنشاء إعلان حضور"
            else -> "بحث"
        }

        binding.btnSearch.setOnClickListener {
            val query = binding.etQuery.text.toString().trim()
            if (query.isEmpty() && mode != "attendance") {
                Toast.makeText(this, "يرجى إدخال بيانات البحث", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            performSearch(mode, query)
        }
    }

    private fun performSearch(mode: String, query: String) {
        binding.pb.isVisible = true
        binding.resultsContainer.removeAllViews()

        scope.launch {
            try {
                val results = withContext(Dispatchers.IO) {
                    when (mode) {
                        "case" -> {
                            val method = if (query.contains("/")) "number" else "name"
                            CaseRepository.search(CaseRepository.load(), query, method, "all", "all", 20)
                        }
                        "sessions" -> CaseRepository.sessions(CaseRepository.load(), query)
                        "supreme" -> SupremeCourtRepository.search(query, "0", "0", "")
                        "attendance" -> listOf(mapOf("type" to "attendance_form", "query" to query)) // منطق خاص للإعلان
                        else -> emptyList()
                    }
                }

                binding.pb.isVisible = false
                if (results.isEmpty()) {
                    addResultText("لا توجد نتائج مطابقة لبحثك.")
                } else {
                    addResultText("تم العثور على ${results.size} نتيجة:")
                    for (item in results) {
                        addResultCard(item, mode)
                    }
                }
            } catch (e: Exception) {
                binding.pb.isVisible = false
                Toast.makeText(this@SearchActivity, "خطأ: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun addResultText(text: String) {
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.text_blue))
            textSize = 16f
            setPadding(16, 16, 16, 8)
            textStyle = android.graphics.Typeface.BOLD
        }
        binding.resultsContainer.addView(tv)
    }

    private fun addResultCard(data: Map<String, String>, mode: String) {
        // هنا يتم إنشاء بطاقة نتيجة واحدة (بديل القوائم المنسدلة المعطوبة)
        val card = layoutInflater.inflate(R.layout.item_result_card, binding.resultsContainer, false)
        
        val mainText = data["رقم القضية"] ?: data["المدعي"] ?: "سجل"
        val subText = "${data["المدعى عليه"] ?: ""} | ${data["الجلسة القادمة"] ?: ""} | ${data["حالة القضية"] ?: ""}"
        
        card.findViewById<TextView>(R.id.tvMain).text = mainText
        card.findViewById<TextView>(R.id.tvSub).text = subText

        // زر تحميل PDF
        card.findViewById<View>(R.id.btnDownloadPdf).setOnClickListener {
            val pdfBytes = PdfReport.build(this, "تقرير استعلام", data.toList().map { arrayOf(it.first, it.second) })
            val fileName = "تقرير_${System.currentTimeMillis()}.pdf"
            PdfReport.saveToDownloads(this, pdfBytes, fileName)
        }

        // زر طباعة
        card.findViewById<View>(R.id.btnPrint).setOnClickListener {
            val pdfBytes = PdfReport.build(this, "تقرير استعلام", data.toList().map { arrayOf(it.first, it.second) })
            PdfReport.print(this, pdfBytes, "طباعة تقرير المحكمة")
        }

        binding.resultsContainer.addView(card)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}