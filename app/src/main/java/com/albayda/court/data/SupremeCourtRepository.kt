package com.albayda.court.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Connection
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class SupremeResult(val rows: List<Pair<String, String>>)

object SupremeCourtRepository {
    private const val BASE = "https://ysc.org.ye/"
    private const val SEARCH_PAGE = "${BASE}Search.aspx"
    private const val SEARCH_URL = "${BASE}Search_Result.aspx"
    private const val DETAILS_URL = "${BASE}Details.aspx"
    private const val JINA = "https://r.jina.ai/"

    suspend fun search(name: String): List<SupremeResult> = withContext(Dispatchers.IO) {
        val q = name.trim()
        if (q.isBlank()) throw Exception("يرجى إدخال اسم الطاعن/الملتمس.")
        val query = "$SEARCH_URL?T1=${enc(q)}&D1=0&D2=0&T2=&S1=&B1=${enc("بحث")}"

        // First try the official site directly.
        val direct = runCatching { searchDirect(query) }.getOrNull()
        if (!direct.isNullOrEmpty()) return@withContext direct

        // The site sometimes serves a WAF page instead of the results; use a text proxy.
        val markdown = fetchText(JINA + query)
        val results = parseMarkdownTable(markdown)
        if (results.isEmpty()) throw Exception("لم يتم العثور على نتائج مطابقة في المحكمة العليا.")
        results.take(30)
    }

    private fun searchDirect(queryUrl: String): List<SupremeResult> {
        val cookies = openSearchSession()
        val doc = get(queryUrl, cookies)
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
            if (row.size < 2 || row.first().contains("نتائج البحث", true)) continue
            val sms = extractSms(row, headers, table, rowIndex)
            if (sms != null && detailsFetched < 12) {
                val detailed = runCatching { fetchDetails(sms, cookies) }.getOrNull()
                if (detailed != null && detailed.rows.isNotEmpty()) {
                    results.add(detailed); detailsFetched++; continue
                }
            }
            val pairs = headers.zip(row).filter { it.first.isNotBlank() && !it.first.contains("التفاصيل") }
            if (pairs.isNotEmpty()) results.add(SupremeResult(pairs))
        }
        return results
    }

    private fun openSearchSession(): Map<String, String> = try {
        Jsoup.connect(SEARCH_PAGE)
            .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .referrer(BASE).timeout(15_000).execute().cookies()
    } catch (_: Exception) { emptyMap() }

    private fun extractSms(
        row: List<String>, headers: List<String>,
        table: org.jsoup.nodes.Element, rowIndex: Int
    ): String? {
        val idx = headers.indexOfFirst { it.contains("SMS", true) || it.contains("الرسائل") || it.contains("الرقم الخاص") }
        if (idx >= 0 && idx < row.size) {
            val value = row[idx].filter { it.isDigit() }
            if (value.length in 3..12) return value
        }
        val trs = table.select("tr")
        if (rowIndex in trs.indices) {
            for (a in trs[rowIndex].select("a[href]")) {
                Regex("Sms_c=(\\d+)").find(a.attr("href"))?.let { return it.groupValues[1] }
                val text = a.text().filter { it.isDigit() }
                if (text.length in 3..12) return text
            }
        }
        return null
    }

    private fun fetchDetails(sms: String, cookies: Map<String, String>): SupremeResult {
        val doc = get("$DETAILS_URL?Sms_c=$sms", cookies)
        val table = doc.selectFirst("table#GridView1") ?: throw Exception("تعذر قراءة التفاصيل.")
        val allRows = table.select("tr").map { tr -> tr.select("th,td").map { it.text().trim() } }
            .filter { it.isNotEmpty() }
        if (allRows.size < 2) throw Exception("تعذر قراءة التفاصيل.")
        val headers = allRows.first()
        val dataRow = allRows.drop(1).firstOrNull { it.size == headers.size }
            ?: throw Exception("تعذر قراءة التفاصيل.")
        return SupremeResult(headers.zip(dataRow).filter { it.first.isNotBlank() })
    }

    private fun get(url: String, cookies: Map<String, String> = emptyMap()): Document {
        val connection: Connection = Jsoup.connect(url)
            .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .referrer(BASE).timeout(25_000).ignoreHttpErrors(true).ignoreContentType(true)
        if (cookies.isNotEmpty()) connection.cookies(cookies)
        val doc = connection.get()
        if (doc.html().contains("sec-warning") || doc.html().contains("product_data")) {
            throw Exception("تم حجب الصفحة")
        }
        return doc
    }

    private fun fetchText(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 25_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (conn.responseCode !in 200..299) return ""
            conn.inputStream.use { String(it.readBytes(), StandardCharsets.UTF_8) }
        } catch (_: Exception) {
            ""
        } finally { conn.disconnect() }
    }

    /** Jina returns the Arabic page mojibake-encoded; repair it. */
    private fun repair(text: String): String = try {
        if (text.any { it == 'Ã' || it == 'Ø' || it == 'Â' || it == 'Ù' }) {
            String(text.toByteArray(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8)
        } else text
    } catch (_: Exception) { text }

    private fun parseMarkdownTable(raw: String): List<SupremeResult> {
        val text = repair(raw)
        val lines = text.lines().filter { it.trim().startsWith("|") }
        val results = mutableListOf<SupremeResult>()
        var detailsLoaded = 0
        for (line in lines) {
            val cells = line.trim().trim('|').split('|').map { it.trim() }
            if (cells.size < 4) continue
            val first = cells[0]
            if (first.contains("الطاعن") || first.contains("الملتمس")) continue
            if (line.contains("__doPostBack") || first.all { it.isDigit() }) continue
            val sms = cells[2].filter { it.isDigit() }
            var pairs = listOf(
                "الطاعن/الملتمس" to cells[0],
                "المطعون/الملتمس ضده" to cells[1],
                "الرقم الخاص بخدمة الرسائل القصيرة" to sms
            )
            if (sms.length in 3..12 && detailsLoaded < 10) {
                val detailText = fetchText(JINA + DETAILS_URL + "?Sms_c=$sms")
                val detailRows = parseDetailMarkdown(detailText)
                if (detailRows.isNotEmpty()) { pairs = detailRows; detailsLoaded++ }
            }
            if (pairs.any { it.second.isNotBlank() }) results.add(SupremeResult(pairs))
        }
        return results
    }

    private fun parseDetailMarkdown(raw: String): List<Pair<String, String>> {
        val text = repair(raw)
        val rows = text.lines().filter { it.trim().startsWith("|") }
            .map { it.trim().trim('|').split('|').map { c -> c.trim() } }
            .filter { it.size >= 2 }
        if (rows.size < 2) return emptyList()
        val headers = rows.first()
        val values = rows[1]
        return headers.zip(values).filter { it.first.isNotBlank() && !it.first.contains("---") }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
