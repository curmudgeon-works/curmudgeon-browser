// SPDX-License-Identifier: GPL-3.0-only
package app.curmudgeon.browser

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ExecutorService

/**
 * Address bar suggestions: matching bookmarks and history, plus Google search suggestions when enabled.
 * Tap opens; long press a search suggestion to pick the engine.
 */
class Suggestions(
    private val activity: Activity,
    private val list: ListView,
    private val db: BrowserDb,
    private val prefs: Prefs,
    private val io: ExecutorService,
    private val onPick: (String) -> Unit,
    private val onPickWithEngine: (String) -> Unit,
) {
    private data class Item(val title: String, val url: String?, val query: String?)

    private var items: List<Item> = emptyList()
    @Volatile private var generation = 0

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(activity).inflate(R.layout.list_row, parent, false)
            val item = items[position]
            v.findViewById<TextView>(R.id.rowTitle).text = item.title
            v.findViewById<TextView>(R.id.rowSubtitle).apply {
                text = item.url ?: ""
                visibility = if (item.url == null) View.GONE else View.VISIBLE
            }
            v.findViewById<ImageView>(R.id.rowIcon).apply {
                visibility = View.VISIBLE
                setImageResource(if (item.query != null) R.drawable.ic_find else R.drawable.ic_history)
            }
            return v
        }
    }

    init {
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val item = items[position]
            onPick(item.url ?: UrlUtils.toUrl(item.query!!, prefs.searchUrl))
            hide()
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            val item = items[position]
            item.query?.let { onPickWithEngine(it); hide() }
            true
        }
    }

    fun update(text: String) {
        val gen = ++generation
        val query = text.trim()
        if (query.isEmpty()) { hide(); return }
        val withRemote = prefs.searchSuggestions
        val bookmarksFirst = prefs.suggestBookmarksFirst
        io.execute {
            if (gen != generation) return@execute
            val local = db.suggest(query, 6, bookmarksFirst).map { Item(it.first.ifBlank { it.second }, it.second, null) }
            post(gen, local)
            if (withRemote) {
                val remote = fetchRemote(query).take(6).map { Item(it, null, it) }
                if (remote.isNotEmpty()) post(gen, local + remote)
            }
        }
    }

    private fun post(gen: Int, result: List<Item>) = activity.runOnUiThread {
        if (gen != generation) return@runOnUiThread
        items = result
        adapter.notifyDataSetChanged()
        list.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun fetchRemote(query: String): List<String> = runCatching {
        val conn = URL("https://suggestqueries.google.com/complete/search?client=firefox&q=" + URLEncoder.encode(query, "UTF-8"))
            .openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        try {
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONArray(body).getJSONArray(1)
            (0 until arr.length()).map { arr.getString(it) }
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(emptyList())

    fun hide() {
        generation++
        items = emptyList()
        adapter.notifyDataSetChanged()
        list.visibility = View.GONE
    }
}
