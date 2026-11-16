package com.albayda.court.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.provider.MediaStore
import android.widget.Toast
import java.io.ByteArrayOutputStream

object PdfReport {

    fun build(context: Context, title: String, rows: List<Array<String>>): ByteArray {
        val W = 595f
        val H = 842f
        val doc = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(W.toInt(), H.toInt(), 1).create()
        val page = doc.startPage(pageInfo)
        val c = page.canvas
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // رأس الصفحة
        p.color = Color.rgb(8, 32, 79)
        c.drawRect(0f, 0f, W, 90f, p)
        p.color = Color.WHITE
        p.textSize = 18f
        c.drawText("تقنية معلومات محكمة البيضاء الابتدائية", W - 20f, 38f, p)
        p.textSize = 13f
        p.color = Color.rgb(201, 162, 75)
        c.drawText(title, W - 20f, 66f, p)

        // المحتوى
        var y = 130f
        p.textSize = 13f
        for (row in rows) {
            p.color = Color.rgb(18, 59, 114)
            c.drawText("${row[0]} :", W - 20f, y, p)
            p.color = Color.BLACK
            c.drawText(row[1] ?: "", W - 20f - p.measureText("${row[0]} :"), y, p)
            y += 26f
            if (y > 760f) break
        }

        // الهامش السفلي
        p.color = Color.rgb(8, 32, 79)
        c.drawRect(0f, H - 70f, W, H, p)
        p.color = Color.WHITE
        p.textSize = 10f
        c.drawText(
            "برمجة وتصميم / احمد حسين محسون • 734840710-771421919 • Telegram: t.me/Ahmed_Mahson",
            W / 2f, H - 25f, p
        )

        doc.finishPage(page)
        val bos = ByteArrayOutputStream()
        doc.writeTo(bos)
        doc.close()
        return bos.toByteArray()
    }

    fun saveToDownloads(context: Context, pdf: ByteArray, fileName: String) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "report_${System.currentTimeMillis()}.pdf")
            put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
            put(MediaStore.Downloads.TITLE, fileName)
        }
        val uri = context.contentResolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
        )
        try {
            context.contentResolver.openOutputStream(uri!!)?.use { it.write(pdf) }
            Toast.makeText(context, "✓ تم حفظ التقرير في التنزيلات", Toast.LENGTH_LONG).show()
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
                        .setPageCount(1).build(), true
                )
            }

            override fun onWrite(
                pages: Array<out PageRange?>,
                dest: android.os.ParcelFileDescriptor,
                signal: android.os.CancellationSignal?,
                callback: WriteResultCallback?
            ) {
                try {
                    android.os.ParcelFileDescriptor.AutoCloseOutputStream(dest)
                        .use { it.write(pdf) }
                    callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    callback?.onWriteFailed(e.message)
                }
            }
        }, null)
    }
}