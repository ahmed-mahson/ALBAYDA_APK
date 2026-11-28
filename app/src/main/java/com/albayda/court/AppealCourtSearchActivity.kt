package com.albayda.court

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.net.http.SslError
import androidx.appcompat.app.AppCompatActivity

/** Ministry of Justice appeal-court case lookup. */
class AppealCourtSearchActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar
    private lateinit var errorPanel: LinearLayout
    private lateinit var errorText: TextView

    companion object {
        private const val SEARCH_URL = "https://judg.moj.gov.ye/JUDDATALIST/CustomRetCaseMaster"
        private const val GREEN = 0xFF188457.toInt()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(0xFFF4F7F6.toInt())
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundColor(GREEN)
        }
        val title = TextView(this).apply {
            text = "البحث والاستعلام في قضايا وأحكام\nمحكمة استئناف محافظة البيضاء"
            setTextColor(-1)
            textSize = 17f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, dp(58), 1f)
        }
        val back = Button(this).apply {
            text = "رجوع"
            setTextColor(GREEN)
            setOnClickListener { finish() }
        }
        header.addView(title)
        header.addView(back)
        root.addView(header)

        val content = FrameLayout(this)
        webView = WebView(this).apply {
            setBackgroundColor(-1)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.loadsImagesAutomatically = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.defaultTextEncodingName = "UTF-8"
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progress.progress = newProgress
                    progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    errorPanel.visibility = View.GONE
                    progress.visibility = View.VISIBLE
                    progress.progress = 5
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    progress.visibility = View.GONE
                    selectAppealCourtDefaults()
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        showLoadError("تعذر تحميل صفحة وزارة العدل. تحقق من اتصال الإنترنت ثم أعد المحاولة.\nرمز الخطأ: ${error?.errorCode ?: "غير معروف"}")
                    }
                }

                @Suppress("DEPRECATION")
                override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                    if (android.os.Build.VERSION.SDK_INT < 23) {
                        showLoadError("تعذر تحميل صفحة وزارة العدل: ${description ?: "خطأ اتصال"}")
                    }
                }

                override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: android.webkit.WebResourceResponse?) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    if (request?.isForMainFrame == true) {
                        showLoadError("رفض خادم وزارة العدل الطلب أو أعاد خطأ HTTP (${errorResponse?.statusCode ?: "؟"}). يمكنك فتح الصفحة في متصفح الهاتف.")
                    }
                }

                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                    handler?.cancel()
                    showLoadError("تعذر التحقق من شهادة الأمان لموقع وزارة العدل. لم يتم تجاوز فحص الأمان.")
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url ?: return false
                    return if (url.scheme == "http" || url.scheme == "https") false else {
                        try { startActivity(Intent(Intent.ACTION_VIEW, url)) } catch (_: Exception) {
                            Toast.makeText(this@AppealCourtSearchActivity, "لا يوجد تطبيق لفتح هذا الرابط", Toast.LENGTH_SHORT).show()
                        }
                        true
                    }
                }
            }
        }
        content.addView(webView, FrameLayout.LayoutParams(-1, -1))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(GREEN)
            visibility = View.GONE
        }
        content.addView(progress, FrameLayout.LayoutParams(-1, dp(4), Gravity.TOP))

        errorPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(24), dp(28), dp(24))
            setBackgroundColor(-1)
            visibility = View.GONE
        }
        errorText = TextView(this).apply {
            text = "تعذر تحميل صفحة الاستعلام"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(0xFF263238.toInt())
        }
        errorPanel.addView(errorText, LinearLayout.LayoutParams(-1, -2))
        val actions = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
        }
        val retry = Button(this).apply {
            text = "إعادة المحاولة"
            setOnClickListener { errorPanel.visibility = View.GONE; webView.loadUrl(SEARCH_URL) }
        }
        val external = Button(this).apply {
            text = "فتح في المتصفح"
            setOnClickListener {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SEARCH_URL))) }
                catch (_: Exception) { Toast.makeText(this@AppealCourtSearchActivity, "تعذر فتح المتصفح", Toast.LENGTH_SHORT).show() }
            }
        }
        actions.addView(retry)
        actions.addView(external)
        errorPanel.addView(actions)
        content.addView(errorPanel, FrameLayout.LayoutParams(-1, -1))
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        if (savedInstanceState == null) webView.loadUrl(SEARCH_URL)
        else if (webView.restoreState(savedInstanceState) == null) webView.loadUrl(SEARCH_URL)
    }

    private fun showLoadError(message: String) {
        runOnUiThread {
            progress.visibility = View.GONE
            errorText.text = message
            errorPanel.visibility = View.VISIBLE
        }
    }

    private fun selectAppealCourtDefaults() {
        val script = """
            (function() {
              const wanted = ['محافظة البيضاء', 'محكمة استئناف', 'محكمة استئناف البيضاء'];
              function norm(s) { return (s || '').replace(/\\s+/g, ' ').trim(); }
              function choose(text) {
                for (const sel of Array.from(document.querySelectorAll('select'))) {
                  const opt = Array.from(sel.options).find(o => norm(o.text).includes(text));
                  if (opt && sel.value !== opt.value) {
                    sel.value = opt.value;
                    sel.dispatchEvent(new Event('change', {bubbles:true}));
                    return true;
                  }
                  if (opt) return true;
                }
                return false;
              }
              let tries = 0;
              const timer = setInterval(() => {
                tries++;
                const a = choose(wanted[0]);
                const b = choose(wanted[1]);
                const c = choose(wanted[2]);
                if ((a && b && c) || tries > 25) clearInterval(timer);
              }, 700);
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) { webView.stopLoading(); webView.destroy() }
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
