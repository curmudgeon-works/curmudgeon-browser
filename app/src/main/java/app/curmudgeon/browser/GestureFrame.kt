// SPDX-License-Identifier: GPL-3.0-only
package app.curmudgeon.browser

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Hosts the current WebView and recognises the browser's own gestures on top of it without breaking
 * normal page scrolling: edge swipes (top, bottom, left), swipes along the bottom edge, bottom-corner
 * swipes and taps, two-finger swipes, and double tap & drag. Touches that are not one of these pass
 * straight through; once a gesture is recognised the WebView receives ACTION_CANCEL.
 */
class GestureFrame @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {

    enum class Edge { TOP_DOWN, BOTTOM_UP, LEFT_IN }

    interface Listener {
        /** Pref key of a bindable gesture (e.g. [Prefs.TWO_FINGER_LEFT]) -> is it bound to something? */
        fun isBound(gestureKey: String): Boolean
        fun onGesture(gestureKey: String)
        fun edgeEnabled(edge: Edge): Boolean
        fun onEdgeSwipe(edge: Edge)
        /** A plain single-finger drag the page received; start* tell whether the page was at a scroll limit at touch down. */
        fun onPageDrag(dx: Float, dy: Float, startAtTop: Boolean, startAtBottom: Boolean, startAtLeft: Boolean)
        fun onDoubleTap()
        /** The page's own double-tap zoom is wanted (if false a double tap is swallowed). */
        fun pageDoubleTapZoom(): Boolean
        fun onTouchDown()
    }

    var listener: Listener? = null
    /** Pixels at the bottom of this frame that belong to the system's home gesture; bottom-edge gestures start above them. */
    var bottomReserved = 0
    var webView: android.webkit.WebView? = null

    private val density = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val edgePx = 22 * density
    private val cornerPx = 56 * density
    private val swipePx = 56 * density
    private val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

    private enum class Region { NONE, TOP, BOTTOM, LEFT, BOTTOM_LEFT_CORNER, BOTTOM_RIGHT_CORNER }
    private enum class Mode { PASS, CONSUMED }

