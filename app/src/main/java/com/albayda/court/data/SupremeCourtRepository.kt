package com.albayda.court.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Connection
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

data class SupremeResult(val rows: List<Pair<String, String>>)

object SupremeCourtRepository {
    private const val BASE = "https://ysc.org.ye/"
    private const val SEARCH_PAGE = "${BASE}Search.aspx"
    private const val SEARCH_URL = "${BASE}Search_Result.aspx"
    private const val DETAILS_URL = "${BASE}Details.aspx"

    suspend fun search(name: String): List<SupremeResult> = withContext(Dispatchers.IO) {
        val q = name.trim()
        if (q.isBlank()) throw Exception("يرجى إدخال اسم الطاعن/الملتمس.")

        val cookies = openSearchSession()
        val url = "$SEARCH_URL?T1=${enc(q)}&D1=0&D2=0&T2=&S1=&B1=${enc("بحث")}"
        val doc = get(url, cookies)
        val table = doc.selectFirst("table#GridView1")
            ?: throw Exception("لم يتم العثور على نتائج مطابقة في المحكمة العليا.")
        val rows = table.select("tr").mapIndexed { index, tr ->
            index to tr.select("th,td").map { it.text().trim() }
        }.filter { it.second.isNotEmpty() }
        if (rows.size < 2) throw Exception("لم يتم العثور على نتائج مطابقة في المحكمة العليا.")

        val headers = rows.first().second
        val results = mutableListOf<SupremeResult>()
        var detailsFetched = 0

        for ((rowIndex, row) in rows.drop(1)) {
            if (row.size < 2) continue
            if (row.first().contains("نتائج البحث", true)) continue

            val sms = extractSms(row, headers, table, rowIndex)
            if (sms != null && detailsFetched < 12) {
                val detailed = runCatching { fetchDetails(sms, cookies) }.getOrNull()
                if (detailed != null && detailed.rows.isNotEmpty()) {
                    results.add(detailed)
                    detailsFetched++
                    continue
                }
            }
            val pairs = headers.zip(row).filter {
                it.first.isNotBlank() && !it.first.contains("التفاصيل")
            }
            if (pairs.isNotEmpty()) results.add(SupremeResult(pairs))
        }

        if (results.isEmpty()) throw Exception("لم يتم العثور على نتائج مطابقة في المحكمة العليا.")
        results.take(30)
    }

    private fun openSearchSession(): Map<String, String> {
        return try {
            Jsoup.connect(SEARCH_PAGE)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .referrer(BASE)
                .timeout(15_000)
                .execute()
                .cookies()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun extractSms(
        row: List<String>,
        headers: List<String>,
        table: org.jsoup.nodes.Element,
        rowIndex: Int
    ): String? {
        val idx = headers.indexOfFirst {
            it.contains("SMS", true) || it.contains("الرسائل") || it.contains("الرقم الخاص")
        }
        if (idx >= 0 && idx < row.size) {
            val value = row[idx].filter { it.isDigit() }
            if (value.length in 3..12) return value
        }
        val trs = table.select("tr")
        if (rowIndex in trs.indices) {
            for (a in trs[rowIndex].select("a[href]")) {
                val href = a.attr("href")
                Regex("Sms_c=(\\d+)").find(href)?.let { return it.groupValues[1] }
                val text = a.text().filter { it.isDigit() }
                if (text.length in 3..12) return text
            }
        }
        for (cell in row) {
            val value = cell.filter { it.isDigit() }
            if (value.length in 6..9) return value
        }
        return null
    }

    private fun fetchDetails(sms: String, cookies: Map<String, String>): SupremeResult {
        val doc = get("$DETAILS_URL?Sms_c=$sms", cookies)
        val table = doc.selectFirst("table#GridView1")
            ?: throw Exception("تعذر قراءة تفاصيل النتيجة.")
        val allRows = table.select("tr").map { tr -> tr.select("th,td").map { it.text().trim() } }
            .filter { it.isNotEmpty() }
        if (allRows.size < 2) throw Exception("تعذر قراءة تفاصيل النتيجة.")
        val headers = allRows.first()
        val dataRow = allRows.drop(1).firstOrNull { it.size == headers.size }
            ?: throw Exception("تعذر قراءة تفاصيل النتيجة.")
        val pairs = headers.zip(dataRow).filter { it.first.isNotBlank() }
        if (pairs.isEmpty()) throw Exception("تعذر قراءة تفاصيل النتيجة.")
        return SupremeResult(pairs)
    }

    private fun get(url: String, cookies: Map<String, String> = emptyMap()): Document {
        val connection: Connection = Jsoup.connect(url)
            .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .referrer(BASE)
            .timeout(25_000)
            .ignoreHttpErrors(true)
            .ignoreContentType(true)
        if (cookies.isNotEmpty()) connection.cookies(cookies)
        return connection.get()
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
