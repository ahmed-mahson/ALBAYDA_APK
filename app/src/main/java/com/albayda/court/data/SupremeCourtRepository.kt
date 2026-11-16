package com.albayda.court.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

data class SupremeResult(val rows: List<Pair<String, String>>)

object SupremeCourtRepository {
    private const val SEARCH_URL = "https://ysc.org.ye/Search_Result.aspx"
    private const val DETAILS_URL = "https://ysc.org.ye/Details.aspx"

    suspend fun search(name: String): List<SupremeResult> = withContext(Dispatchers.IO) {
        val url = "$SEARCH_URL?T1=${enc(name)}&D1=0&D2=0&T2=&S1=&B1=%D8%A8%D8%AD%D8%AB"
        val doc = get(url)
        val table = doc.selectFirst("table#GridView1") ?: throw Exception("لم يتم العثور على نتائج مطابقة في المحكمة العليا.")
        val allRows = table.select("tr").map { tr -> tr.select("th,td").map { it.text().trim() } }.filter { it.isNotEmpty() }
        if (allRows.size < 2) throw Exception("لم يتم العثور على نتائج مطابقة في المحكمة العليا.")
        val headers = allRows.first()
        val results = mutableListOf<SupremeResult>()
        for (row in allRows.drop(1)) {
            if (row.size == headers.size && row.size >= 4) {
                // Try detect SMS/details number in cells (often 4-col with sms number)
                val smsIdx = headers.indexOfFirst { it.contains("SMS") || it.contains("الرسائل") || it.contains("الرقم") }
                if (row.size == 4 && smsIdx >= 0) {
                    val sms = row[smsIdx].filter { it.isDigit() }
                    if (sms.isNotEmpty()) {
                        results.add(fetchDetails(sms))
                        continue
                    }
                }
                if (row.size == 7 && row[0].all { it.isDigit() }) {
                    val h = headers.drop(1)
                    val v = row.drop(1)
                    results.add(SupremeResult(h.zip(v)))
                } else if (row.size == 6) {
                    results.add(SupremeResult(headers.take(6).zip(row.take(6))))
                }
            }
        }
        if (results.isEmpty()) throw Exception("لم يتم العثور على نتائج مطابقة في المحكمة العليا.")
        results
    }

    private fun fetchDetails(sms: String): SupremeResult {
        val doc = get("$DETAILS_URL?Sms_c=$sms")
        val table = doc.selectFirst("table#GridView1") ?: throw Exception("تعذر قراءة تفاصيل النتيجة.")
        val allRows = table.select("tr").map { tr -> tr.select("th,td").map { it.text().trim() } }.filter { it.isNotEmpty() }
        if (allRows.size < 2) throw Exception("تعذر قراءة تفاصيل النتيجة.")
        val headers = allRows.first()
        val row = allRows.drop(1).firstOrNull { it.size == headers.size } ?: throw Exception("تعذر قراءة تفاصيل النتيجة.")
        val h = if (headers.size == 7 && row.size == 7) headers.drop(1) else headers
        val v = if (row.size == 7 && headers.size == 7) row.drop(1) else row
        return SupremeResult(h.zip(v))
    }

    private fun get(url: String): Document {
        return Jsoup.connect(url)
            .userAgent("Mozilla/5.0")
            .timeout(20000)
            .get()
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
