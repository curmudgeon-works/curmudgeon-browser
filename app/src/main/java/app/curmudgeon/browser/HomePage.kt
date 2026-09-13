// SPDX-License-Identifier: GPL-3.0-only
package app.curmudgeon.browser

import android.text.Html

/**
 * The built-in about:home page: search box, bookmarks and recent history, in the browser's greys.
 * It is loaded with [BASE_URL] (a reserved .invalid host that can never resolve) as its origin.
 */
object HomePage {
    const val BASE_URL = "https://home.curmudgeon.invalid/"

    private fun esc(s: String) = Html.escapeHtml(s)

    fun build(prefs: Prefs, db: BrowserDb): String {
        val search = if (!prefs.homeSearchBox) "" else """
            <form class="search" onsubmit="var q=document.getElementById('q').value.trim(); if(q) location.href='${prefs.searchUrl.replace("'", "\\'")}'+encodeURIComponent(q); return false;">
              <input id="q" type="search" autocomplete="off" autocapitalize="off" placeholder="search the web">
              <button type="submit">web</button>
            </form>""".trimIndent()

        val bookmarks = if (!prefs.homeBookmarks) "" else db.bookmarks().let { list ->
            if (list.isEmpty()) "" else buildString {
                append("<h2>bookmarks</h2>")
                list.groupBy { it.group }.forEach { (group, items) ->
                    if (group.isNotEmpty()) append("<h3>${esc(group)}</h3>")
                    append("<ul>")
                    items.forEach { append("<li><a href=\"${esc(it.url)}\">${esc(it.title.ifBlank { it.url })}</a></li>") }
                    append("</ul>")
                }
            }
        }

        val history = if (!prefs.homeHistory) "" else db.recentHistory(60).distinctBy { UrlUtils.host(it.url) }.take(25).let { list ->
            if (list.isEmpty()) "" else buildString {
                append("<h2>recent history</h2><ul class=\"hosts\">")
                list.forEach { append("<li><a href=\"${esc(it.url)}\">${esc(UrlUtils.host(it.url).ifEmpty { it.url })} →</a></li>") }
                append("</ul>")
            }
        }

        val custom = prefs.homeHtml
        if (custom.isNotBlank()) return custom.replace("{{search}}", search).replace("{{bookmarks}}", bookmarks).replace("{{history}}", history)

        return """
            <!doctype html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <meta name="color-scheme" content="dark">
            <title>home</title>
            <style>
              body { background:#383838; color:#eee; font-family:sans-serif; margin:0; padding:12px 14px 80px; }
              .search { display:flex; gap:8px; margin:6px 0 18px; }
              .search input { flex:1; font-size:18px; padding:9px; border:0; border-radius:2px; background:#fff; color:#000; }
              .search button { font-size:18px; font-weight:bold; padding:0 14px; border:0; background:transparent; color:#fff; }
              h2 { color:#bbb; font-size:20px; margin:22px 0 6px; }
              h3 { color:#999; font-size:15px; margin:12px 0 2px; }
              ul { list-style:none; padding:0; margin:0; }
              li a { display:block; color:#fff; font-weight:bold; font-size:18px; text-decoration:none; padding:6px 0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
            </style></head><body>
            $search
            $bookmarks
            $history
            </body></html>
        """.trimIndent()
    }
}
