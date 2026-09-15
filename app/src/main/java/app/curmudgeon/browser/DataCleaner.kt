// SPDX-License-Identifier: GPL-3.0-only
package app.curmudgeon.browser

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase

/** Clearing history, cache and cookies, shared by the settings screen and "clear on exit". */
object DataCleaner {
    fun clearHistory(context: Context) {
        BrowserDb.get(context).clearHistory()
        WebViewDatabase.getInstance(context).clearFormData()
    }

    /** Clears the HTTP cache (a WebView instance is needed for that) and all site storage. */
    fun clearCache(context: Context, anyWebView: WebView?) {
        val wv = anyWebView ?: WebView(context.applicationContext)
        wv.clearCache(true)
        if (anyWebView == null) wv.destroy()
        WebStorage.getInstance().deleteAllData()
    }

    /**
     * Removes all cookies except those of sites the user chose to keep. WebView has no per-host delete,
     * so kept cookies are read first and written back afterwards (as session-scoped name=value pairs).
     */
    fun clearCookies(context: Context, done: () -> Unit = {}) {
        val cm = CookieManager.getInstance()
        val keep = BrowserDb.get(context).sites(BrowserDb.SiteList.COOKIES_KEEP)
        val saved = keep.associateWith { host -> cm.getCookie("https://$host") }.filterValues { !it.isNullOrEmpty() }
        cm.removeAllCookies {
            saved.forEach { (host, cookies) ->
                cookies!!.split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { pair ->
                    // __Host- cookies must not carry a Domain; both prefixes require Secure
                    val attrs = if (pair.startsWith("__Host-")) "Path=/; Secure" else "Domain=$host; Path=/; Secure"
                    cm.setCookie("https://$host", "$pair; $attrs")
                }
            }
            cm.flush()
            done()
        }
    }

    fun clearOnExit(context: Context, anyWebView: WebView?) {
        val p = Prefs(context)
        if (p.clearHistoryOnExit) clearHistory(context)
        if (p.clearCacheOnExit) clearCache(context, anyWebView)
        if (p.clearCookiesOnExit) clearCookies(context)
    }
}
