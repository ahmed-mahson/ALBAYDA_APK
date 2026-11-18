package com.albayda.court.util

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.widget.Toast
import com.albayda.court.data.CaseRecord
import com.albayda.court.data.CaseRecordsRepository
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

object PdfReport {

    fun build(context: Context, title: String, rows: List<Array<String>>): ByteArray {
        val data = linkedMapOf<String, String>()
        rows.forEach { data[it.getOrNull(0)?.trim().orEmpty()] = it.getOrNull(1)?.trim().orEmpty() }
        return try {
            when {
                title.contains("إعلان") || title.contains("حضور") ->
                    buildAttendance(context, data)
                title.contains("المحكمة العليا") || title.contains("نتيجة") ->
                    buildSupreme(context, title, rows)
                title.contains("جلس") ->
                    buildGeneric(context, title, rows)
                else ->
                    buildCaseInfo(context, data)
            }
        } catch (_: Exception) {
            buildGeneric(context, title, rows)
        }
    }

    fun buildCaseInfo(context: Context, data: Map<String, String>): ByteArray {
        return try {
            val ordered = listOf(
                "القاضي المختص" to (data["القاضي المختص"] ?: data["القاضي"] ?: data["برئاسة القاضي"] ?: "-"),
                "رقم القضية" to (data["رقم القضية"] ?: "-"),
                "تاريخ الورود" to (data["تاريخ الورود"] ?: data["تاريخ ورودها"] ?: "-"),
                "موضوع القضية" to (data["موضوع القضية"] ?: data["موضوعها"] ?: "-"),
                "نوع القضية" to (data["نوع القضية"] ?: "-"),
                "المدعي" to (data["المدعي"] ?: "-"),
                "المدعى عليه" to (data["المدعى عليه"] ?: "-"),
                "آخر قرار للقضية" to (data["آخر قرار للقضية"] ?: data["حالة القضية"] ?: "-"),
                "تاريخ الجلسة القادمة" to (data["تاريخ الجلسة القادمة"] ?: data["الجلسة القادمة"] ?: data["تاريخ الجلسة"] ?: "-")
            )
            renderCaseInfoTemplate(context, ordered)
        } catch (_: Exception) {
            buildGeneric(context, "معلومات القضية", data.map { arrayOf(it.key, it.value) })
        }
    }

    fun buildAttendance(context: Context, data: Map<String, String>): ByteArray {
        return try {
            renderAttendanceTemplate(context, data)
        } catch (_: Exception) {
            buildGeneric(context, "إعلان حضور", data.map { arrayOf(it.key, it.value) })
        }
    }


    fun buildSessions(context: Context, date: String, judge: String, records: List<CaseRecord>): ByteArray {
        return try {
            renderSessionsTemplate(context, date, judge, records)
        } catch (_: Exception) {
            val rows = records.flatMap { r ->
                listOf(
                    arrayOf("رقم القضية", r.caseNumber),
                    arrayOf("الموضوع", r.subject),
                    arrayOf("نوع القضية", r.caseType),
                    arrayOf("المدعي", r.plaintiff),
                    arrayOf("المدعى عليه", r.defendant),
                    arrayOf("القاضي", r.judge),
                    arrayOf("---", "---")
                )
            }
            buildGeneric(context, "قائمة الجلسات $date", rows)
        }
    }

    private fun loadTypeface(context: Context): Typeface? {
        return try { Typeface.createFromAsset(context.assets, "fonts/sultan-medium.ttf") } catch (_: Exception) { null }
    }

    private fun openTemplate(context: Context, assetPath: String): File {
        val out = File(context.cacheDir, assetPath.substringAfterLast('/'))
        if (!out.exists() || out.length() == 0L) {
            context.assets.open(assetPath).use { input -> FileOutputStream(out).use { input.copyTo(it) } }
        }
        return out
    }

    private data class HijriDate(val day: Int, val month: Int, val year: Int)

