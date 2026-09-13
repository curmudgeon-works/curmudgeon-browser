// SPDX-License-Identifier: GPL-3.0-only
package app.curmudgeon.browser

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Bookmark(val id: Long, val group: String, val title: String, val url: String)
data class HistoryEntry(val url: String, val title: String, val visits: Int, val lastVisit: Long)

/**
 * Bookmarks, history and per-site rules in one small SQLite file (no ORM, keeps the APK small).
 * Per-site rules are host lists: sites allowed JavaScript, images, geolocation, or whose cookies survive clearing.
 */
class BrowserDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "browser.db", null, 1) {

    enum class SiteList { JAVASCRIPT, IMAGES, COOKIES_KEEP, GEO_ALLOW, GEO_DENY }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE bookmarks (id INTEGER PRIMARY KEY AUTOINCREMENT, grp TEXT NOT NULL DEFAULT '', title TEXT NOT NULL, url TEXT NOT NULL, created INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE history (url TEXT PRIMARY KEY, title TEXT NOT NULL DEFAULT '', visits INTEGER NOT NULL DEFAULT 1, last_visit INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX history_last ON history(last_visit)")
        db.execSQL("CREATE TABLE site_rules (list TEXT NOT NULL, host TEXT NOT NULL, PRIMARY KEY(list, host))")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // --- bookmarks ---
    fun bookmarks(): List<Bookmark> = readableDatabase.rawQuery(
        "SELECT id, grp, title, url FROM bookmarks ORDER BY grp COLLATE NOCASE, created", null
    ).use { c -> generateSequence { if (c.moveToNext()) Bookmark(c.getLong(0), c.getString(1), c.getString(2), c.getString(3)) else null }.toList() }

    fun bookmarkGroups(): List<String> = bookmarks().map { it.group }.distinct()

    fun addBookmark(group: String, title: String, url: String): Long = writableDatabase.insert("bookmarks", null, ContentValues().apply {
        put("grp", group); put("title", title); put("url", url); put("created", System.currentTimeMillis())
    })

    fun updateBookmark(b: Bookmark) {
        writableDatabase.update("bookmarks", ContentValues().apply {
            put("grp", b.group); put("title", b.title); put("url", b.url)
        }, "id = ?", arrayOf(b.id.toString()))
    }

    fun deleteBookmark(id: Long) { writableDatabase.delete("bookmarks", "id = ?", arrayOf(id.toString())) }
    fun deleteAllBookmarks() { writableDatabase.delete("bookmarks", null, null) }
    fun isBookmarked(url: String) = readableDatabase.rawQuery("SELECT 1 FROM bookmarks WHERE url = ? LIMIT 1", arrayOf(url)).use { it.moveToFirst() }

    /** Tab-separated lines: group, URL, title (Naked Browser's bookmarks.txt format). */
    fun exportBookmarks(): String = bookmarks().joinToString("\n") { "${it.group}\t${it.url}\t${it.title}" }

    fun importBookmarks(text: String): Int {
        var n = 0
        writableDatabase.beginTransaction()
        try {
            text.lines().forEach { line ->
                val parts = line.split('\t')
                val (group, url, title) = when (parts.size) {
                    0, 1 -> if (parts.firstOrNull()?.startsWith("http") == true) Triple("", parts[0], parts[0]) else return@forEach
                    2 -> Triple(parts[0], parts[1], parts[1])
                    else -> Triple(parts[0], parts[1], parts.drop(2).joinToString("\t"))
                }
                if (!url.startsWith("http") && !url.startsWith("javascript:")) return@forEach
                if (!isBookmarked(url)) { addBookmark(group.trim(), title.trim(), url.trim()); n++ }
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
        return n
    }

    // --- history ---
    fun recordVisit(url: String, title: String) {
        if (url == Prefs.HOME_URL || url.startsWith("data:") || url.startsWith("javascript:")) return
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val updated = db.compileStatement("UPDATE history SET visits = visits + 1, last_visit = ?, title = CASE WHEN ? = '' THEN title ELSE ? END WHERE url = ?").apply {
            bindLong(1, now); bindString(2, title); bindString(3, title); bindString(4, url)
        }.executeUpdateDelete()
        if (updated == 0) db.insert("history", null, ContentValues().apply {
            put("url", url); put("title", title); put("visits", 1); put("last_visit", now)
        })
    }

    fun updateTitle(url: String, title: String) {
        if (title.isBlank()) return
        writableDatabase.update("history", ContentValues().apply { put("title", title) }, "url = ?", arrayOf(url))
    }

    private fun historyQuery(sql: String, args: Array<String>?) = readableDatabase.rawQuery(sql, args).use { c ->
        generateSequence { if (c.moveToNext()) HistoryEntry(c.getString(0), c.getString(1), c.getInt(2), c.getLong(3)) else null }.toList()
    }

    fun recentHistory(limit: Int) = historyQuery("SELECT url, title, visits, last_visit FROM history ORDER BY last_visit DESC LIMIT $limit", null)
    fun popular(limit: Int) = historyQuery("SELECT url, title, visits, last_visit FROM history ORDER BY visits DESC, last_visit DESC LIMIT $limit", null)
    fun deleteHistory(url: String) { writableDatabase.delete("history", "url = ?", arrayOf(url)) }
    fun clearHistory() { writableDatabase.delete("history", null, null) }

    /** Bookmarks and history whose URL or title contains [text], for URL bar suggestions. */
    fun suggest(text: String, limit: Int, bookmarksFirst: Boolean): List<Pair<String, String>> {
        if (text.isBlank()) return emptyList()
        val like = "%${text.replace("%", "").replace("_", "")}%"
        val marks = readableDatabase.rawQuery(
            "SELECT title, url FROM bookmarks WHERE url LIKE ? OR title LIKE ? LIMIT $limit", arrayOf(like, like)
        ).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) to c.getString(1) else null }.toList() }
        val hist = historyQuery(
            "SELECT url, title, visits, last_visit FROM history WHERE url LIKE ? OR title LIKE ? ORDER BY visits DESC, last_visit DESC LIMIT $limit",
            arrayOf(like, like)
        ).map { it.title to it.url }
        val combined = if (bookmarksFirst) marks + hist else hist + marks
        return combined.distinctBy { it.second }.take(limit)
    }

    // --- per-site rules ---
    fun sites(list: SiteList): Set<String> = readableDatabase.rawQuery(
        "SELECT host FROM site_rules WHERE list = ? ORDER BY host", arrayOf(list.name)
    ).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toCollection(LinkedHashSet()) }

    fun setSite(list: SiteList, host: String, present: Boolean) {
        if (host.isEmpty()) return
        if (present) writableDatabase.insertWithOnConflict("site_rules", null, ContentValues().apply {
            put("list", list.name); put("host", host)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        else writableDatabase.delete("site_rules", "list = ? AND host = ?", arrayOf(list.name, host))
    }

    fun inList(list: SiteList, host: String) = UrlUtils.matchesAny(host, sites(list))

    companion object {
        @Volatile private var instance: BrowserDb? = null
        fun get(context: Context) = instance ?: synchronized(this) { instance ?: BrowserDb(context).also { instance = it } }
    }
}
