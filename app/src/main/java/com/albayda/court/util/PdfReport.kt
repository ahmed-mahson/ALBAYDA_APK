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

    private fun renderCaseInfoTemplate(context: Context, ordered: List<Pair<String, String>>): ByteArray {
        val file = openTemplate(context, "templates/case-info.pdf")
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        val outDoc = PdfDocument()
        val tf = loadTypeface(context)
        val src = renderer.openPage(0)
        val w = src.width * 2
        val h = src.height * 2
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        src.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
        src.close()
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textAlign = Paint.Align.RIGHT
            typeface = tf ?: typeface
        }
        val sx = w / 595.32f
        val sy = h / 841.92f
        val rightX = 393f * sx
        val baselines = floatArrayOf(609.94f, 550.63f, 491.35f, 431.95f, 372.65f, 313.37f, 254.09f, 194.78f, 136.22f)
        for (i in ordered.indices) {
            if (i >= baselines.size) break
            val value = ordered[i].second.ifBlank { "-" }
            val y = h - baselines[i] * sy
            p.textSize = (if (i == baselines.lastIndex) 24f else 28f) * sy
            drawFittedText(c, p, value, rightX, y, 340f * sx)
        }
        val page = outDoc.startPage(PdfDocument.PageInfo.Builder(w, h, 1).create())
        page.canvas.drawBitmap(bmp, 0f, 0f, null)
        outDoc.finishPage(page)
        bmp.recycle()
        renderer.close(); pfd.close()
        val bos = ByteArrayOutputStream(); outDoc.writeTo(bos); outDoc.close()
        return bos.toByteArray()
    }

    private fun renderAttendanceTemplate(context: Context, data: Map<String, String>): ByteArray {
        val file = openTemplate(context, "templates/attendance-ad.pdf")
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        val outDoc = PdfDocument()
        val tf = loadTypeface(context)
        val src = renderer.openPage(0)
        val w = src.width * 2
        val h = src.height * 2
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        src.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
        src.close()
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textAlign = Paint.Align.CENTER
            typeface = tf ?: typeface
            textSize = 28f * (h / 842f)
        }
        val sx = w / 595.32f
        val sy = h / 842f
        val plaintiff = data["المدعي"] ?: data["الطاعن"] ?: "-"
        val defendant = data["المدعى عليه"] ?: data["المطعون ضده"] ?: "-"
        val date = data["تاريخ الجلسة"] ?: "-"
        val judge = data["القاضي"] ?: data["اسم القاضي"] ?: data["القاضي المختص"] ?: "-"
        val caseNo = data["رقم القضية"] ?: "-"
        // Approximate positions for attendance template (tuned for common layout)
        drawFittedText(c, p, plaintiff, 360f * sx, h - 648f * sy, 320f * sx)
        drawFittedText(c, p, defendant, 360f * sx, h - 603f * sy, 320f * sx)
        drawFittedText(c, p, caseNo, 380f * sx, h - 560f * sy, 200f * sx)
        drawFittedText(c, p, date, 300f * sx, h - 520f * sy, 180f * sx)
        drawFittedText(c, p, judge, 210f * sx, h - 382f * sy, 220f * sx)
        val page = outDoc.startPage(PdfDocument.PageInfo.Builder(w, h, 1).create())
        page.canvas.drawBitmap(bmp, 0f, 0f, null)
        outDoc.finishPage(page)
        bmp.recycle()
        renderer.close(); pfd.close()
        val bos = ByteArrayOutputStream(); outDoc.writeTo(bos); outDoc.close()
        return bos.toByteArray()
    }

    private fun renderSessionsTemplate(context: Context, date: String, judgeFilter: String, records: List<CaseRecord>): ByteArray {
        val file = openTemplate(context, "templates/sessions.pdf")
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        val outDoc = PdfDocument()
        val tf = loadTypeface(context)
        val src0 = renderer.openPage(0)
        val w = src0.width * 2
        val h = src0.height * 2
        src0.close()

        // Table geometry approximated from bot
        val sx = w / 595.32f
        val sy = h / 841.92f
        val tableLeft = 43.8f * sx
        val tableRight = 552.58f * sx
        val topY = h - 621.26f * sy
        val bottomY = h - 127.82f * sy
        val rowH = 24.6f * sy
        val cols = floatArrayOf(43.8f, 174.5f, 305.21f, 367.03f, 460.9f, 523.78f, 552.58f).map { it * sx }
        // sequence col: between cols[5]-cols[6]; data cols: case_number cols[4]-[5], subject [3]-[4], case_type [2]-[3], plaintiff [1]-[2], defendant [0]-[1]
        val byJudge = records.groupBy { it.judge.ifBlank { "غير محدد" } }.toSortedMap()
        val showAll = judgeFilter.isBlank() || judgeFilter == "جميع القضاة" || judgeFilter == "الكل"
        val judges = if (showAll) byJudge.keys.toList() else listOf(judgeFilter)
        val rowsPerPage = ((topY - bottomY) / rowH).toInt().coerceAtLeast(1)
        var pageIndex = 0
        fun newPageBitmap(): Bitmap {
            val pfd2 = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val r2 = PdfRenderer(pfd2)
            val pg = r2.openPage(0)
            val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bm.eraseColor(Color.WHITE)
            pg.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            pg.close(); r2.close(); pfd2.close()
            return bm
        }
        for (jg in judges) {
            val list = byJudge[jg] ?: continue
            var idx = 0
            while (idx < list.size) {
                val chunk = list.subList(idx, kotlin.math.min(idx + rowsPerPage, list.size))
                val bmp = newPageBitmap()
                val c = Canvas(bmp)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = tf ?: typeface; color = Color.BLACK }
                // header: judge and date
                paint.textAlign = Paint.Align.RIGHT
                paint.textSize = 22f * sy
                c.drawText(jg, 391.5f * sx, h - 689.74f * sy, paint)
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = 20f * sy
                c.drawText(date, 300f * sx, h - 664.18f * sy, paint)
                // table body background
                paint.color = Color.WHITE
                c.drawRect(tableLeft, topY, tableRight, bottomY, paint)
                paint.color = Color.BLACK
                paint.textSize = 12f * sy
                paint.textAlign = Paint.Align.CENTER
                var yTop = topY
                for ((rowNum, rec) in chunk.withIndex()) {
                    val yBottom = yTop - rowH
                    val yCenter = (yTop + yBottom) / 2f + 4f * sy
                    val seq = (idx + rowNum + 1).toString()
                    // sequence col (rightmost)
                    drawFittedText(c, paint, seq, (cols[5] + cols[6]) / 2f, yCenter, (cols[6]-cols[5]-4))
                    // case number
                    drawFittedText(c, paint, rec.caseNumber.ifBlank { "-" }, (cols[4] + cols[5]) / 2f, yCenter, (cols[5]-cols[4]-6))
                    // subject
                    drawFittedText(c, paint, rec.subject.ifBlank { "-" }, (cols[3] + cols[4]) / 2f, yCenter, (cols[4]-cols[3]-6))
                    // case type
                    drawFittedText(c, paint, rec.caseType.ifBlank { "-" }, (cols[2] + cols[3]) / 2f, yCenter, (cols[3]-cols[2]-6))
                    // plaintiff
                    drawFittedText(c, paint, rec.plaintiff.ifBlank { "-" }, (cols[1] + cols[2]) / 2f, yCenter, (cols[2]-cols[1]-6))
                    // defendant
                    drawFittedText(c, paint, rec.defendant.ifBlank { "-" }, (cols[0] + cols[1]) / 2f, yCenter, (cols[1]-cols[0]-6))
                    // row line
                    paint.strokeWidth = 1f
                    c.drawLine(tableLeft, yBottom, tableRight, yBottom, paint)
                    yTop = yBottom
                }
                // borders
                paint.strokeWidth = 1.2f
                c.drawRect(tableLeft, bottomY, tableRight, topY, paint)
                for (x in cols) c.drawLine(x, topY, x, bottomY, paint)
                val page = outDoc.startPage(PdfDocument.PageInfo.Builder(w, h, pageIndex + 1).create())
                page.canvas.drawBitmap(bmp, 0f, 0f, null)
                outDoc.finishPage(page)
                bmp.recycle()
                pageIndex++
                idx += chunk.size
            }
        }
        if (pageIndex == 0) {
            // no pages produced
            val bmp = newPageBitmap()
            val page = outDoc.startPage(PdfDocument.PageInfo.Builder(w, h, 1).create())
            page.canvas.drawBitmap(bmp, 0f, 0f, null)
            outDoc.finishPage(page)
            bmp.recycle()
        }
        renderer.close(); pfd.close()
        val bos = ByteArrayOutputStream(); outDoc.writeTo(bos); outDoc.close()
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
                    p.textSize = 26f * sy
                    drawFittedText(c, p, value, 216f * sx, y, 320f * sx)
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
