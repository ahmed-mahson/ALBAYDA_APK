package com.albayda.court.util

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.provider.MediaStore
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

object PdfReport {

    fun build(context: Context, title: String, rows: List<Array<String>>): ByteArray {
        return try {
            when {
                title.contains("معلومات") || title.contains("قضية") || title.contains("سجل") || title.contains("استعلام عن قضية") ->
                    buildOnTemplate(context, "templates/case-info.pdf", title, rows)
                title.contains("المحكمة العليا") || title.contains("نتيجة") ->
                    buildSupreme(context, title, rows)
                title.contains("إعلان") || title.contains("حضور") ->
                    buildOnTemplate(context, "templates/attendance-ad.pdf", title, rows)
                title.contains("جلس") ->
                    buildOnTemplate(context, "templates/sessions.pdf", title, rows)
                else -> buildGeneric(title, rows)
            }
        } catch (_: Exception) {
            buildGeneric(title, rows)
        }
    }

    private fun loadTypeface(context: Context): Typeface? {
        return try { Typeface.createFromAsset(context.assets, "fonts/sultan-medium.ttf") } catch (_: Exception) { null }
    }

    private fun buildGeneric(title: String, rows: List<Array<String>>): ByteArray {
        val W = 595
        val H = 842
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, 1).create())
        val c = page.canvas
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.rgb(8, 32, 79); c.drawRect(0f, 0f, W.toFloat(), 90f, p)
        p.color = Color.WHITE; p.textSize = 18f; p.textAlign = Paint.Align.RIGHT
        c.drawText("تقنية معلومات محكمة البيضاء الابتدائية", W - 20f, 38f, p)
        p.textSize = 13f; p.color = Color.rgb(201, 162, 75)
        c.drawText(title, W - 20f, 66f, p)
        var y = 130f; p.textSize = 13f; p.textAlign = Paint.Align.RIGHT
        for (row in rows) {
            val label = row.getOrNull(0) ?: ""
            val value = row.getOrNull(1) ?: ""
            p.color = Color.rgb(18, 59, 114); c.drawText("$label :", W - 20f, y, p)
            p.color = Color.BLACK; c.drawText(value, W - 40f - p.measureText("$label : "), y, p)
            y += 26f; if (y > 760f) break
        }
        p.color = Color.rgb(8, 32, 79); c.drawRect(0f, H - 70f, W.toFloat(), H.toFloat(), p)
        p.color = Color.WHITE; p.textSize = 10f; p.textAlign = Paint.Align.CENTER
        c.drawText("برمجة وتصميم / احمد حسين محسون • Telegram: t.me/Ahmed_Mahson", W / 2f, H - 25f, p)
        doc.finishPage(page)
        val bos = ByteArrayOutputStream(); doc.writeTo(bos); doc.close()
        return bos.toByteArray()
    }

    private fun openTemplate(context: Context, assetPath: String): File {
        val out = File(context.cacheDir, assetPath.substringAfterLast('/'))
        if (!out.exists()) {
            context.assets.open(assetPath).use { input -> FileOutputStream(out).use { input.copyTo(it) } }
        }
        return out
    }

    private fun buildOnTemplate(context: Context, assetPath: String, title: String, rows: List<Array<String>>): ByteArray {
        val file = openTemplate(context, assetPath)
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        val pageCount = renderer.pageCount.coerceAtLeast(1)
        val outDoc = PdfDocument()
        val tf = loadTypeface(context)
        for (i in 0 until pageCount) {
            val src = renderer.openPage(i)
            val w = src.width * 2
            val h = src.height * 2
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            src.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            src.close()
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 28f; textAlign = Paint.Align.RIGHT; typeface = tf ?: typeface }
            if (i == 0) {
                drawRowsOnCanvas(c, p, w.toFloat(), h.toFloat(), title, rows, assetPath)
            }
            val page = outDoc.startPage(PdfDocument.PageInfo.Builder(w, h, i + 1).create())
            page.canvas.drawBitmap(bmp, 0f, 0f, null)
            outDoc.finishPage(page)
            bmp.recycle()
        }
        renderer.close(); pfd.close()
        val bos = ByteArrayOutputStream(); outDoc.writeTo(bos); outDoc.close()
        return bos.toByteArray()
    }

    private fun drawRowsOnCanvas(c: Canvas, p: Paint, w: Float, h: Float, title: String, rows: List<Array<String>>, assetPath: String) {
        val sx = w / 595f
        val sy = h / 842f
        p.textAlign = Paint.Align.RIGHT
        p.color = Color.rgb(10, 46, 102)
        if (assetPath.contains("case-info")) {
            // Approximate overlay positions for case info template
            val baseX = 393f * sx
            val valX = baseX - 10f * sx
            val labelX = w - 30f * sx
            val ys = floatArrayOf(610f, 551f, 492f, 432f, 373f, 314f, 255f, 195f, 136f).map { h - it * sy }
            val map = linkedMapOf<String, String>()
            rows.forEach { map[it.getOrNull(0) ?: ""] = it.getOrNull(1) ?: "" }
            val ordered = listOf("القاضي المختص","برئاسة القاضي","رقم القضية","تاريخ الورود","تاريخ ورودها","موضوع القضية","موضوعها","نوع القضية","المدعي","المدعى عليه","آخر قرار للقضية","حالة القضية","تاريخ الجلسة القادمة","الجلسة القادمة")
            var yi = 0
            val drawn = mutableSetOf<String>()
            fun drawLine(label: String, value: String) {
                if (yi >= ys.size) return
                val y = ys[yi++]
                p.textSize = 26f * sy; p.color = Color.rgb(8, 32, 79)
                c.drawText(value, valX, y, p)
            }
            for (r in rows) {
                val k = r.getOrNull(0)?.trim().orEmpty().replace(":","")
                val v = r.getOrNull(1)?.trim().orEmpty()
                if (k.isNotBlank() && v.isNotBlank()) drawLine(k, v)
                if (yi >= ys.size) break
            }
            return
        }
        if (assetPath.contains("attendance")) {
            p.textAlign = Paint.Align.CENTER; p.textSize = 28f * sy; p.color = Color.BLACK
            val map = rows.associate { (it.getOrNull(0)?:"") to (it.getOrNull(1)?:"") }
            c.drawText(map["المدعي"] ?: "", 380f * sx, h - 648f * sy, p)
            c.drawText(map["المدعى عليه"] ?: "", 380f * sx, h - 603f * sy, p)
            c.drawText(map["تاريخ الجلسة"] ?: "", 310f * sx, h - 521f * sy, p)
            c.drawText(map["القاضي"] ?: "", 211f * sx, h - 382f * sy, p)
            c.drawText(map["رقم القضية"] ?: "", 400f * sx, h - 560f * sy, p)
            return
        }
        // generic overlay for other templates
        p.textAlign = Paint.Align.RIGHT; p.color = Color.BLACK; p.textSize = 24f * sy
        var y = 180f * sy
        for (r in rows) {
            val line = "${r.getOrNull(0)?:""}: ${r.getOrNull(1)?:""}"
            c.drawText(line, w - 40f * sx, y, p)
            y += 34f * sy
            if (y > h - 120f * sy) break
        }
    }

    private fun buildSupreme(context: Context, title: String, rows: List<Array<String>>): ByteArray {
        // rows are [header, value] pairs possibly for multiple results concatenated
        return try {
            val file = openTemplate(context, "templates/supreme-court.pdf")
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            val outDoc = PdfDocument()
            val tf = loadTypeface(context)
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
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 30f; textAlign = Paint.Align.CENTER; typeface = tf ?: typeface }
                val baselines = floatArrayOf(518f, 455f, 393f, 331f, 267f, 204f)
                val sx = w / 595f; val sy = h / 842f
                for (i in chunk.indices) {
                    val value = chunk[i].getOrNull(1) ?: ""
                    val y = h - baselines.getOrElse(i) { 200f } * sy
                    c.drawText(value, 216f * sx, y, p)
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
            buildGeneric(title, rows)
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

    fun print(context: Context, pdf: ByteArray, jobName: String) {
        val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
        printManager.print(jobName, object : PrintDocumentAdapter() {
            override fun onLayout(
                old: PrintAttributes?, new: PrintAttributes?,
                signal: android.os.CancellationSignal?,
                callback: LayoutResultCallback?, extras: Bundle?
            ) {
                callback?.onLayoutFinished(
                    PrintDocumentInfo.Builder("report.pdf")
                        .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                        .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN).build(), true
                )
            }

            override fun onWrite(
                pages: Array<out PageRange?>,
                dest: ParcelFileDescriptor,
                signal: android.os.CancellationSignal?,
                callback: WriteResultCallback?
            ) {
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(dest).use { it.write(pdf) }
                    callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    callback?.onWriteFailed(e.message)
                }
            }
        }, null)
    }
}