    private fun hijriFromGregorian(y: Int, m: Int, d: Int): HijriDate? {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                try {
                    val icuCal = android.icu.util.IslamicCalendar()
                    try {
                        icuCal.calculationType = android.icu.util.IslamicCalendar.CalculationType.ISLAMIC_UMALQURA
                    } catch (_: Throwable) { }
                    val g = android.icu.util.GregorianCalendar(y, m - 1, d)
                    icuCal.timeInMillis = g.timeInMillis
                    val hd = icuCal.get(android.icu.util.Calendar.DAY_OF_MONTH)
                    val hm = icuCal.get(android.icu.util.Calendar.MONTH) + 1
                    val hy = icuCal.get(android.icu.util.Calendar.YEAR)
                    return HijriDate(hd, hm, hy)
                } catch (_: Throwable) { }
            }
            val a = (14 - m) / 12
            val yy = y + 4800 - a
            val mm = m + 12 * a - 3
            val jdn = d + (153 * mm + 2) / 5 + 365 * yy + yy / 4 - yy / 100 + yy / 400 - 32045
            val l = jdn - 1948440 + 10632
            val n = (l - 1) / 10631
            var l2 = l - 10631 * n + 354
            val j = ((10985 - l2) / 5316) * ((50 * l2) / 17719) + (l2 / 5670) * ((43 * l2) / 15238)
            l2 = l2 - ((30 - j) / 15) * ((17719 * j) / 50) - (j / 16) * ((15238 * j) / 43) + 29
            val hm = (24 * l2) / 709
            val hd = l2 - (709 * hm) / 24
            val hy = 30 * n + j - 30
            HijriDate(hd, hm, hy)
        } catch (_: Exception) { null }
    }

    private fun hijriFullDate(y: Int, m: Int, d: Int): String {
        val h = hijriFromGregorian(y, m, d) ?: return "--/--/----"
        return String.format(Locale.US, "%02d/%02d/%04d", h.day, h.month, h.year)
    }

    private fun weekdayName(y: Int, m: Int, d: Int): String {
        val weekdays = listOf("الاثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت", "الأحد")
        return try {
            val cal = java.util.Calendar.getInstance().apply { clear(); set(y, m - 1, d) }
            when (cal.get(java.util.Calendar.DAY_OF_WEEK)) {
                java.util.Calendar.MONDAY -> weekdays[0]
                java.util.Calendar.TUESDAY -> weekdays[1]
                java.util.Calendar.WEDNESDAY -> weekdays[2]
                java.util.Calendar.THURSDAY -> weekdays[3]
                java.util.Calendar.FRIDAY -> weekdays[4]
                java.util.Calendar.SATURDAY -> weekdays[5]
                else -> weekdays[6]
            }
        } catch (_: Exception) { "" }
    }

    private fun parseIsoParts(date: String): Triple<Int, Int, Int>? {
        val norm = CaseRecordsRepository.normalizeDate(date) ?: return null
        val p = norm.split("-")
        if (p.size != 3) return null
        val y = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        val d = p[2].toIntOrNull() ?: return null
        return Triple(y, m, d)
    }

    private fun formatDisplayFull(date: String): String {
        val t = parseIsoParts(date) ?: return if (date.isBlank()) "-" else date
        return String.format(Locale.US, "%02d/%02d/%04d", t.third, t.second, t.first)
    }

    private fun wrapTextRight(text: String, paint: Paint, maxWidth: Float): List<String> {
        val clean = if (text.isBlank()) "-" else text.trim().replace(Regex("\\s+"), " ")
        val words = clean.split(" ").filter { it.isNotEmpty() }
        if (words.isEmpty()) return listOf("-")
        val lines = mutableListOf<String>()
        var current = ""
        fun fits(s: String) = paint.measureText(s) <= maxWidth
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (fits(candidate)) {
                current = candidate
            } else if (current.isNotEmpty()) {
                lines.add(current)
                if (fits(word)) {
                    current = word
                } else {
                    var chunk = ""
                    for (ch in word) {
                        val c2 = chunk + ch
                        if (chunk.isNotEmpty() && !fits(c2)) { lines.add(chunk); chunk = ch.toString() } else chunk = c2
                    }
                    current = chunk
                }
            } else {
                var chunk = ""
                for (ch in word) {
                    val c2 = chunk + ch
                    if (chunk.isNotEmpty() && !fits(c2)) { lines.add(chunk); chunk = ch.toString() } else chunk = c2
                }
                current = chunk
            }
        }
        if (current.isNotEmpty()) lines.add(current)
        return if (lines.isEmpty()) listOf("-") else lines
    }

    private fun renderCaseInfoTemplate(context: Context, ordered: List<Pair<String, String>>): ByteArray {
        val file = openTemplate(context, "templates/case-info.pdf")
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        val src = renderer.openPage(0)
        val w = src.width * 2
        val h = src.height * 2
        val sx = w / 595.32f
        val sy = h / 841.92f
        src.close(); renderer.close(); pfd.close()

        fun renderBg(): Bitmap {
            val pfd2 = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val r2 = PdfRenderer(pfd2)
            val pg = r2.openPage(0)
            val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bm.eraseColor(Color.WHITE)
            pg.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            pg.close(); r2.close(); pfd2.close()
            return bm
        }

        val tf = loadTypeface(context) ?: Typeface.DEFAULT
        val outDoc = PdfDocument()

        data class Row(val label: String, val baseline: Float, val fontPt: Float)
        val layout = listOf(
            Row("القاضي المختص", 609.94f, 30f),
            Row("رقم القضية", 550.63f, 30f),
            Row("تاريخ الورود", 491.35f, 30f),
            Row("موضوع القضية", 431.95f, 30f),
            Row("نوع القضية", 372.65f, 30f),
            Row("المدعي", 313.37f, 30f),
            Row("المدعى عليه", 254.09f, 30f),
            Row("آخر قرار للقضية", 194.78f, 30f),
            Row("تاريخ الجلسة القادمة", 136.22f, 24f)
        )
        val rightX = 393f * sx
        val maxW = 357f * sx
        val dateLabels = setOf("تاريخ الورود", "تاريخ الجلسة القادمة")

        data class FieldLines(val baselinePx: Float, val fontPx: Float, val chunks: List<List<String>>)
        val fieldsLines = mutableListOf<FieldLines>()
        for (i in layout.indices) {
            val row = layout[i]
            val raw = ordered.getOrNull(i)?.second?.ifBlank { "-" } ?: "-"
            val value = if (row.label in dateLabels) {
                val fd = formatDisplayFull(raw)
                if (fd == "--/--/----" || fd == "-") raw.ifBlank { "-" } else fd
            } else raw.ifBlank { "-" }
            val fontPx = row.fontPt * sy
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textAlign = Paint.Align.RIGHT; typeface = tf; textSize = fontPx }
            val lines = wrapTextRight(value, p, maxW)
            val chunks = mutableListOf<List<String>>()
            var idx = 0
            while (idx < lines.size) {
                chunks.add(lines.subList(idx, minOf(idx + 2, lines.size)))
                idx += 2
            }
            if (chunks.isEmpty()) chunks.add(listOf("-"))
            fieldsLines.add(FieldLines(h - row.baseline * sy, fontPx, chunks))
        }

        val pageCount = maxOf(1, fieldsLines.maxOfOrNull { it.chunks.size } ?: 1)
        for (pageIndex in 0 until pageCount) {
            val bg = renderBg()
            val page = outDoc.startPage(PdfDocument.PageInfo.Builder(w, h, pageIndex + 1).create())
            val c = page.canvas
            c.drawBitmap(bg, 0f, 0f, null)
            bg.recycle()
            for (f in fieldsLines) {
                if (pageIndex >= f.chunks.size) continue
                val lines = f.chunks[pageIndex]
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textAlign = Paint.Align.RIGHT; typeface = tf; textSize = f.fontPx }
                val leading = f.fontPx
                val firstBaseline = f.baselinePx + (lines.size - 1) * leading / 2f
                for ((li, line) in lines.withIndex()) {
                    c.drawText(line, rightX, firstBaseline - li * leading, p)
                }
            }
            outDoc.finishPage(page)
        }

        val bos = ByteArrayOutputStream(); outDoc.writeTo(bos); outDoc.close()
        return bos.toByteArray()
    }

    private fun renderAttendanceTemplate(context: Context, data: Map<String, String>): ByteArray {
        val file = openTemplate(context, "templates/attendance-ad.pdf")
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        val src = renderer.openPage(0)
        val w = src.width * 2
        val h = src.height * 2
        src.close(); renderer.close(); pfd.close()

        val pfd2 = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer2 = PdfRenderer(pfd2)
        val pg = renderer2.openPage(0)
        val bg = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bg.eraseColor(Color.WHITE)
        pg.render(bg, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
        pg.close(); renderer2.close(); pfd2.close()

        val outDoc = PdfDocument()
        val page = outDoc.startPage(PdfDocument.PageInfo.Builder(w, h, 1).create())
        val c = page.canvas
        c.drawBitmap(bg, 0f, 0f, null)
        bg.recycle()

        val tf = loadTypeface(context) ?: Typeface.DEFAULT
        val sx = w / 595.32f
        val sy = h / 841.92f
        fun ax(x: Float) = x * sx
        fun ay(y: Float) = h - y * sy

        val plaintiff = data["المدعي"] ?: data["الطاعن"] ?: "-"
        val defendant = data["المدعى عليه"] ?: data["المطعون ضده"] ?: "-"
        val sessionDateRaw = data["تاريخ الجلسة"] ?: data["الجلسة القادمة"] ?: "-"
        val judge = data["القاضي"] ?: data["اسم القاضي"] ?: data["القاضي المختص"] ?: data["برئاسة القاضي"] ?: "-"

        val coverPaint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
        fun cover(x: Float, y: Float, cw: Float, ch: Float) {
            c.drawRect(ax(x), ay(y + ch), ax(x + cw), ay(y), coverPaint)
        }
        cover(249f, 516f, 45f, 31f)
        cover(46f, 516f, 51f, 31f)
        cover(298f, 444f, 114f, 31f)
        cover(123f, 444f, 55f, 31f)

        fun drawSingleLineFit(text: String, anchorX: Float, baselineY: Float, maxWidth: Float, maxSize: Float, align: Paint.Align = Paint.Align.CENTER) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textAlign = align; typeface = tf }
            val t = if (text.isBlank()) "-" else text.trim()
            var fs = maxSize * sy
            p.textSize = fs
            val maxW = maxWidth * sx
            while (fs > 8f * sy && p.measureText(t) > maxW) { fs -= 0.5f * sy; p.textSize = fs }
            c.drawText(t, ax(anchorX), ay(baselineY), p)
        }
        fun drawCenteredFit(text: String, centerX: Float, baselineY: Float, maxWidth: Float, maxSize: Float) {
            drawSingleLineFit(text, centerX, baselineY, maxWidth, maxSize, Paint.Align.CENTER)
        }

        drawSingleLineFit(plaintiff, 380f, 648f, 205f, 24f)
        drawSingleLineFit(defendant, 380f, 603f, 205f, 24f)

        val sp = parseIsoParts(sessionDateRaw)
        if (sp != null) {
            val (syy, smm, sdd) = sp
            drawCenteredFit(weekdayName(syy, smm, sdd), 450f, 521.47f, 90f, 24f)
            drawCenteredFit(String.format(Locale.US, "%02d/%02d/%04d", sdd, smm, syy), 310f, 521.47f, 115f, 24f)
            drawCenteredFit(hijriFullDate(syy, smm, sdd), 120f, 521.47f, 120f, 24f)
        } else {
            drawCenteredFit(sessionDateRaw.ifBlank { "-" }, 310f, 521.47f, 180f, 24f)
        }

        try {
            val now = java.util.Calendar.getInstance()
            val py = now.get(java.util.Calendar.YEAR)
            val pm = now.get(java.util.Calendar.MONTH) + 1
            val pd = now.get(java.util.Calendar.DAY_OF_MONTH)
            drawCenteredFit(String.format(Locale.US, "%02d/%02d/%04d", pd, pm, py), 354f, 450.31f, 115f, 24.96f)
            drawCenteredFit(hijriFullDate(py, pm, pd), 190f, 450.31f, 120f, 24.96f)
        } catch (_: Exception) { }

        drawSingleLineFit(judge, 211.25f, 382.49f, 150f, 18f, Paint.Align.RIGHT)

        outDoc.finishPage(page)
        val bos = ByteArrayOutputStream(); outDoc.writeTo(bos); outDoc.close()
        return bos.toByteArray()
    }


    private data class SessionFragment(
        val fields: Map<String, List<String>>,
        val height: Float,
        val first: Boolean
    )

    private fun renderSessionsTemplate(context: Context, date: String, judgeFilter: String, records: List<CaseRecord>): ByteArray {
        val file = openTemplate(context, "templates/sessions.pdf")
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        val src0 = renderer.openPage(0)
        val pageW = src0.width * 2
        val pageH = src0.height * 2
        src0.close()

        val tf = loadTypeface(context) ?: Typeface.DEFAULT
        val sx = pageW / 595.32f
        val sy = pageH / 841.92f

        // PDF point geometry (reportlab coords: origin bottom-left, Y up)
        val tableLeftPt = 42.84f
        val tableRightPt = 552.58f
        val tableTopPt = 621.26f
        val tableBottomPt = 127.82f
        val colEdgesPt = floatArrayOf(43.8f, 174.5f, 305.21f, 367.03f, 460.9f, 523.78f, 552.58f)
        val seqCenterPt = (colEdgesPt[5] + colEdgesPt[6]) / 2f
        val minRowHeightPt = 24.6f
        val bodyFontPt = 8.5f
        val headerFontPt = 21.96f
        val leadingFactor = 1.15f
        val cellPadPt = 3.5f

        data class ColDef(val name: String, val left: Float, val right: Float)
        val columns = listOf(
            ColDef("case_number", 460.9f, 523.78f),
            ColDef("subject", 367.03f, 460.9f),
            ColDef("case_type", 305.21f, 367.03f),
            ColDef("plaintiff", 174.5f, 305.21f),
            ColDef("defendant", 43.8f, 174.5f)
        )

        fun ptToAx(xPt: Float): Float = xPt * sx
        fun ptToAy(yPt: Float): Float = pageH - yPt * sy // PDF Y-up -> Android Y-down

        fun measure(ptSize: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = tf
            color = Color.BLACK
            textAlign = Paint.Align.CENTER
            textSize = ptSize * sy
        }

        fun wrapText(text: String, maxWidthPx: Float, ptSize: Float): List<String> {
            val p = measure(ptSize)
            val clean = if (text.isBlank()) "-" else text.trim().replace(Regex("\\s+"), " ")
            val words = clean.split(" ").filter { it.isNotEmpty() }
            if (words.isEmpty()) return listOf("-")
            val lines = mutableListOf<String>()
            var current = ""
            fun fits(s: String) = p.measureText(s) <= maxWidthPx
            for (word in words) {
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (fits(candidate)) {
                    current = candidate
                } else if (current.isNotEmpty()) {
                    lines.add(current)
                    if (fits(word)) {
                        current = word
                    } else {
                        var chunk = ""
                        for (ch in word) {
                            val c2 = chunk + ch
                            if (chunk.isNotEmpty() && !fits(c2)) {
                                lines.add(chunk); chunk = ch.toString()
                            } else chunk = c2
                        }
                        current = chunk
                    }
                } else {
                    var chunk = ""
                    for (ch in word) {
                        val c2 = chunk + ch
                        if (chunk.isNotEmpty() && !fits(c2)) {
                            lines.add(chunk); chunk = ch.toString()
                        } else chunk = c2
                    }
                    current = chunk
                }
            }
            if (current.isNotEmpty()) lines.add(current)
            return if (lines.isEmpty()) listOf("-") else lines
        }

        fun rowFragments(rec: CaseRecord): List<SessionFragment> {
            val leadingPx = bodyFontPt * leadingFactor * sy
            val padPx = cellPadPt * sy
            val tableHeightPx = (tableTopPt - tableBottomPt) * sy
            val linesPerFragment = maxOf(1, ((tableHeightPx - padPx) / leadingPx).toInt())
            val fieldsWrapped = mutableMapOf<String, List<String>>()
            for (col in columns) {
                val maxW = (col.right - col.left - 6f) * sx
                val value = when (col.name) {
                    "case_number" -> rec.caseNumber.ifBlank { "-" }
                    "subject" -> rec.subject.ifBlank { "-" }
                    "case_type" -> rec.caseType.ifBlank { "-" }
                    "plaintiff" -> rec.plaintiff.ifBlank { "-" }
                    "defendant" -> rec.defendant.ifBlank { "-" }
                    else -> "-"
                }
                fieldsWrapped[col.name] = wrapText(value, maxW, bodyFontPt)
            }
            val maxLines = fieldsWrapped.values.maxOfOrNull { it.size } ?: 1
            val fragCount = maxOf(1, (maxLines + linesPerFragment - 1) / linesPerFragment)
            val frags = mutableListOf<SessionFragment>()
            for (i in 0 until fragCount) {
                val start = i * linesPerFragment
                val endIdx = start + linesPerFragment
                val sub = mutableMapOf<String, List<String>>()
                for ((k, v) in fieldsWrapped) {
                    sub[k] = if (start < v.size) v.subList(start, minOf(endIdx, v.size)) else emptyList()
                }
                val lineCount = maxOf(1, sub.values.maxOfOrNull { it.size } ?: 1)
                val hPx = maxOf(minRowHeightPt * sy, lineCount * leadingPx + padPx)
                frags.add(SessionFragment(sub, hPx, i == 0))
            }
            return frags
        }

        val normalizedDate = CaseRecordsRepository.normalizeDate(date) ?: date.trim()
        val dateParts = if (normalizedDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) normalizedDate.split("-") else listOf("0000", "00", "00")
        val gYear = dateParts[0].toIntOrNull() ?: 0
        val gMonth = dateParts[1].toIntOrNull() ?: 0
        val gDay = dateParts[2].toIntOrNull() ?: 0

        val weekdays = listOf("الاثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت", "الأحد")
        val weekdayName = try {
            val cal = java.util.Calendar.getInstance().apply {
                clear()
                set(gYear, gMonth - 1, gDay)
            }
            when (cal.get(java.util.Calendar.DAY_OF_WEEK)) {
                java.util.Calendar.MONDAY -> weekdays[0]
                java.util.Calendar.TUESDAY -> weekdays[1]
                java.util.Calendar.WEDNESDAY -> weekdays[2]
                java.util.Calendar.THURSDAY -> weekdays[3]
                java.util.Calendar.FRIDAY -> weekdays[4]
                java.util.Calendar.SATURDAY -> weekdays[5]
                else -> weekdays[6]
            }
        } catch (_: Exception) { "" }

        fun hijriDayMonth(y: Int, m: Int, d: Int): Pair<String, String> {
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                try {
                    val icuCal = android.icu.util.IslamicCalendar()
                    try {
                        icuCal.calculationType = android.icu.util.IslamicCalendar.CalculationType.ISLAMIC_UMALQURA
                    } catch (_: Throwable) { }
                    val g = android.icu.util.GregorianCalendar(y, m - 1, d)
                    icuCal.timeInMillis = g.timeInMillis
                    val hd = icuCal.get(android.icu.util.Calendar.DAY_OF_MONTH)
                    val hm = icuCal.get(android.icu.util.Calendar.MONTH) + 1
                    return Pair(String.format(Locale.US, "%02d", hd), String.format(Locale.US, "%02d", hm))
                } catch (_: Throwable) { }
            }
            return try {
                val a = (14 - m) / 12
                val yy = y + 4800 - a
                val mm = m + 12 * a - 3
                val jdn = d + (153 * mm + 2) / 5 + 365 * yy + yy / 4 - yy / 100 + yy / 400 - 32045
                val l = jdn - 1948440 + 10632
                val n = (l - 1) / 10631
                var l2 = l - 10631 * n + 354
                val j = ((10985 - l2) / 5316) * ((50 * l2) / 17719) + (l2 / 5670) * ((43 * l2) / 15238)
                l2 = l2 - ((30 - j) / 15) * ((17719 * j) / 50) - (j / 16) * ((15238 * j) / 43) + 29
                val hm = (24 * l2) / 709
                val hd = l2 - (709 * hm) / 24
                Pair(String.format(Locale.US, "%02d", hd), String.format(Locale.US, "%02d", hm))
            } catch (_: Exception) { Pair("--", "--") }
        }
        val (hDay, hMonth) = hijriDayMonth(gYear, gMonth, gDay)

        // group by judge and paginate
        val byJudge = linkedMapOf<String, MutableList<CaseRecord>>()
        for (rec in records) {
            val j = rec.judge.ifBlank { "غير محدد" }
            byJudge.getOrPut(j) { mutableListOf() }.add(rec)
        }
        val judgesSorted = byJudge.keys.sorted()
        data class PageData(val judge: String, val rows: List<SessionFragment>)
        val pages = mutableListOf<PageData>()
        val pageCapacityPx = (tableTopPt - tableBottomPt) * sy
        for (j in judgesSorted) {
            val recs = byJudge[j] ?: continue
            val fragments = recs.flatMap { rowFragments(it) }
            var current = mutableListOf<SessionFragment>()
            var used = 0f
            for (frag in fragments) {
                if (current.isNotEmpty() && used + frag.height > pageCapacityPx) {
                    pages.add(PageData(j, current))
                    current = mutableListOf()
                    used = 0f
                }
                current.add(frag)
                used += frag.height
            }
            if (current.isNotEmpty()) pages.add(PageData(j, current))
        }
        if (pages.isEmpty()) {
            val fallbackJudge = if (judgeFilter.isBlank() || judgeFilter.contains("جميع")) "غير محدد" else judgeFilter
            pages.add(PageData(fallbackJudge, emptyList()))
        }

        val outDoc = PdfDocument()
        fun renderTemplatePage(): Bitmap {
            val pfd2 = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val r2 = PdfRenderer(pfd2)
            val pg = r2.openPage(0)
            val bm = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
            bm.eraseColor(Color.WHITE)
            pg.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            pg.close(); r2.close(); pfd2.close()
            return bm
        }

        for ((pageIndex, pageData) in pages.withIndex()) {
            val bg = renderTemplatePage()
            val page = outDoc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageIndex + 1).create())
            val c = page.canvas
            c.drawBitmap(bg, 0f, 0f, null)
            bg.recycle()

            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = tf; color = Color.BLACK }

            // Judge name
            val judgeTitle = CaseRecordsRepository.displayJudge(pageData.judge).removePrefix("").trim().ifBlank { pageData.judge }
            paint.textAlign = Paint.Align.RIGHT
            var jSize = headerFontPt * sy
            paint.textSize = jSize
            val judgeMaxW = 150f * sx
            while (jSize > 10f * sy && paint.measureText(judgeTitle) > judgeMaxW) { jSize -= 0.5f * sy; paint.textSize = jSize }
            c.drawText(judgeTitle, ptToAx(391.5f), ptToAy(689.74f), paint)

            // Date header parts
            val headerBaseY = ptToAy(664.18f)
            fun drawCenteredFit(text: String, centerXPtf: Float, maxWidthPtf: Float, maxSizePt: Float = headerFontPt) {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = tf; color = Color.BLACK; textAlign = Paint.Align.CENTER }
                var fs = maxSizePt * sy
                p.textSize = fs
                val maxW = maxWidthPtf * sx
                while (fs > 5.5f * sy && p.measureText(text) > maxW) { fs -= 0.5f * sy; p.textSize = fs }
                c.drawText(text, ptToAx(centerXPtf), headerBaseY, p)
            }
            drawCenteredFit(weekdayName, 399f, 82f)
            drawCenteredFit(String.format(Locale.US, "%02d", if (gDay in 1..31) gDay else 0), 281.2f, 25f)
            drawCenteredFit(String.format(Locale.US, "%02d", if (gMonth in 1..12) gMonth else 0), 254.7f, 25f)
            drawCenteredFit(hDay, 138.6f, 25f)
            drawCenteredFit(hMonth, 112.1f, 25f)

            // Table
            val tLeft = ptToAx(tableLeftPt)
            val tRight = ptToAx(tableRightPt)
            val tTop = ptToAy(tableTopPt)
            val tBottom = ptToAy(tableBottomPt)
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            c.drawRect(tLeft, tTop, tRight, tBottom, paint)

            val rowBounds = mutableListOf(tTop)
            var cursorY = tTop
            var serial = 0
            val leadPx = bodyFontPt * leadingFactor * sy

            for ((rowIndex, frag) in pageData.rows.withIndex()) {
                val rowTop = cursorY
                cursorY += frag.height
                rowBounds.add(cursorY)
                if (rowIndex % 2 == 1) {
                    paint.style = Paint.Style.FILL
                    paint.color = Color.rgb(245, 245, 245)
                    c.drawRect(tLeft, rowTop, tRight, cursorY, paint)
                }
                paint.color = Color.BLACK
                if (frag.first) {
                    serial++
                    val seqPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        typeface = tf; color = Color.BLACK; textAlign = Paint.Align.CENTER; textSize = 8.5f * sy
                    }
                    val seqCy = (rowTop + cursorY) / 2f + (seqPaint.textSize * 0.35f)
                    c.drawText(serial.toString(), ptToAx(seqCenterPt), seqCy, seqPaint)
                }
                val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = tf; color = Color.BLACK; textAlign = Paint.Align.CENTER; textSize = bodyFontPt * sy
                }
                for (col in columns) {
                    val lines = frag.fields[col.name].orEmpty()
                    val lineCount = maxOf(1, lines.size)
                    val cx = ptToAx((col.left + col.right) / 2f)
                    val totalTextH = (lineCount - 1) * leadPx
                    val firstBaseline = (rowTop + cursorY) / 2f - totalTextH / 2f + (cellPaint.textSize * 0.35f)
                    for ((li, line) in lines.withIndex()) {
                        c.drawText(line, cx, firstBaseline + li * leadPx, cellPaint)
                    }
                }
            }

            val tableBottomActual = cursorY
            paint.style = Paint.Style.STROKE
            paint.color = Color.BLACK
            paint.strokeWidth = maxOf(1f, 0.5f * sy)
            for (xPt in colEdgesPt) {
                val x = ptToAx(xPt)
                c.drawLine(x, tTop, x, tableBottomActual, paint)
            }
            val uniqueYs = (rowBounds + tableBottomActual).distinct()
            for (y in uniqueYs) {
                c.drawLine(tLeft, y, tRight, y, paint)
            }
            c.drawLine(tLeft, tTop, tLeft, tableBottomActual, paint)
            c.drawLine(tRight, tTop, tRight, tableBottomActual, paint)

            outDoc.finishPage(page)
        }

        renderer.close(); pfd.close()
        val bos = ByteArrayOutputStream()
        outDoc.writeTo(bos)
        outDoc.close()
        return bos.toByteArray()
    }

    private fun drawFittedText(c: Canvas, p: Paint, text: String, x: Float, y: Float, maxWidth: Float) {
        val t = if (text.isBlank()) "-" else text
        val original = p.textSize
        var size = original
        while (size > 10f && p.measureText(t) > maxWidth) {
            size -= 1f
            p.textSize = size
        }
        c.drawText(t, x, y, p)
        p.textSize = original
    }

    private fun buildGeneric(context: Context, title: String, rows: List<Array<String>>): ByteArray {
        val W = 595; val H = 842
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, 1).create())
        val c = page.canvas
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val tf = loadTypeface(context)
        p.color = Color.rgb(8, 32, 79); c.drawRect(0f, 0f, W.toFloat(), 90f, p)
        p.color = Color.WHITE; p.textSize = 18f; p.textAlign = Paint.Align.RIGHT; p.typeface = tf ?: p.typeface
        c.drawText("تقنية معلومات محكمة البيضاء الابتدائية", W - 20f, 38f, p)
        p.textSize = 13f; p.color = Color.rgb(201, 162, 75)
        c.drawText(title, W - 20f, 66f, p)
        var y = 130f; p.textSize = 13f; p.textAlign = Paint.Align.RIGHT
        for (row in rows) {
            val label = row.getOrNull(0) ?: ""
            val value = row.getOrNull(1) ?: ""
            p.color = Color.rgb(18, 59, 114); c.drawText("$label :", W - 20f, y, p)
            p.color = Color.BLACK; c.drawText(value, W - 30f - p.measureText("$label : "), y, p)
            y += 26f; if (y > 760f) break
        }
        doc.finishPage(page)
        val bos = ByteArrayOutputStream(); doc.writeTo(bos); doc.close()
        return bos.toByteArray()
    }

    private fun buildSupreme(context: Context, title: String, rows: List<Array<String>>): ByteArray {
        return try {
            val file = openTemplate(context, "templates/supreme-court.pdf")
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            val outDoc = PdfDocument()
            val tf = loadTypeface(context)
            // Each supreme result is typically 6 fields as in bot
            val chunkSize = 6
            val chunks = if (rows.isEmpty()) listOf(emptyList()) else rows.toList().chunked(chunkSize)
            for ((idx, chunk) in chunks.withIndex()) {
                val src = renderer.openPage(0)
                val w = src.width * 2; val h = src.height * 2
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                src.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                src.close()
                val c = Canvas(bmp)
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textAlign = Paint.Align.CENTER; typeface = tf ?: typeface }
                val baselines = floatArrayOf(518f, 455f, 393f, 331f, 267f, 204f)
                val sx = w / 595.32f; val sy = h / 841.92f
                for (i in chunk.indices) {
                    val value = chunk[i].getOrNull(1) ?: "-"
                    val y = h - baselines.getOrElse(i) { 200f } * sy
                    p.textSize = 28f * sy
                    drawFittedText(c, p, value, 216f * sx, y, 330f * sx)
                }
                val page = outDoc.startPage(PdfDocument.PageInfo.Builder(w, h, idx + 1).create())
                page.canvas.drawBitmap(bmp, 0f, 0f, null)
                outDoc.finishPage(page)
                bmp.recycle()
            }
            renderer.close(); pfd.close()
            val bos = ByteArrayOutputStream(); outDoc.writeTo(bos); outDoc.close()
            bos.toByteArray()
        } catch (_: Exception) {
            buildGeneric(context, title, rows)
        }
    }

    fun saveToDownloads(context: Context, pdf: ByteArray, fileName: String) {
        val base = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "report" }
        val display = if (base.endsWith(".pdf", true)) base else "$base.pdf"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, display)
            put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
            put(MediaStore.Downloads.TITLE, base)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        try {
            context.contentResolver.openOutputStream(uri!!)?.use { it.write(pdf) }
            Toast.makeText(context, "✓ تم حفظ الملف: $display", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(context, "تعذر الحفظ: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun print(context: Context, pdf: ByteArray, jobName: String) {
        // printing removed per requirements; download only
    }
}
