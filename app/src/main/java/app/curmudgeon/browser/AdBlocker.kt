// SPDX-License-Identifier: GPL-3.0-only
package app.curmudgeon.browser

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Host-list blocker. The list is downloaded only when the user turns blocking on or taps "update",
 * stored in app storage, and loaded into memory on a background thread.
 */
object AdBlocker {
    @Volatile private var hosts: Set<String> = emptySet()
    @Volatile var loaded = false
        private set

    private fun file(context: Context) = File(context.filesDir, "blocklist.txt")

    fun hasList(context: Context) = file(context).length() > 0

    /** Loads the stored list in the background if blocking is on and it is not loaded yet. */
    fun ensureLoaded(context: Context) {
        if (loaded || !Prefs(context).blockAds) return
        val f = file(context)
        if (!f.exists()) return
        thread(name = "blocklist-load") { load(f) }
    }

    private fun load(f: File) {
        val set = HashSet<String>(200_000)
        f.forEachLine { line -> UrlUtils.parseHostsLine(line)?.let { set.add(it) } }
        hosts = set
        loaded = true
    }

    fun isBlocked(host: String): Boolean = loaded && host.isNotEmpty() && UrlUtils.matchesAny(host, hosts)

    val size get() = hosts.size

    /** Downloads the list from [url]; calls [done] on a background thread with the host count or an error message. */
    fun update(context: Context, url: String, done: (Result<Int>) -> Unit) {
        val target = file(context)
        thread(name = "blocklist-update") {
            val result = runCatching {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = true
                try {
                    if (conn.responseCode !in 200..299) error("server answered ${conn.responseCode}")
                    val tmp = File(target.parentFile, "blocklist.tmp")
                    conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
                } finally {
                    conn.disconnect()
                }
                load(target)
                hosts.size
            }
            done(result)
        }
    }
}
