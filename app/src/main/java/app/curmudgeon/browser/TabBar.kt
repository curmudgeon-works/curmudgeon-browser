// SPDX-License-Identifier: GPL-3.0-only
package app.curmudgeon.browser

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlin.math.abs

/**
 * Naked-style tab strip: fixed-width buttons whose titles wrap onto [Prefs.tabRows] lines.
 * Tap another tab to switch; close the current tab by tap, long press or vertical swipe (per settings).
 */
class TabBar(
    private val scroll: HorizontalScrollView,
    private val strip: LinearLayout,
    private val prefs: Prefs,
    private val onSelect: (Int) -> Unit,
    private val onClose: (Int) -> Unit,
    private val onTabMenu: (Int) -> Unit,
) {
    private val context = strip.context
    private val density = context.resources.displayMetrics.density

    fun render(tabs: List<Tab>, current: Int) {
        val rows = prefs.tabRows.coerceIn(1, 9)
        val width = (prefs.tabWidthDp * density).toInt()
        while (strip.childCount > tabs.size) strip.removeViewAt(strip.childCount - 1)
        while (strip.childCount < tabs.size) strip.addView(newButton())
        tabs.forEachIndexed { i, tab ->
            val tv = strip.getChildAt(i) as TextView
            tv.tag = i
            tv.layoutParams = LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.MATCH_PARENT)
            tv.minLines = rows
            tv.maxLines = rows
            val label = tab.label
            if (tv.text.toString() != label) tv.text = label
            val isCurrent = i == current
            tv.setTypeface(null, if (tab.isCached && !isCurrent) Typeface.ITALIC else Typeface.NORMAL)
            tv.setBackgroundColor(ContextCompat.getColor(context, when {
                isCurrent -> R.color.tab_current_bg
                i % 2 == 0 -> R.color.tab_bg
                else -> R.color.tab_bg_alt
            }))
            tv.setTextColor(ContextCompat.getColor(context, if (isCurrent) R.color.tab_current_text else R.color.tab_text))
            tv.contentDescription = if (isCurrent) "$label, current tab" else label
        }
        scrollToCurrent(current)
    }

    fun updateLabel(index: Int, tab: Tab) {
        (strip.getChildAt(index) as? TextView)?.let { if (it.text.toString() != tab.label) it.text = tab.label }
    }

    private fun scrollToCurrent(current: Int) {
        strip.post {
            val child = strip.getChildAt(current) ?: return@post
            val left = child.left
            val right = child.right
            if (left < scroll.scrollX || right > scroll.scrollX + scroll.width)
                scroll.smoothScrollTo((left - (scroll.width - child.width) / 2).coerceAtLeast(0), 0)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun newButton() = TextView(context).apply {
        gravity = Gravity.CENTER
        ellipsize = TextUtils.TruncateAt.END
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        val pad = (4 * density).toInt()
        setPadding(pad, pad, pad, pad)
        minHeight = (40 * density).toInt()
        isClickable = true
        isLongClickable = true
        setOnClickListener {
            val i = tag as Int
            if (i == currentIndexProvider() && prefs.tabCloseMethod == 0) onClose(i) else onSelect(i)
        }
        setOnLongClickListener {
            val i = tag as Int
            if (prefs.tabCloseMethod == 1) onClose(i) else onTabMenu(i)
            true
        }
        // vertical swipe to close
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downY = 0f
        var downX = 0f
        setOnTouchListener { v, ev ->
            if (prefs.tabCloseMethod != 2) return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY }
                MotionEvent.ACTION_MOVE -> {
                    val dy = ev.rawY - downY
                    if (abs(dy) > slop && abs(dy) > abs(ev.rawX - downX)) {
                        v.parent.requestDisallowInterceptTouchEvent(true)
                        v.translationY = dy
                        v.alpha = (1f - abs(dy) / (v.height * 1.5f)).coerceIn(0.2f, 1f)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dy = ev.rawY - downY
                    v.translationY = 0f
                    v.alpha = 1f
                    if (ev.actionMasked == MotionEvent.ACTION_UP && abs(dy) > v.height * 0.6f) {
                        onClose(v.tag as Int)
                        return@setOnTouchListener true
                    }
                }
            }
            false
        }
    }

    /** Set by the activity so a tap can tell whether it hit the current tab. */
    var currentIndexProvider: () -> Int = { -1 }
}
