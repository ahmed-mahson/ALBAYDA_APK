package com.albayda.court.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.CookieHandler
import java.net.CookieManager
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
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
    private const val DRIVE_FILE_ID = "1an8xIuEv59d-ERmEy5OIlBvDlNGA5ZO8"
    private const val DRIVE_URL = "https://drive.google.com/uc?export=download&id=$DRIVE_FILE_ID"
    private const val DIRECT_URL =
        "https://drive.usercontent.google.com/download?id=$DRIVE_FILE_ID&export=download&confirm=t"
    private const val CACHE_KEY = "case_csv_cache"

    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
        if (CookieHandler.getDefault() == null) CookieHandler.setDefault(CookieManager())
    }

    /** Always tries to fetch fresh data; falls back to the last successful copy only on failure. */
    suspend fun loadRecords(force: Boolean = false): List<CaseRecord> = withContext(Dispatchers.IO) {
        val urls = listOf(DIRECT_URL, DRIVE_URL)
        var lastError: Exception? = null
        for (url in urls) {
            try {
                val text = download(url)
                val records = parseCsv(text)
                if (records.isNotEmpty()) {
                    cache(text)
                    return@withContext records
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        val cached = readCache()
        if (cached.isNotBlank()) {
            val records = parseCsv(cached)
            if (records.isNotEmpty()) return@withContext records
        }
        throw lastError ?: IllegalStateException("تعذر جلب بيانات القضايا من Google Drive")
    }

    private fun download(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 25_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.useCaches = false
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AlbaydaCourt/3.0")
            conn.setRequestProperty("Accept", "text/csv,text/plain,*/*")
            when (conn.responseCode) {
                in 200..299 -> Unit
                else -> throw IllegalStateException("HTTP ${conn.responseCode}")
            }
            val bytes = ByteArrayOutputStream().use { out ->
                conn.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    var read = input.read(buffer)
                    while (read != -1 && out.size() < 25 * 1024 * 1024) {
                        out.write(buffer, 0, read)
                        read = input.read(buffer)
                    }
                }
                out.toByteArray()
            }
            val text = decodeCsv(bytes)
            if (text.trimStart().startsWith("<")) {
                throw IllegalStateException("تم استلام صفحة HTML بدلاً من ملف CSV")
            }
            text
        } finally {
            conn.disconnect()
        }
    }

    /** Handles UTF-8 BOM and Arabic Windows-1256 encoded files without breaking letters. */
    private fun decodeCsv(bytes: ByteArray): String {
        if (bytes.size >= 3 && (bytes[0].toInt() and 0xFF) == 0xEF &&
            (bytes[1].toInt() and 0xFF) == 0xBB && (bytes[2].toInt() and 0xFF) == 0xBF
        ) {
            return String(bytes.copyOfRange(3, bytes.size), StandardCharsets.UTF_8)
        }
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            String(bytes, charset("windows-1256"))
        }
    }

    private fun cache(text: String) {
        try {
            appContext?.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                ?.edit()?.putString(CACHE_KEY, text)?.apply()
        } catch (_: Exception) {
        }
    }

    private fun readCache(): String {
        return try {
            appContext?.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                ?.getString(CACHE_KEY, "") ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun parseCsv(text: String): List<CaseRecord> {
        val lines = text.replace("\uFEFF", "").replace("\r\n", "\n").replace("\r", "\n").split("\n")
        val out = mutableListOf<CaseRecord>()
        for (line in lines) {
            if (line.trim().isEmpty()) continue
            val row = parseCsvLine(line)
            if (row.size < 27) continue
            if (row.getOrNull(14)?.trim() == "رقم القضية") continue
            val rec = CaseRecord(
                previousSession = clean(row.getOrNull(16)),
                nextSession = clean(row.getOrNull(17)),
                status = clean(row.getOrNull(19)),
                judge = clean(row.getOrNull(20)),
                defendant = clean(row.getOrNull(21)),
                plaintiff = clean(row.getOrNull(22)),
                receivedDate = clean(row.getOrNull(23)),
                subject = clean(row.getOrNull(24)),
                caseType = clean(row.getOrNull(25)),
                caseNumber = clean(row.getOrNull(26))
            )
            if (rec.caseNumber.isNotBlank() || rec.plaintiff.isNotBlank() || rec.defendant.isNotBlank()) {
                out.add(rec)
            }
        }
        return out
    }

    private fun clean(value: String?): String {
        if (value == null) return ""
        return value
            .map { ch -> if (ch.code < 32 && ch != '\t') ' ' else ch }
            .joinToString("")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /** Whole row may be quoted, with CSV-escaped fields inside. */
    private fun parseCsvLine(line: String): List<String> {
        val l = line.trim()
        val inner = if (l.length >= 2 && l.first() == '"' && l.last() == '"') {
            l.substring(1, l.length - 1).replace("\"\"", "\"")
        } else l
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < inner.length) {
            val c = inner[i]
            when {
                c == '"' -> {
                    if (inQuotes && i + 1 < inner.length && inner[i + 1] == '"') {
                        sb.append('"'); i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                c == ',' && !inQuotes -> {
                    result.add(sb.toString()); sb.setLength(0)
                }
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
                    (pl.isBlank() || r.plaintiff.contains(pl, ignoreCase = true)) &&
                    (de.isBlank() || r.defendant.contains(de, ignoreCase = true)) &&
                    (ct.isBlank() || ct == "الكل" || r.caseType.contains(ct) || ct.contains(r.caseType)) &&
                    (jg.isBlank() || jg == "الكل" || r.judge.contains(jg) || jg.contains(r.judge)) &&
                    (q.isBlank() || normalizeDigits(r.caseNumber).contains(normalizeDigits(q)) ||
                            r.plaintiff.contains(q, ignoreCase = true) || r.defendant.contains(q, ignoreCase = true))
        }.take(100)
    }
}
