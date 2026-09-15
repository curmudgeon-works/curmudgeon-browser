// SPDX-License-Identifier: GPL-3.0-only
package app.curmudgeon.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlUtilsTest {
    private val search = "https://duckduckgo.com/?q="

    @Test fun typedTextBecomesUrlOrSearch() {
        assertEquals("https://example.com", UrlUtils.toUrl("example.com", search))
        assertEquals("https://example.com/a?b=c", UrlUtils.toUrl("  example.com/a?b=c ", search))
        assertEquals("http://x.org", UrlUtils.toUrl("http://x.org", search))
        assertEquals("https://192.168.1.1:8080", UrlUtils.toUrl("192.168.1.1:8080", search))
        assertEquals("https://localhost:3000/x", UrlUtils.toUrl("localhost:3000/x", search))
        assertEquals(search + "hello+world", UrlUtils.toUrl("hello world", search))
        assertEquals(search + "kotlin", UrlUtils.toUrl("kotlin", search))
        assertEquals(search + "what+is+a.b", UrlUtils.toUrl("what is a.b", search))
        assertEquals("mailto:a@b.c", UrlUtils.toUrl("mailto:a@b.c", search))
        assertEquals(Prefs.HOME_URL, UrlUtils.toUrl("", search))
        assertEquals(Prefs.HOME_URL, UrlUtils.toUrl("about:home", search))
    }

    @Test fun hostExtraction() {
        assertEquals("example.com", UrlUtils.host("https://www.example.com/path?q=1"))
        assertEquals("a.b.example.com", UrlUtils.host("http://user:pw@a.b.Example.com:8443/x"))
        assertEquals("", UrlUtils.host("about:home"))
        assertEquals("", UrlUtils.host(null))
    }

    @Test fun parentDomainMatching() {
        assertEquals(listOf("ads.tracker.example.com", "tracker.example.com", "example.com"), UrlUtils.hostAndParents("ads.tracker.example.com"))
        val set = setOf("tracker.example.com")
        assertTrue(UrlUtils.matchesAny("ads.tracker.example.com", set))
        assertTrue(UrlUtils.matchesAny("tracker.example.com", set))
        assertFalse(UrlUtils.matchesAny("example.com", set))
        assertFalse(UrlUtils.matchesAny("nottracker.example.com", set))
    }

    @Test fun hostsFileLines() {
        assertEquals("ads.example.com", UrlUtils.parseHostsLine("0.0.0.0 ads.example.com # comment"))
        assertEquals("ads.example.com", UrlUtils.parseHostsLine("127.0.0.1\tADS.example.com"))
        assertEquals("plain.example.org", UrlUtils.parseHostsLine("plain.example.org"))
        assertNull(UrlUtils.parseHostsLine("# just a comment"))
        assertNull(UrlUtils.parseHostsLine("127.0.0.1 localhost"))
        assertNull(UrlUtils.parseHostsLine("0.0.0.0 0.0.0.0"))
        assertNull(UrlUtils.parseHostsLine(""))
    }

    @Test fun searchEngineList() {
        val list = UrlUtils.parseSearchEngines("DDG|https://duckduckgo.com/?q=\nbroken line\nX|ftp://nope\n Wiki | https://en.wikipedia.org/w/index.php?search= ")
        assertEquals(listOf("DDG" to "https://duckduckgo.com/?q=", "Wiki" to "https://en.wikipedia.org/w/index.php?search="), list)
    }

    @Test fun desktopUserAgent() {
        val mobile = "Mozilla/5.0 (Linux; Android 17; Pixel 11 Pro XL Build/CD1A; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/140.0.0.0 Mobile Safari/537.36"
        val desktop = UrlUtils.desktopUserAgent(mobile)
        assertFalse(desktop.contains("Android"))
        assertFalse(desktop.contains("Mobile"))
        assertTrue(desktop.contains("X11; Linux x86_64"))
    }

    @Test fun browserUserAgentDropsWebViewMarkers() {
        val webView = "Mozilla/5.0 (Linux; Android 17; Pixel 11 Pro XL Build/CD1A; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/151.0.7922.199 Mobile Safari/537.36"
        assertEquals(
            "Mozilla/5.0 (Linux; Android 17; Pixel 11 Pro XL Build/CD1A) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.7922.199 Mobile Safari/537.36",
            UrlUtils.browserUserAgent(webView),
        )
        assertEquals("Mozilla/5.0 (X11; Linux x86_64) Chrome/1", UrlUtils.browserUserAgent("Mozilla/5.0 (X11; Linux x86_64) Chrome/1"))
    }

    @Test fun sameSite() {
        assertTrue(UrlUtils.sameSite("https://www.google.com/maps", "https://google.com/x"))
        assertTrue(UrlUtils.sameSite("https://maps.google.com/", "https://google.com/"))
        assertFalse(UrlUtils.sameSite("https://news.site/a", "https://maps.google.com/b"))
        assertFalse(UrlUtils.sameSite(null, "https://a.com"))
    }

    @Test fun tabLabels() {
        assertEquals("Title", UrlUtils.tabLabel(" Title ", "https://x.com"))
        assertEquals("x.com", UrlUtils.tabLabel("", "https://www.x.com/a"))
        assertEquals("home", UrlUtils.tabLabel(null, Prefs.HOME_URL))
    }
}
