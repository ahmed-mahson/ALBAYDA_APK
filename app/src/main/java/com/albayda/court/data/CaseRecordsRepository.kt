package com.albayda.court.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale

data class CaseRecord(
    val caseNumber: String,
    val caseType: String,
    val subject: String,
    val plaintiff: String,
    val defendant: String,
    val judge: String,
    val status: String,
    val nextSession: String,
    val receivedDate: String = "",
    val previousSession: String = ""
)

object CaseRecordsRepository {
    private const val DRIVE_FILE_ID = "1wxYp5pvVh-8fWkW8OV1zMqWz90q59j8H"
    private const val DRIVE_URL = "https://drive.google.com/uc?export=download&id=$DRIVE_FILE_ID"
    // Always fetch fresh data from Drive on each request as requested
    suspend fun loadRecords(force: Boolean = false): List<CaseRecord> = withContext(Dispatchers.IO) {
        val conn = (URL(DRIVE_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 25000
            readTimeout = 25000
            setRequestProperty("User-Agent", "AlbaydaCourtApp/1.0")
            useCaches = false
        }
        try {
            val bytes = conn.inputStream.use { it.readBytes() }
            val text = try { String(bytes, Charsets.UTF_8) } catch (_: Exception) { String(bytes, charset("windows-1256")) }
            parseCsv(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseCsv(text: String): List<CaseRecord> {
        val lines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        val out = mutableListOf<CaseRecord>()
        for (line in lines) {
            if (line.trim().isEmpty()) continue
            val row = parseCsvLine(line)
            if (row.size < 27) continue
            // Skip header-like rows
            if (row.getOrNull(14)?.trim() == "رقم القضية") continue
            val rec = CaseRecord(
                previousSession = row.getOrNull(16)?.trim().orEmpty(),
                nextSession = row.getOrNull(17)?.trim().orEmpty(),
                status = row.getOrNull(19)?.trim().orEmpty(),
                judge = row.getOrNull(20)?.trim().orEmpty(),
                defendant = row.getOrNull(21)?.trim().orEmpty(),
                plaintiff = row.getOrNull(22)?.trim().orEmpty(),
                receivedDate = row.getOrNull(23)?.trim().orEmpty(),
                subject = row.getOrNull(24)?.trim().orEmpty(),
                caseType = row.getOrNull(25)?.trim().orEmpty(),
                caseNumber = row.getOrNull(26)?.trim().orEmpty()
            )
            if (rec.caseNumber.isNotBlank() || rec.plaintiff.isNotBlank() || rec.defendant.isNotBlank()) out.add(rec)
        }
        return out
    }

    // Parses a single CSV line where the whole row may be wrapped in quotes and fields are CSV-escaped inside
    private fun parseCsvLine(line: String): List<String> {
        val l = line.trim()
        val inner = if (l.length >= 2 && l.first() == '"' && l.last() == '"') l.substring(1, l.length - 1).replace("\"\"", "\"") else l
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < inner.length) {
            val c = inner[i]
            when {
                c == '"' -> {
                    if (inQuotes && i + 1 < inner.length && inner[i + 1] == '"') { sb.append('"'); i++ } else inQuotes = !inQuotes
                }
                c == ',' && !inQuotes -> { result.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(c)
            }
            i++
        }
        result.add(sb.toString())
        return result
    }

    fun normalizeDigits(s: String): String = s.map { ch ->
        if (ch.isDigit()) Character.getNumericValue(ch).digitToChar() else ch
    }.joinToString("")

    fun search(
        records: List<CaseRecord>,
        caseNumber: String = "",
        plaintiff: String = "",
        defendant: String = "",
        caseType: String = "",
        judge: String = "",
        query: String = ""
    ): List<CaseRecord> {
        val cn = normalizeDigits(caseNumber.trim()).lowercase(Locale.ROOT)
        val pl = plaintiff.trim()
        val de = defendant.trim()
        val ct = caseType.trim()
        val jg = judge.trim()
        val q = query.trim()
        return records.filter { r ->
            (cn.isBlank() || normalizeDigits(r.caseNumber).contains(cn)) &&
            (pl.isBlank() || r.plaintiff.contains(pl)) &&
            (de.isBlank() || r.defendant.contains(de)) &&
            (ct.isBlank() || ct == "الكل" || r.caseType.contains(ct) || ct.contains(r.caseType)) &&
            (jg.isBlank() || jg == "الكل" || r.judge.contains(jg) || jg.contains(r.judge)) &&
            (q.isBlank() || r.caseNumber.contains(q) || r.plaintiff.contains(q) || r.defendant.contains(q))
        }.take(50)
    }
}
