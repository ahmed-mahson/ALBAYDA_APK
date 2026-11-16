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
        val q = name.trim()
        if (q.isBlank()) throw Exception("يرجى إدخال اسم الطاعن/الملتمس.")
        val url = "$SEARCH_URL?T1=${enc(q)}&D1=0&D2=0&T2=&S1=&B1=%D8%A8%D8%AD%D8%AB"
        val doc = get(url)
        val table = doc.selectFirst("table#GridView1") ?: throw Exception("لم يتم العثور على نتائج مطابقة في المحكمة العليا.")
        val allRows = table.select("tr").map { tr -> tr.select("th,td").map { it.text().trim() } }.filter { it.isNotEmpty() }
        if (allRows.size < 2) throw Exception("لم يتم العثور على نتائج مطابقة في المحكمة العليا.")
        val headers = allRows.first()
        val results = mutableListOf<SupremeResult>()
        for (row in allRows.drop(1)) {
            if (row.isEmpty()) continue
            if (row.size == headers.size) {
                // 4-column search result often contains link/number to details
                if (headers.size == 4) {
                    val sms = extractSms(row, headers, doc, table, allRows.indexOf(row))
                    if (sms != null) {
                        results.add(fetchDetails(sms))
                        continue
                    }
                    results.add(SupremeResult(headers.zip(row)))
                    continue
                }
                if (headers.size == 7 && row.size == 7 && row[0].all { it.isDigit() || it.isWhitespace() }) {
                    results.add(SupremeResult(headers.drop(1).zip(row.drop(1))))
                } else if (headers.size == 6) {
                    results.add(SupremeResult(headers.zip(row)))
                } else {
                    results.add(SupremeResult(headers.zip(row)))
                }
            } else if (row.size == 6 && headers.size >= 6) {
                results.add(SupremeResult(headers.take(6).zip(row.take(6))))
            }
        }
        if (results.isEmpty()) throw Exception("لم يتم العثور على نتائج مطابقة في المحكمة العليا.")
        results
    }

    private fun extractSms(row: List<String>, headers: List<String>, doc: Document, table: org.jsoup.nodes.Element, rowIndex: Int): String? {
        // Try header-based detection
        val idx = headers.indexOfFirst { h -> h.contains("SMS") || h.contains("الرسائل") || h.contains("sms", true) || h.contains("الرقم الخاص") }
        if (idx >= 0 && idx < row.size) {
            val v = row[idx].filter { it.isDigit() }
            if (v.isNotBlank()) return v
        }
        // Try any cell that looks like details id
        for (cell in row) {
            val v = cell.filter { it.isDigit() }
            if (v.length >= 3 && v.length <= 12) return v
        }
        // Try links in the HTML row
        val trs = table.select("tr")
        if (rowIndex in trs.indices) {
            val links = trs[rowIndex].select("a[href]")
            for (a in links) {
                val href = a.attr("href")
                val m = Regex("Sms_c=(\\d+)").find(href)
                if (m != null) return m.groupValues[1]
                val txt = a.text().filter { it.isDigit() }
                if (txt.isNotBlank()) return txt
            }
        }
        return null
    }

    private fun fetchDetails(sms: String): SupremeResult {
        val doc = get("$DETAILS_URL?Sms_c=$sms")
        val table = doc.selectFirst("table#GridView1") ?: throw Exception("تعذر قراءة تفاصيل النتيجة.")
        val allRows = table.select("tr").map { tr -> tr.select("th,td").map { it.text().trim() } }.filter { it.isNotEmpty() }
        if (allRows.size < 2) throw Exception("تعذر قراءة تفاصيل النتيجة.")
        val headers = allRows.first()
        val dataRow = allRows.drop(1).firstOrNull { it.size == headers.size } ?: allRows.drop(1).first()
        val h = if (headers.size == 7) headers.drop(1) else headers
        val v = if (dataRow.size == 7 && headers.size == 7) dataRow.drop(1) else if (dataRow.size == h.size) dataRow else dataRow.take(h.size)
        val pairs = h.zip(v).filter { it.first.isNotBlank() }
        if (pairs.isEmpty()) throw Exception("تعذر قراءة تفاصيل النتيجة.")
        return SupremeResult(pairs)
    }

    private fun get(url: String): Document {
        return Jsoup.connect(url)
            .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .referrer("https://ysc.org.ye/")
            .timeout(25000)
            .get()
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}

