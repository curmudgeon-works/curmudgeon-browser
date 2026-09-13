// SPDX-License-Identifier: GPL-3.0-only
package app.curmudgeon.browser

import java.net.URLEncoder

/** Pure URL helpers (no Android dependencies, unit-tested). */
object UrlUtils {
    private val schemeRegex = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
    private val hostLike = Regex("^(localhost|[\\w-]+(\\.[\\w-]+)+|\\d{1,3}(\\.\\d{1,3}){3}|\\[[0-9a-fA-F:]+])(:\\d+)?(/.*)?$")

    /** Turns what the user typed into a URL: a URL as-is, a host with https added, anything else a search. */
    fun toUrl(input: String, searchUrl: String): String {
        val text = input.trim()
        if (text.isEmpty()) return Prefs.HOME_URL
        if (text.equals(Prefs.HOME_URL, ignoreCase = true)) return Prefs.HOME_URL
        if (!text.contains(' ')) {
            if (schemeRegex.containsMatchIn(text) && (text.contains("://") || text.startsWith("about:") ||
                    text.startsWith("mailto:") || text.startsWith("tel:") || text.startsWith("data:") || text.startsWith("javascript:")))
                return text
            if (hostLike.matches(text)) return "https://$text"
        }
        return searchUrl + URLEncoder.encode(text, "UTF-8")
    }

    /** Host of a URL without the "www." prefix, or "" when there is none. */
    fun host(url: String?): String {
        if (url.isNullOrEmpty()) return ""
        val afterScheme = url.substringAfter("://", "")
        if (afterScheme.isEmpty()) return ""
        val hostPort = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#').substringAfter('@')
        val host = if (hostPort.startsWith("[")) hostPort.substringBefore(']') + "]" else hostPort.substringBefore(':')
        return host.lowercase().removePrefix("www.")
    }

    /** The host and each parent domain, most specific first: a.b.example.com, b.example.com, example.com. */
    fun hostAndParents(host: String): List<String> {
        if (host.isEmpty()) return emptyList()
        val parts = host.split('.')
        if (parts.size < 2) return listOf(host)
        return (0 until parts.size - 1).map { parts.subList(it, parts.size).joinToString(".") }
    }

    /** True if [host] or one of its parent domains is in [set]. */
    fun matchesAny(host: String, set: Set<String>): Boolean = hostAndParents(host).any { it in set }

    /** Short label for a tab: page title, else host, else the URL. */
    fun tabLabel(title: String?, url: String?): String = when {
        !title.isNullOrBlank() -> title.trim()
        url == Prefs.HOME_URL || url.isNullOrEmpty() -> "home"
        else -> host(url).ifEmpty { url }
    }

    /** Parses lines of "hosts" format ("0.0.0.0 ads.example.com") or plain host lines into host names. */
    fun parseHostsLine(line: String): String? {
        val content = line.substringBefore('#').trim()
        if (content.isEmpty()) return null
        val parts = content.split(Regex("\\s+"))
        val host = when {
            parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1" || parts[0] == "::1") -> parts[1]
            parts.size == 1 -> parts[0]
            else -> return null
        }.lowercase()
        if (host == "localhost" || host == "0.0.0.0" || host.startsWith("localhost.") || host == "broadcasthost" || !host.contains('.')) return null
        return host
    }

    /** "Name|https://…?q=" lines into (name, prefix) pairs, skipping malformed lines. */
    fun parseSearchEngines(text: String): List<Pair<String, String>> = text.lines().mapNotNull { line ->
        val name = line.substringBefore('|', "").trim()
        val url = line.substringAfter('|', "").trim()
        if (name.isEmpty() || !(url.startsWith("http://") || url.startsWith("https://"))) null else name to url
    }

    /** Desktop user agent derived from the WebView default: drops "Android …;" and "Mobile". */
    fun desktopUserAgent(defaultUa: String): String = defaultUa
        .replace(Regex("\\(Linux; Android [^;)]*;[^)]*\\)"), "(X11; Linux x86_64)")
        .replace(" Mobile", "")
        .replace(Regex("\\s*wv\\)"), ")")
}
