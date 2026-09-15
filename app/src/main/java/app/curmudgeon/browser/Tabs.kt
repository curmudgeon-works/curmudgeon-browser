// SPDX-License-Identifier: GPL-3.0-only
package app.curmudgeon.browser

import android.os.Bundle
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject

/**
 * One browser tab. The [webView] may be null when the tab is "cached": its page state lives in
 * [savedState] (or only [url] after a restart) and a WebView is recreated when the tab is shown again.
 */
class Tab(val id: Long, var url: String, var title: String = "") {
    var webView: WebView? = null
    var savedState: Bundle? = null
    var progress = 100
    var desktopMode = false
    /** Opened from another app's link: back at the start of its history returns to that app. */
    var openedExternally = false
    /** The tab whose page opened this one (a new window or "Open in new tab"): back at the start of history returns there. */
    var openerId: Long? = null
    /** Unfinished text in the address bar, kept while the user looks at another tab. */
    var addressDraft: String? = null
    /** JavaScript / images temporarily allowed for this tab from the bottom menu. */
    var tempJavaScript = false
    var tempImages = false
    var lastUsed = System.currentTimeMillis()
    val isCached get() = webView == null
    val label get() = UrlUtils.tabLabel(title, url)
}

/** Closed tab kept for undo: URL, title and its back/forward history when it still had one. */
data class ClosedTab(val url: String, val title: String, val state: Bundle?)

/**
 * Tab list and selection. Owns no views: [createWebView] and [destroyWebView] are supplied by the activity.
 * Keeps at most [cacheThreshold] live WebViews (least recently used tabs are cached to a Bundle).
 */
class TabManager(
    private val createWebView: (Tab) -> WebView,
    private val destroyWebView: (Tab) -> Unit,
    private val load: (Tab) -> Unit,
) {
    val tabs = mutableListOf<Tab>()
    var currentIndex = -1
        private set
    val current: Tab? get() = tabs.getOrNull(currentIndex)
    val closed = ArrayDeque<ClosedTab>()
    var cacheThreshold = 6
    var onChanged: () -> Unit = {}
    private var nextId = 1L

    fun newTab(url: String, select: Boolean = true, afterCurrent: Boolean = true, state: Bundle? = null): Tab {
        val tab = Tab(nextId++, url)
        tab.savedState = state
        val index = if (afterCurrent && currentIndex >= 0) currentIndex + 1 else tabs.size
        tabs.add(index, tab)
        if (currentIndex >= index && !select) currentIndex++
        if (select) select(index) else onChanged()
        return tab
    }

    /** Makes the tab at [index] current, creating (or restoring) its WebView if it was cached. */
    fun select(index: Int) {
        if (index !in tabs.indices) return
        currentIndex = index
        val tab = tabs[index]
        tab.lastUsed = System.currentTimeMillis()
        if (tab.webView == null) {
            val wv = createWebView(tab)
            tab.webView = wv
            val state = tab.savedState
            if (state != null && wv.restoreState(state) != null) tab.savedState = null
            else load(tab)
        }
        enforceCache()
        onChanged()
    }

    /** Closes the tab at [index]. With [returnToOpener] the tab that opened it is shown next, if it still exists. */
    fun close(index: Int, returnToOpener: Boolean = false) {
        val tab = tabs.getOrNull(index) ?: return
        val state = tab.webView?.let { wv -> Bundle().also { wv.saveState(it) } } ?: tab.savedState
        if (tab.url.isNotEmpty()) closed.addFirst(ClosedTab(tab.url, tab.title, state))
        while (closed.size > MAX_CLOSED) closed.removeLast()
        if (tab.webView != null) destroyWebView(tab)
        tab.webView = null
        tabs.removeAt(index)
        val opener = if (returnToOpener) tabs.indexOfFirst { it.id == tab.openerId } else -1
        when {
            tabs.isEmpty() -> { currentIndex = -1; newTab(Prefs.HOME_URL); return }
            opener >= 0 -> currentIndex = opener
            index < currentIndex -> currentIndex--
            index == currentIndex -> currentIndex = index.coerceAtMost(tabs.lastIndex) // the right neighbour slides into place
        }
        select(currentIndex)
    }

    /** A tab for a page-opened window (popup): the WebView is created now and filled by the page. */
    fun newTabForWindow(opener: Tab?): Tab {
        val tab = Tab(nextId++, "")
        tab.openerId = opener?.id
        tabs.add((currentIndex + 1).coerceAtMost(tabs.size), tab)
        tab.webView = createWebView(tab)
        select(tabs.indexOf(tab))
        return tab
    }

    fun indexOf(webView: WebView) = tabs.indexOfFirst { it.webView === webView }

    fun exists(id: Long?) = id != null && tabs.any { it.id == id }

    /** Each tab's back/forward history as a Bundle (null for a tab with none yet), in tab order. */
    fun historyStates(): List<Bundle?> = tabs.map { t -> t.webView?.let { wv -> Bundle().also { wv.saveState(it) } } ?: t.savedState }

    fun undoClose(position: Int = 0): Tab? {
        if (position !in closed.indices) return null
        val c = closed.removeAt(position)
        return newTab(c.url, state = c.state).also { it.title = c.title; onChanged() }
    }

    fun move(step: Int) {
        if (tabs.size < 2) return
        select(((currentIndex + step) % tabs.size + tabs.size) % tabs.size)
    }

    /** Destroys WebViews of the least recently used tabs beyond the threshold, keeping their state. */
    fun enforceCache() {
        val live = tabs.filter { it.webView != null && it !== current }.sortedByDescending { it.lastUsed }
        val allowed = (cacheThreshold - 1).coerceAtLeast(1)
        live.drop(allowed).forEach { tab ->
            val wv = tab.webView ?: return@forEach
            tab.savedState = Bundle().also { wv.saveState(it) }
            destroyWebView(tab)
            tab.webView = null
        }
    }

    fun toJson(): String = JSONObject().apply {
        put("current", currentIndex)
        put("tabs", JSONArray().apply { tabs.forEach { put(JSONObject().put("url", it.url).put("title", it.title)) } })
    }.toString()

    companion object {
        const val MAX_CLOSED = 25

        /** Parses [toJson] output into (url, title) pairs and the selected index; empty on bad input. */
        fun parseSaved(json: String): Pair<List<Pair<String, String>>, Int> = try {
            val o = JSONObject(json)
            val arr = o.getJSONArray("tabs")
            val list = (0 until arr.length()).map { arr.getJSONObject(it).let { t -> t.getString("url") to t.optString("title") } }
            list to o.optInt("current", 0).coerceIn(0, (list.size - 1).coerceAtLeast(0))
        } catch (e: Exception) {
            emptyList<Pair<String, String>>() to 0
        }
    }
}
