package com.albayda.court.util

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

class NewsTicker @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : AppCompatTextView(context, attrs) {

    private var animator: ValueAnimator? = null
    var speedPxPerSec: Float = 80f

    init {
        isSingleLine = true
        setHorizontallyScrolling(true)
    }

    fun setNews(items: List<String>) {
        val text = if (items.isEmpty())
            "مرحباً بكم في محكمة البيضاء الابتدائية"
        else items.joinToString("   ✦   ")
        setText(text)
        post { startLoop() }
    }

    fun setNewsText(text: String) {
        setText(text.ifBlank { "مرحباً بكم في محكمة البيضاء الابتدائية" })
        post { startLoop() }
    }

    private fun startLoop() {
        animator?.cancel()
        val parentW = (parent as? android.view.View)?.width?.toFloat() ?: width.toFloat()
        val tw = paint.measureText(text.toString())
        if (parentW <= 0f || tw <= 0f) {
            postDelayed({ startLoop() }, 100)
            return
        }
        val from = parentW
        val to = -tw
        val dist = Math.abs(to - from)
        animator = ValueAnimator.ofFloat(from, to).apply {
            duration = (dist / speedPxPerSec * 1000).toLong().coerceAtLeast(1000L)
            repeatMode = ValueAnimator.RESTART
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { translationX = it.animatedValue as Float }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}