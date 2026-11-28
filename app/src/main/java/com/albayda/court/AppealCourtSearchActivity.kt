package com.albayda.court

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/**
 * Native-looking Ministry case search form.
 * The form remains available even when the Ministry host is temporarily unreachable.
 */
class AppealCourtSearchActivity : AppCompatActivity() {
    private lateinit var formView: WebView
    private lateinit var ministryView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var container: FrameLayout

    companion object {
        private const val SEARCH_URL = "https://judg.moj.gov.ye/JUDDATALIST/CustomRetCaseMaster"
        private const val GREEN = "#188457"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.WHITE)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val header = TextView(this).apply {
            text = "البحث والاستعلام في قضايا وأحكام\nمحكمة استئناف محافظة البيضاء       رجوع"
            textSize = 20f
            setTextColor(android.graphics.Color.WHITE)
            gravity = android.view.Gravity.CENTER
            setPadding(dp(14), dp(16), dp(14), dp(16))
            setBackgroundColor(android.graphics.Color.parseColor(GREEN))
            setOnClickListener { finish() }
        }
        root.addView(header, LinearLayout.LayoutParams(-1, dp(92)))

        container = FrameLayout(this)
        formView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(FormBridge(), "AndroidForm")
        }
        ministryView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadsImagesAutomatically = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.defaultTextEncodingName = "UTF-8"
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    progress.visibility = View.VISIBLE
                    status.text = "جارٍ الاتصال بموقع وزارة العدل..."
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    progress.visibility = View.GONE
                    status.text = "تم تحميل موقع الوزارة. إذا لم تظهر النتيجة، استخدم فتح في المتصفح."
                    fillMinistryForm()
                }
                override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                    if (request?.isForMainFrame == true) {
                        progress.visibility = View.GONE
                        status.text = "تعذر الاتصال بموقع وزارة العدل. تحقق من الإنترنت أو افتح الموقع في المتصفح."
                    }
                }
                override fun onReceivedHttpError(view: WebView?, request: android.webkit.WebResourceRequest?, response: android.webkit.WebResourceResponse?) {
                    if (request?.isForMainFrame == true) {
                        progress.visibility = View.GONE
                        status.text = "خادم وزارة العدل أعاد خطأ HTTP: ${response?.statusCode ?: "غير معروف"}"
                    }
                }
            }
        }
        container.addView(formView, FrameLayout.LayoutParams(-1, -1))
        container.addView(ministryView, FrameLayout.LayoutParams(-1, -1).apply { })
        ministryView.visibility = View.GONE

        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progress.visibility = View.GONE
        overlay.addView(progress, LinearLayout.LayoutParams(-1, dp(4)))
        status = TextView(this).apply {
            text = ""
            textSize = 12f
            setTextColor(android.graphics.Color.DKGRAY)
            gravity = android.view.Gravity.CENTER
        }
        overlay.addView(status, LinearLayout.LayoutParams(-1, -2))
        root.addView(container, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(overlay, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)

        formView.loadDataWithBaseURL("https://local.albayda/", htmlPage(), "text/html", "UTF-8", null)
    }

    private inner class FormBridge {
        @JavascriptInterface
        fun submit(payload: String) {
            runOnUiThread {
                val values = JSONObject(payload)
                status.text = "جارٍ إرسال بيانات البحث إلى موقع وزارة العدل..."
                ministryView.visibility = View.VISIBLE
                formView.visibility = View.GONE
                ministryView.loadUrl(SEARCH_URL)
                pendingSearch = values
            }
        }
        @JavascriptInterface
        fun backToForm() {
            runOnUiThread {
                ministryView.visibility = View.GONE
                formView.visibility = View.VISIBLE
            }
        }
        @JavascriptInterface
        fun openExternal() {
            runOnUiThread {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SEARCH_URL))) }
                catch (_: Exception) { Toast.makeText(this@AppealCourtSearchActivity, "تعذر فتح المتصفح", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private var pendingSearch: JSONObject? = null

    private fun fillMinistryForm() {
        val data = pendingSearch ?: return
        val payload = JSONObject.quote(data.toString())
        val script = """
          (function(){
            var p = JSON.parse($payload);
            var sels = Array.from(document.querySelectorAll('select'));
            var wanted = [p.authority,p.courtType,p.court,p.caseType,p.subType];
            function norm(s){return (s||'').replace(/\\s+/g,' ').trim();}
            wanted.forEach(function(v,i){
              if(!v || !sels[i]) return;
              var opts=Array.from(sels[i].options);
              var opt=opts.find(function(o){return norm(o.text)===norm(v)}) ||
                      opts.find(function(o){return norm(o.text).indexOf(norm(v))>=0});
              if(opt){sels[i].value=opt.value;sels[i].dispatchEvent(new Event('change',{bubbles:true}));}
            });
            var visible=Array.from(document.querySelectorAll('input')).filter(function(x){return x.type!=='hidden' && x.offsetParent!==null;});
            var number=visible.find(function(x){return /رقم|case|number/i.test(x.name+' '+x.id+' '+x.placeholder);});
            var year=visible.find(function(x){return /سنة|year/i.test(x.name+' '+x.id+' '+x.placeholder);});
            if(!number) number=visible.find(function(x){return /0000/.test(x.placeholder||'')}) || visible[visible.length-1];
            if(!year) year=visible.find(function(x){return /144[0-9]/.test(x.value||x.placeholder||'')}) || visible[visible.length-2];
            if(number){number.value=p.caseNumber;number.dispatchEvent(new Event('input',{bubbles:true}));number.dispatchEvent(new Event('change',{bubbles:true}));}
            if(year){year.value=p.year;year.dispatchEvent(new Event('input',{bubbles:true}));year.dispatchEvent(new Event('change',{bubbles:true}));}
            var btn=Array.from(document.querySelectorAll('button,input[type=submit],a')).find(function(x){return /بحث/.test(norm(x.innerText||x.value));});
            if(btn){setTimeout(function(){btn.click();},500);}
            else {window.scrollTo(0,document.body.scrollHeight);}
          })();
        """.trimIndent()
        ministryView.evaluateJavascript(script, null)
        pendingSearch = null
    }

    private fun htmlPage() = """
<!doctype html><html lang="ar" dir="rtl"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<style>
*{box-sizing:border-box}body{margin:0;background:#f7faf8;font-family:Tahoma,Arial,sans-serif;color:#333}
.card{margin:0 5px 8px;background:#fff;border-radius:0 0 28px 28px;padding:22px 6.5% 28px;box-shadow:0 2px 9px #0001}
label{display:block;color:#188457;font-size:21px;font-weight:bold;margin:20px 0 10px}
select,input{width:100%;height:58px;border:1.5px solid #d1d6da;border-radius:9px;background:#fff;color:#555;font-size:19px;padding:0 15px;text-align:right;direction:rtl}
.row{display:flex;direction:ltr;align-items:center;margin-top:8px}.row input{border-radius:0;text-align:center;font-weight:bold;color:#252525}.row input:first-child{border-radius:0 8px 8px 0}.row input:last-child{border-radius:8px 0 0 8px}.slash{padding:17px;background:#e8ecef;color:#188457;font-size:22px;font-weight:bold}
button{margin-top:26px;width:100%;height:66px;border:0;border-radius:9px;background:#188457;color:#fff;font-size:25px;font-weight:bold}
.note{font-size:12px;color:#777;text-align:center;margin-top:12px}
</style></head><body>
<div class="card">
<label>الجهة الرئيسية</label><select id="authority"><option>محافظة البيضاء</option></select>
<label>نوع المحكمة</label><select id="courtType"><option>محكمة استئناف</option></select>
<label>المحكمة</label><select id="court"><option>محكمة استئناف البيضاء</option></select>
<label>نوع القضية</label><select id="caseType"><option>مدنية</option><option>جزائية</option><option>شخصية</option><option>تجارية</option><option>إدارية</option></select>
<label>النوع الفرعي</label><select id="subType"><option>مدنية</option></select>
<label>رقم القضية (الرقم / السنة هـ)</label>
<div class="row"><input id="year" value="1448" inputmode="numeric" aria-label="السنة الهجرية"><span class="slash">/</span><input id="caseNumber" value="8" inputmode="numeric" aria-label="رقم القضية"></div>
<button onclick="search()">بحث　⌕</button>
<div class="note">يتم إرسال بيانات البحث إلى خدمة وزارة العدل الرسمية</div>
</div>
<script>
function search(){
 const p={authority:document.getElementById('authority').value,courtType:document.getElementById('courtType').value,court:document.getElementById('court').value,caseType:document.getElementById('caseType').value,subType:document.getElementById('subType').value,year:document.getElementById('year').value.trim(),caseNumber:document.getElementById('caseNumber').value.trim()};
 if(!p.caseNumber||!p.year){alert('يرجى إدخال رقم القضية والسنة الهجرية');return;}
 AndroidForm.submit(JSON.stringify(p));
}
</script></body></html>
    """.trimIndent()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        if (::formView.isInitialized) formView.destroy()
        if (::ministryView.isInitialized) { ministryView.stopLoading(); ministryView.destroy() }
        super.onDestroy()
    }
}
