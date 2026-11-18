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
        for (line in lines) {
            if (line.trim().isEmpty()) continue
            val row = parseCsvLine(line)
            if (row.size < 27) continue
            val caseHeader = row.getOrNull(14).orEmpty()
            if (caseHeader.contains("رقم") && caseHeader.contains("القضية")) continue
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
