package com.albayda.court

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
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
                delay(500) // محاكاة تحميل
                val mockResults = listOf(
                    mapOf(
                        "رقم القضية" to "123/2024",
                        "المدعي" to "أحمد",
                        "المدعى عليه" to "محمد",
                        "حالة القضية" to "منظورة",
                        "الجلسة القادمة" to "2024-12-01"
                    ),
                    mapOf(
                        "رقم القضية" to "456/2024",
                        "المدعي" to "علي",
                        "المدعى عليه" to "حسن",
                        "حالة القضية" to "محكوم",
                        "الجلسة القادمة" to "لا يوجد"
                    )
                )

                binding.pb.isVisible = false
                if (mockResults.isEmpty()) {
                    addResultText("لا توجد نتائج مطابقة لبحثك.")
                } else {
                    addResultText("تم العثور على ${mockResults.size} نتيجة:")
                    for (item in mockResults) addResultCard(item, mode)
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
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        binding.resultsContainer.addView(tv)
    }

    private fun addResultCard(data: Map<String, String>, mode: String) {
        val card = layoutInflater.inflate(
            R.layout.item_result_card, binding.resultsContainer, false
        )
        card.findViewById<TextView>(R.id.tvMain).text = data["رقم القضية"] ?: "سجل"
        card.findViewById<TextView>(R.id.tvSub).text =
            "${data["المدعي"]} | ${data["المدعى عليه"]} | ${data["حالة القضية"]}"

        card.findViewById<View>(R.id.btnDownloadPdf).setOnClickListener {
            val rows = data.map { arrayOf(it.key, it.value ?: "") }
            val pdf = PdfReport.build(this, "تقرير استعلام", rows)
            PdfReport.saveToDownloads(this, pdf, "تقرير_${System.currentTimeMillis()}.pdf")
        }

        card.findViewById<View>(R.id.btnPrint).setOnClickListener {
            val rows = data.map { arrayOf(it.key, it.value ?: "") }
            val pdf = PdfReport.build(this, "تقرير استعلام", rows)
            PdfReport.print(this, pdf, "طباعة تقرير المحكمة")
        }

        binding.resultsContainer.addView(card)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}