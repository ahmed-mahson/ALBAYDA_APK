package com.albayda.court.util

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

class NewsTicker @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : AppCompatTextView(context, attrs) {

    private var animator: ValueAnimator? = null

    init {
        isSingleLine = true
        setHorizontallyScrolling(true)
    }

    fun setNews(items: List<String>) {
        val text = if (items.isEmpty())
            "مرحباً بكم في البوابة الإلكترونية لمحكمة البيضاء الابتدائية"
        else items.joinToString("   ✦   ")
        setText(text)
        post { startLoop() }
    }

    private fun startLoop() {
        animator?.cancel()
        val w = width.toFloat()
        val tw = paint.measureText(text.toString())
        if (w <= 0f || tw <= 0f) {
            postDelayed({ startLoop() }, 100)
            return
        }
        val from = w
        val to = -tw
        val dist = Math.abs(to - from)
        animator = ValueAnimator.ofFloat(from, to).apply {
            duration = (dist / 40f * 1000).toLong().coerceAtLeast(1000L)
            repeatMode = ValueAnimator.RESTART
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { translationX = it.animatedValue as Float }
            start()
        }
    }

}