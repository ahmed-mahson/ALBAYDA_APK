package com.albayda.court.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.CookieHandler
import java.net.CookieManager
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
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
    private const val DRIVE_FILE_ID = "1an8xIuEv59d-ERmEy5OIlBvDlNGA5ZO8"
    private const val CACHE_KEY = "case_csv_cache"

    // Case-type and judge lists are derived directly from the downloaded CSV data
    // (see orderedCaseTypes / orderedJudges) instead of a hard-coded mapping,
    // so mojibake in literals can never empty the drop-downs.

    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
        if (CookieHandler.getDefault() == null) CookieHandler.setDefault(CookieManager())
    }

    /** Always tries to fetch fresh data; falls back to the last successful copy only on failure. */
    suspend fun loadRecords(force: Boolean = false): List<CaseRecord> = withContext(Dispatchers.IO) {
        val urls = listOf(
            "https://drive.google.com/uc?export=download&id=" + DRIVE_FILE_ID,
            "https://drive.usercontent.google.com/download?id=" + DRIVE_FILE_ID + "&export=download&confirm=t",
            "https://drive.google.com/uc?export=download&id=" + DRIVE_FILE_ID + "&confirm=t",
            "https://drive.usercontent.google.com/download?id=" + DRIVE_FILE_ID + "&export=download"
        )
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
        var currentUrl = url
        var attempts = 0
        while (attempts < 5) {
            attempts++
            val conn = URL(currentUrl).openConnection() as HttpURLConnection
            val (code, bytes, finalUrl) = try {
                conn.connectTimeout = 25_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = true
                conn.useCaches = false
                conn.setRequestProperty("User-Agent", "AlbaydaCourtBot/1.0")
                conn.setRequestProperty("Accept", "text/csv,text/plain,*/*")
                val c = conn.responseCode
                val b = ByteArrayOutputStream().use { out ->
                    try {
                        conn.inputStream.use { input ->
                            val buffer = ByteArray(8192)
                            var read = input.read(buffer)
                            while (read != -1 && out.size() < 25 * 1024 * 1024) {
                                out.write(buffer, 0, read)
                                read = input.read(buffer)
                            }
                        }
                    } catch (_: Exception) {
                    }
                    out.toByteArray()
                }
                Triple(c, b, conn.url?.toString())
            } finally {
                conn.disconnect()
            }
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val text = decodeCsv(bytes)
            if (!text.trimStart().startsWith("<")) return text
            // HTML interstitial (virus scan). Extract confirm token.
            val token = Regex("confirm=([0-9A-Za-z_-]+)").find(text)?.groupValues?.get(1)
                ?: Regex("confirm=([0-9A-Za-z_-]+)").find(finalUrl ?: "")?.groupValues?.get(1)
            val uuid = Regex("uuid=([0-9A-Za-z-]+)").find(text)?.groupValues?.get(1)
            if (token.isNullOrBlank()) throw IllegalStateException("تم استلام صفحة HTML بدلاً من ملف CSV")
            val sep = if (currentUrl.contains("?")) "&" else "?"
            currentUrl = "$currentUrl${sep}confirm=$token" + (if (uuid != null) "&uuid=$uuid" else "")
        }
        throw IllegalStateException("تم استلام صفحة HTML بدلاً من ملف CSV")
    }
    /** Detects and normalizes Unicode before CSV parsing to prevent Chinese text and squares. */
    private fun decodeCsv(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""

        val candidates = LinkedHashMap<String, String>()
        fun add(name: String, text: String?) {
            if (!text.isNullOrEmpty() && !candidates.values.any { it == text }) candidates[name] = text
        }
        fun charset(name: String): Charset? = try { Charset.forName(name) } catch (_: Exception) { null }
        fun decodeRange(offset: Int, cs: Charset?): String? {
            if (cs == null || offset >= bytes.size) return null
            return try { String(bytes, offset, bytes.size - offset, cs) } catch (_: Exception) { null }
        }

        // 1) Byte-order marks.
        when {
            startsWith(bytes, 0x00, 0x00, 0xFE, 0xFF) -> add("UTF-32BE", decodeRange(4, charset("UTF-32BE")))
            startsWith(bytes, 0xFF, 0xFE, 0x00, 0x00) -> add("UTF-32LE", decodeRange(4, charset("UTF-32LE")))
            startsWith(bytes, 0xEF, 0xBB, 0xBF) -> add("UTF-8-BOM", decodeRange(3, StandardCharsets.UTF_8))
            startsWith(bytes, 0xFF, 0xFE) -> add("UTF-16LE-BOM", decodeRange(2, StandardCharsets.UTF_16LE))
            startsWith(bytes, 0xFE, 0xFF) -> add("UTF-16BE-BOM", decodeRange(2, StandardCharsets.UTF_16BE))
        }

        // 2) Strict UTF-8 — the normal case for a CSV downloaded from Google Drive.
        val strictUtf8 = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            null
        }
        add("UTF-8", strictUtf8)

        // 3) Other encodings. The scoring function below selects the one containing real Arabic.
        add("UTF-8-lenient", String(bytes, StandardCharsets.UTF_8))
        add("windows-1256", decodeRange(0, charset("windows-1256")))
        add("ISO-8859-1", decodeRange(0, charset("ISO-8859-1")))
        add("UTF-16LE", decodeRange(0, StandardCharsets.UTF_16LE))
        add("UTF-16BE", decodeRange(0, StandardCharsets.UTF_16BE))
        add("GB18030", decodeRange(0, charset("GB18030")))
        add("Big5", decodeRange(0, charset("Big5")))
        add("Shift_JIS", decodeRange(0, charset("Shift_JIS")))

        // 4) Repair mojibake produced when UTF-8 bytes were read as a single-byte charset.
        val latinSource = candidates["ISO-8859-1"]
        if (latinSource != null && latinSource.any { it == 'Ã' || it == 'Ø' || it == 'Ù' || it == 'â' }) {
            val cs = charset("ISO-8859-1")
            add("repaired-latin1->UTF-8", if (cs == null) null else try {
                String(latinSource.toByteArray(cs), StandardCharsets.UTF_8)
            } catch (_: Exception) { null })
        }
        val cp1256Source = candidates["windows-1256"]
        if (cp1256Source != null && cp1256Source.any { it == 'Ã' || it == 'Ø' || it == 'Ù' }) {
            val cs = charset("windows-1256")
            add("repaired-cp1256->UTF-8", if (cs == null) null else try {
                String(cp1256Source.toByteArray(cs), StandardCharsets.UTF_8)
            } catch (_: Exception) { null })
        }
        if (strictUtf8 != null && strictUtf8.any { it == 'Ã' || it == 'Ø' || it == 'Ù' || it == 'â' }) {
            val cs = charset("windows-1256")
            add("repaired-utf8-text-cp1256->UTF-8", if (cs == null) null else try {
                String(strictUtf8.toByteArray(cs), StandardCharsets.UTF_8)
            } catch (_: Exception) { null })
        }

        val best = candidates.maxByOrNull { scoreDecodedText(it.value) }?.value
            ?: strictUtf8
            ?: String(bytes, StandardCharsets.UTF_8)
        return normalizeUnicode(best)
    }

    private fun startsWith(bytes: ByteArray, vararg magic: Int): Boolean {
        if (bytes.size < magic.size) return false
        return magic.indices.all { (bytes[it].toInt() and 0xFF) == (magic[it] and 0xFF) }
    }

    private fun scoreDecodedText(text: String): Int {
        var arabic = 0
        var cjk = 0
        var replacement = 0
        var controls = 0
        var useful = 0
        for (ch in text) {
            val code = ch.code
            when {
                ch == '\uFFFD' -> replacement += 6
                code in 0x0600..0x06FF || code in 0x0750..0x077F ||
                        code in 0xFB50..0xFDFF || code in 0xFE70..0xFEFF -> arabic += 4
                code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF ||
                        code in 0x3040..0x30FF || code in 0xAC00..0xD7AF -> cjk += 6
                code < 32 && ch != '\n' && ch != '\r' && ch != '\t' -> controls += 5
                ch.isLetterOrDigit() -> useful += 1
            }
        }
        val headerBonus = if (text.contains("رقم القضية") || text.contains("القضية")) 250 else 0
        val columnBonus = minOf(text.count { it == ',' }, 200)
        return arabic + headerBonus + columnBonus + minOf(useful, 200) / 4 -
                cjk * 4 - replacement * 5 - controls * 3
    }

    private fun normalizeUnicode(text: String): String {
        val nfc = try { Normalizer.normalize(text, Normalizer.Form.NFKC) } catch (_: Exception) { text }
        return buildString(nfc.length) {
            nfc.forEach { ch ->
                when {
                    ch == '\uFEFF' || ch == '\u200B' || ch == '\u200E' || ch == '\u200F' -> Unit
                    ch == '\u00A0' -> append(' ')
                    ch.code < 32 && ch != '\n' && ch != '\r' && ch != '\t' -> append(' ')
                    else -> append(ch)
                }
            }
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
        for ((_, rawLine) in lines.withIndex()) {
            if (rawLine.trim().isEmpty()) continue
            val row = parseCsvLine(rawLine)
            if (row.size < 27) continue
            // Each row in the Drive "report" export contains label columns (1,8,14)
            // alongside value columns. Validate labels when present, but do not skip.
            val label1 = row.getOrNull(1)?.trim().orEmpty()
            val label8 = row.getOrNull(8)?.trim().orEmpty()
            val label14 = row.getOrNull(14)?.trim().orEmpty()
            val looksLikeHeaderOnly =
                label1 == "نوع القضية الأساسي" && label8 == "برئاسة القاضي" && label14 == "رقم القضية" &&
                clean(row.getOrNull(26)).isBlank() && clean(row.getOrNull(25)).isBlank() &&
                clean(row.getOrNull(22)).isBlank() && clean(row.getOrNull(21)).isBlank()
            if (looksLikeHeaderOnly) continue
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
            if (rec.caseNumber.isNotBlank() || rec.plaintiff.isNotBlank() || rec.defendant.isNotBlank() ||
                rec.caseType.isNotBlank() || rec.judge.isNotBlank() || rec.nextSession.isNotBlank()) {
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

    /** Converts Arabic-Indic / Extended Arabic / Persian digits to plain ASCII digits. */
    fun normalizeDigits(s: String): String = buildString(s.length) {
        s.forEach { ch ->
            if (ch.isDigit()) append(Character.getNumericValue(ch)) else append(ch)
        }
    }

    /** Same validation as bot.py::parse_case_query — e.g. 1446/6. */
    fun parseCaseQuery(input: String): Pair<String, String>? {
        val value = normalizeDigits(input.trim())
        if (value.count { it == '/' } != 1) return null
        val parts = value.split('/')
        val caseNum = parts[0].trim()
        val year = parts[1].trim()
        if (caseNum.isEmpty() || year.isEmpty()) return null
        if (caseNum.length !in 1..10 || year.length != 4) return null
        if (caseNum.all { it == '0' } || year.all { it == '0' }) return null
        return caseNum to year
    }

    /** Accepts yyyy-MM-dd, yyyy/MM/dd and dd/MM/yyyy — same as the bot. */
    fun normalizeDate(value: String): String? {
        val text = normalizeDigits(value.trim())
        if (text.isBlank()) return null
        val iso = Regex("^(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})$").matchEntire(text)
        val dayFirst = Regex("^(\\d{1,2})/(\\d{1,2})/(\\d{4})$").matchEntire(text)
        val (y, m, d) = when {
            iso != null -> listOf(iso.groupValues[1], iso.groupValues[2], iso.groupValues[3])
            dayFirst != null -> listOf(dayFirst.groupValues[3], dayFirst.groupValues[2], dayFirst.groupValues[1])
            else -> return null
        }
        val year = y.toIntOrNull() ?: return null
        val month = m.toIntOrNull() ?: return null
        val day = d.toIntOrNull() ?: return null
        if (year < 1 || month !in 1..12 || day !in 1..31) return null
        return String.format(Locale.US, "%04d-%02d-%02d", year, month, day)
    }

    /** Dates are displayed as dd/MM/yyyy, e.g. 18/11/2026. */
    fun formatDisplayDate(value: String): String {
        val iso = normalizeDate(value) ?: return value
        val parts = iso.split("-")
        return "${parts[2]}/${parts[1]}/${parts[0]}"
    }

    fun displayCaseType(raw: String): String = raw.trim()

    fun orderedCaseTypes(records: List<CaseRecord>): List<String> =
        records.map { it.caseType.trim() }.filter { it.isNotBlank() }.distinct().sorted()

    fun normalizeJudge(value: String): String = clean(value)

    fun displayJudge(value: String): String {
        val name = normalizeJudge(value)
        if (name.isBlank()) return "-"
        return if (name.startsWith("\u2696\uFE0F")) name else "\u2696\uFE0F$name"
    }

    private fun caseTypeMatches(recordType: String, selected: String): Boolean {
        val ct = selected.trim()
        if (ct.isBlank() || ct == "الكل" || ct.contains("الكل")) return true
        val raw = recordType.trim()
        return raw == ct || raw.contains(ct) || ct.contains(raw)
    }

    private fun judgeMatches(recordJudge: String, selected: String): Boolean {
        val jg = selected.trim().removePrefix("\u2696\uFE0F").trim()
        if (jg.isBlank() || jg == "الكل" || jg.contains("الكل")) return true
        val expected = normalizeJudge(jg)
        val actual = normalizeJudge(recordJudge)
        return actual == expected || actual.contains(expected) || expected.contains(actual)
    }

    /** Session search with normalized dates, matching the bot's search_sessions_by_date. */
    fun searchSessions(
        records: List<CaseRecord>,
        date: String,
        judge: String = ""
    ): List<CaseRecord> {
        val target = normalizeDate(date) ?: return emptyList()
        return records.filter { rec ->
            normalizeDate(rec.nextSession) == target && judgeMatches(rec.judge, judge)
        }.take(100)
    }

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
        val pl = plaintiff.trim().lowercase(Locale.ROOT)
        val de = defendant.trim().lowercase(Locale.ROOT)
        val ct = caseType.trim()
        val jg = judge.trim()
        val rawQuery = query.trim()
        val q = rawQuery.lowercase(Locale.ROOT)
        val numberQueries = LinkedHashSet<String>()
        if (cn.isNotBlank()) numberQueries.add(cn)
        if (q.isNotBlank()) {
            val normalized = normalizeDigits(q)
            numberQueries.add(normalized)
            // bot.py tries both 1446/6 and 6/1446 because the sheet may store either order.
            if (normalized.count { it == '/' } == 1) {
                val parts = normalized.split('/')
                numberQueries.add("${parts[1].trim()}/${parts[0].trim()}")
            }
        }
        return records.filter { r ->
            val recordNumber = normalizeDigits(r.caseNumber).lowercase(Locale.ROOT)
            val numberMatch = numberQueries.isEmpty() || numberQueries.any { recordNumber.contains(it) }
            val nameMatch = q.isBlank() ||
                    r.plaintiff.lowercase(Locale.ROOT).contains(q) ||
                    r.defendant.lowercase(Locale.ROOT).contains(q)
            numberMatch && nameMatch &&
                    (pl.isBlank() || r.plaintiff.lowercase(Locale.ROOT).contains(pl)) &&
                    (de.isBlank() || r.defendant.lowercase(Locale.ROOT).contains(de)) &&
                    caseTypeMatches(r.caseType, ct) &&
                    judgeMatches(r.judge, jg)
        }.take(100)
    }
}