    private var mode = Mode.PASS
    private var region = Region.NONE
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var maxPointers = 1
    private var twoFingerStart: Pair<Float, Float>? = null
    private var twoFingerSpan = 0f
    private var startAtTop = false
    private var startAtBottom = false
    private var startAtLeft = false
    private var lastTapUpTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var doubleTapCandidate = false
    private var hotspotsPaused = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val l = listener ?: return super.dispatchTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = Mode.PASS
                maxPointers = 1
                twoFingerStart = null
                downX = ev.x; downY = ev.y; downTime = ev.eventTime
                region = regionAt(ev.x, ev.y)
                val wv = webView
                startAtTop = wv?.canScrollVertically(-1) != true
                startAtBottom = wv?.canScrollVertically(1) != true
                startAtLeft = wv?.canScrollHorizontally(-1) != true
                doubleTapCandidate = ev.eventTime - lastTapUpTime < doubleTapTimeout &&
                    hypot(ev.x - lastTapX, ev.y - lastTapY) < 48 * density
                l.onTouchDown()
                if (doubleTapCandidate && !l.pageDoubleTapZoom()) {
                    // swallow the second tap so the page does not zoom; decide tap vs drag on move / up
                    mode = Mode.CONSUMED
                    return true
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = maxOf(maxPointers, ev.pointerCount)
                if (ev.pointerCount == 2 && mode == Mode.PASS) {
                    twoFingerStart = centroid(ev)
                    twoFingerSpan = span(ev)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.CONSUMED) {
                    if (doubleTapCandidate) checkDoubleTapDrag(ev, l)
                    return true
                }
                if (ev.pointerCount >= 2) { checkTwoFinger(ev, l); if (mode == Mode.CONSUMED) return true }
                else if (maxPointers == 1) {
                    if (doubleTapCandidate && checkDoubleTapDrag(ev, l)) return true
                    if (checkEdge(ev, l)) return true
                }
            }
            MotionEvent.ACTION_UP -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                val moved = hypot(dx, dy) > slop
                val duration = ev.eventTime - downTime
                if (mode == Mode.CONSUMED) {
                    if (doubleTapCandidate && !moved) { l.onDoubleTap(); lastTapUpTime = 0 }
                    mode = Mode.PASS
                    return true
                }
                if (!moved && maxPointers == 1) {
                    val cornerKey = when (region) {
                        Region.BOTTOM_LEFT_CORNER -> Prefs.BOTTOM_LEFT_CORNER_TAP
                        Region.BOTTOM_RIGHT_CORNER -> Prefs.BOTTOM_RIGHT_CORNER_TAP
                        else -> null
                    }
                    if (cornerKey != null && duration >= longPressTimeout * 2 && l.isBound(cornerKey)) {
                        hotspotsPaused = true
                    } else if (cornerKey != null && !hotspotsPaused && duration < longPressTimeout && l.isBound(cornerKey)) {
                        cancelChild(ev)
                        l.onGesture(cornerKey)
                        return true
                    } else if (cornerKey == null) {
                        hotspotsPaused = false
                    }
                    if (doubleTapCandidate) { l.onDoubleTap(); lastTapUpTime = 0 }
                    else { lastTapUpTime = ev.eventTime; lastTapX = ev.x; lastTapY = ev.y }
                } else if (maxPointers == 1 && moved) {
                    l.onPageDrag(dx, dy, startAtTop, startAtBottom, startAtLeft)
                    lastTapUpTime = 0
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (mode == Mode.CONSUMED) { mode = Mode.PASS; return true }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun regionAt(x: Float, y: Float): Region {
        val w = width.toFloat()
        val h = height.toFloat() - bottomReserved
        if (y > h) return Region.NONE
        return when {
            y > h - cornerPx && x < cornerPx -> Region.BOTTOM_LEFT_CORNER
            y > h - cornerPx && x > w - cornerPx -> Region.BOTTOM_RIGHT_CORNER
            y > h - edgePx -> Region.BOTTOM
            y < edgePx -> Region.TOP
            x < edgePx -> Region.LEFT
            else -> Region.NONE
        }
    }

    /** Recognises swipes that start in an edge or corner region. Returns true once consumed. */
    private fun checkEdge(ev: MotionEvent, l: Listener): Boolean {
        if (region == Region.NONE) return false
        val dx = ev.x - downX
        val dy = ev.y - downY
        if (hypot(dx, dy) < swipePx / 2) return false
        val horizontal = abs(dx) > abs(dy) * 1.5f
        val vertical = abs(dy) > abs(dx) * 1.5f
        val action: (() -> Unit)? = when (region) {
            Region.TOP -> if (vertical && dy > 0 && l.edgeEnabled(Edge.TOP_DOWN)) ({ l.onEdgeSwipe(Edge.TOP_DOWN) }) else null
            Region.LEFT -> if (horizontal && dx > 0 && l.edgeEnabled(Edge.LEFT_IN)) ({ l.onEdgeSwipe(Edge.LEFT_IN) }) else null
            Region.BOTTOM -> when {
                vertical && dy < 0 && l.edgeEnabled(Edge.BOTTOM_UP) -> ({ l.onEdgeSwipe(Edge.BOTTOM_UP) })
                horizontal && dx < 0 && l.isBound(Prefs.BOTTOM_SWIPE_LEFT) -> ({ l.onGesture(Prefs.BOTTOM_SWIPE_LEFT) })
                horizontal && dx > 0 && l.isBound(Prefs.BOTTOM_SWIPE_RIGHT) -> ({ l.onGesture(Prefs.BOTTOM_SWIPE_RIGHT) })
                else -> null
            }
            Region.BOTTOM_LEFT_CORNER -> when {
                horizontal && dx > 0 && l.isBound(Prefs.BOTTOM_LEFT_CORNER_ACROSS) -> ({ l.onGesture(Prefs.BOTTOM_LEFT_CORNER_ACROSS) })
                vertical && dy < 0 && l.isBound(Prefs.BOTTOM_LEFT_CORNER_UP) -> ({ l.onGesture(Prefs.BOTTOM_LEFT_CORNER_UP) })
                vertical && dy < 0 && l.edgeEnabled(Edge.BOTTOM_UP) -> ({ l.onEdgeSwipe(Edge.BOTTOM_UP) })
                else -> null
            }
            Region.BOTTOM_RIGHT_CORNER -> when {
                horizontal && dx < 0 && l.isBound(Prefs.BOTTOM_RIGHT_CORNER_ACROSS) -> ({ l.onGesture(Prefs.BOTTOM_RIGHT_CORNER_ACROSS) })
                vertical && dy < 0 && l.isBound(Prefs.BOTTOM_RIGHT_CORNER_UP) -> ({ l.onGesture(Prefs.BOTTOM_RIGHT_CORNER_UP) })
                vertical && dy < 0 && l.edgeEnabled(Edge.BOTTOM_UP) -> ({ l.onEdgeSwipe(Edge.BOTTOM_UP) })
                else -> null
            }
            Region.NONE -> null
        }
        region = Region.NONE // decide only once per touch
        if (action == null) return false
        cancelChild(ev)
        mode = Mode.CONSUMED
        action()
        return true
    }

    private fun checkTwoFinger(ev: MotionEvent, l: Listener) {
        val start = twoFingerStart ?: return
        val c = centroid(ev)
        val dx = c.first - start.first
        val dy = c.second - start.second
        if (hypot(dx, dy) < swipePx) return
        // a pinch changes the finger distance a lot; a swipe keeps it roughly constant
        if (twoFingerSpan > 0 && abs(span(ev) - twoFingerSpan) > twoFingerSpan * 0.35f) { twoFingerStart = null; return }
        val key = if (abs(dx) > abs(dy)) (if (dx < 0) Prefs.TWO_FINGER_LEFT else Prefs.TWO_FINGER_RIGHT)
        else (if (dy < 0) Prefs.TWO_FINGER_UP else Prefs.TWO_FINGER_DOWN)
        twoFingerStart = null
        if (!l.isBound(key)) return
        cancelChild(ev)
        mode = Mode.CONSUMED
        l.onGesture(key)
    }

    /** Double tap, keep the finger down and drag: recognised once the drag passes [swipePx]. */
    private fun checkDoubleTapDrag(ev: MotionEvent, l: Listener): Boolean {
        val dx = ev.x - downX
        val dy = ev.y - downY
        if (hypot(dx, dy) < swipePx) return false
        val key = if (abs(dx) > abs(dy)) (if (dx < 0) Prefs.DOUBLE_TAP_LEFT else Prefs.DOUBLE_TAP_RIGHT)
        else (if (dy < 0) Prefs.DOUBLE_TAP_UP else Prefs.DOUBLE_TAP_DOWN)
        doubleTapCandidate = false
        lastTapUpTime = 0
        if (!l.isBound(key)) return false
        if (mode == Mode.PASS) cancelChild(ev)
        mode = Mode.CONSUMED
        l.onGesture(key)
        return true
    }

    private fun cancelChild(ev: MotionEvent) {
        val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
    }

    private fun centroid(ev: MotionEvent): Pair<Float, Float> {
        var x = 0f; var y = 0f
        val n = minOf(ev.pointerCount, 2)
        for (i in 0 until n) { x += ev.getX(i); y += ev.getY(i) }
        return x / n to y / n
    }

    private fun span(ev: MotionEvent) = if (ev.pointerCount < 2) 0f else hypot(ev.getX(0) - ev.getX(1), ev.getY(0) - ev.getY(1))

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // keep the system back gesture from stealing the left-edge sidebar swipe (Android caps this at 200dp)
        val band = (200 * density).toInt().coerceAtMost(height)
        val top0 = (height - band) / 2
        systemGestureExclusionRects = listOf(Rect(0, top0, edgePx.toInt() * 2, top0 + band))
    }
}
