package com.albayda.court

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/** In-app access to the Ministry of Justice's official case lookup and case card. */
class AppealCourtSearchActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var progress: ProgressBar

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
            layoutParams = LinearLayout.LayoutParams(0, dp(54), 1f)
        }
        val back = Button(this).apply {
            text = "رجوع"
            setTextColor(GREEN)
            setOnClickListener { finish() }
        }
        header.addView(title)
        header.addView(back)
        root.addView(header)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            progressTintList = ContextCompat.getColorStateList(this@AppealCourtSearchActivity, android.R.color.holo_green_dark)
        }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(3)))

        webView = WebView(this).apply {
            setBackgroundColor(-1)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.loadsImagesAutomatically = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.defaultTextEncodingName = "UTF-8"
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    progress.visibility = View.VISIBLE
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    progress.visibility = View.GONE
                    selectAppealCourtDefaults()
                }
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
            }
        }
        root.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        if (savedInstanceState == null) webView.loadUrl(SEARCH_URL) else webView.restoreState(savedInstanceState)
    }

    // The official form is cascading; choose matching Arabic options as they appear.
    private fun selectAppealCourtDefaults() {
        val script = """
            (function() {
              const wanted = ['محافظة البيضاء', 'محكمة استئناف', 'محكمة استئناف البيضاء'];
              function norm(s) { return (s || '').replace(/\s+/g, ' ').trim(); }
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
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
    override fun onDestroy() {
        if (::webView.isInitialized) { webView.stopLoading(); webView.destroy() }
        super.onDestroy()
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
