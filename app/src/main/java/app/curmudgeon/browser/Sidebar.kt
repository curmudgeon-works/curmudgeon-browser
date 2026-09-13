// SPDX-License-Identifier: GPL-3.0-only
package app.curmudgeon.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import java.text.DateFormat
import java.util.Date

/**
 * The links sidebar: bookmarks (collapsible groups), history (by day) and popular (most visited).
 * Opens from the left; list on the left, section rail on the right like Naked Browser.
 */
class Sidebar(
    private val root: View,
    private val db: BrowserDb,
    private val host: Host,
) {
    interface Host {
        fun openUrl(url: String, newTab: Boolean, keepSidebar: Boolean)
        fun currentPage(): Pair<String, String>? // title, url
        fun closeSidebar()
    }

    enum class Section { BOOKMARKS, HISTORY, POPULAR }

    private val context: Context = root.context
    private val list = root.findViewById<ListView>(R.id.sbList)
    private val newBookmark = root.findViewById<Button>(R.id.sbNewBookmark)
    private val clearHistory = root.findViewById<Button>(R.id.sbClear)
    private val rails = mapOf(
        Section.BOOKMARKS to root.findViewById<TextView>(R.id.sbBookmarks),
        Section.HISTORY to root.findViewById(R.id.sbHistory),
        Section.POPULAR to root.findViewById(R.id.sbPopular),
    )
    private var section = Section.BOOKMARKS
    private val collapsedGroups = mutableSetOf<String>()

    private sealed class Row {
        data class Header(val text: String, val group: String?) : Row()
        data class Link(val title: String, val url: String, val bookmark: Bookmark? = null, val subtitle: String = url) : Row()
    }

    private var rows: List<Row> = emptyList()
    private val adapter = object : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(context).inflate(R.layout.list_row, parent, false)
            val title = v.findViewById<TextView>(R.id.rowTitle)
            val sub = v.findViewById<TextView>(R.id.rowSubtitle)
            val icon = v.findViewById<ImageView>(R.id.rowIcon)
            when (val r = rows[position]) {
                is Row.Header -> {
                    title.text = r.text
                    title.setTypeface(null, android.graphics.Typeface.BOLD)
                    sub.visibility = View.GONE
                    icon.visibility = if (r.group != null) View.VISIBLE else View.GONE
                    icon.setImageResource(R.drawable.ic_folder)
                    v.setBackgroundColor(ContextCompat.getColor(context, R.color.sidebar_rail))
                }
                is Row.Link -> {
                    title.text = r.title.ifBlank { r.url }
                    title.setTypeface(null, android.graphics.Typeface.NORMAL)
                    sub.text = r.subtitle
                    sub.visibility = if (r.subtitle.isEmpty()) View.GONE else View.VISIBLE
                    icon.visibility = View.GONE
                    v.setBackgroundColor(0)
                }
            }
            return v
        }
    }

    init {
        list.adapter = adapter
        rails.forEach { (s, v) -> v.setOnClickListener { show(s) } }
        root.findViewById<View>(R.id.sbDone).setOnClickListener { host.closeSidebar() }
        newBookmark.setOnClickListener { editBookmark(null) }
        clearHistory.setOnClickListener {
            AlertDialog.Builder(context).setMessage("Clear all history?")
                .setPositiveButton("Clear") { _, _ -> db.clearHistory(); refresh() }
                .setNegativeButton("Cancel", null).show()
        }
        list.setOnItemClickListener { _, _, position, _ ->
            when (val r = rows[position]) {
                is Row.Header -> r.group?.let { g -> if (!collapsedGroups.remove(g)) collapsedGroups.add(g); refresh() }
                is Row.Link -> {
                    val behavior = Prefs(context).bookmarkOpenBehavior
                    host.openUrl(r.url, newTab = behavior != 0, keepSidebar = behavior == 1)
                }
            }
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            (rows[position] as? Row.Link)?.let { linkMenu(it) }
            true
        }
    }

    fun show(s: Section = section) {
        section = s
        rails.forEach { (sec, v) ->
            v.setTextColor(ContextCompat.getColor(context, if (sec == s) R.color.accent else R.color.tab_text))
        }
        newBookmark.visibility = if (s == Section.BOOKMARKS) View.VISIBLE else View.GONE
        clearHistory.visibility = if (s == Section.HISTORY) View.VISIBLE else View.GONE
        refresh()
    }

    fun refresh() {
        rows = when (section) {
            Section.BOOKMARKS -> db.bookmarks().groupBy { it.group }.flatMap { (group, items) ->
                val header = if (group.isEmpty()) emptyList() else listOf(Row.Header(if (group in collapsedGroups) "▸ $group" else "▾ $group", group))
                header + if (group in collapsedGroups) emptyList() else items.map { Row.Link(it.title, it.url, it) }
            }.ifEmpty { listOf(Row.Header("No bookmarks yet. Tap “new bookmark” to add this page.", null)) }
            Section.HISTORY -> {
                val out = mutableListOf<Row>()
                var lastDay = ""
                db.recentHistory(500).forEach { h ->
                    val day = dayLabel(h.lastVisit)
                    if (day != lastDay) { out.add(Row.Header(day, null)); lastDay = day }
                    out.add(Row.Link(h.title, h.url))
                }
                out.ifEmpty { listOf(Row.Header("No history.", null)) }
            }
            Section.POPULAR -> db.popular(100).map { Row.Link(it.title, it.url, subtitle = "${it.visits} visits · ${UrlUtils.host(it.url)}") }
                .ifEmpty { listOf(Row.Header("Nothing visited yet.", null)) }
        }
        adapter.notifyDataSetChanged()
    }

    private fun dayLabel(time: Long): String = when {
        DateUtils.isToday(time) -> "today"
        DateUtils.isToday(time + DateUtils.DAY_IN_MILLIS) -> "yesterday"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(time))
    }

    private fun linkMenu(r: Row.Link) {
        val items = mutableListOf("Open in new tab", "Copy address", "Share")
        if (r.bookmark != null) items += listOf("Edit bookmark", "Delete bookmark")
        else items += listOf("Bookmark", "Remove from history")
        AlertDialog.Builder(context).setTitle(r.title.ifBlank { r.url })
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    "Open in new tab" -> host.openUrl(r.url, newTab = true, keepSidebar = true)
                    "Copy address" -> copy(r.url)
                    "Share" -> context.startActivity(android.content.Intent.createChooser(
                        android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, r.url), null))
                    "Edit bookmark" -> editBookmark(r.bookmark)
                    "Delete bookmark" -> { db.deleteBookmark(r.bookmark!!.id); refresh() }
                    "Bookmark" -> editBookmark(Bookmark(0, "", r.title, r.url))
                    "Remove from history" -> { db.deleteHistory(r.url); refresh() }
                }
            }.show()
    }

    private fun copy(text: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("address", text))
        Toast.makeText(context, "Address copied", Toast.LENGTH_SHORT).show()
    }

    /** Add (id 0 or null) or edit a bookmark. New bookmarks default to the current page. */
    fun editBookmark(existing: Bookmark?) {
        val page = host.currentPage()
        val b = existing ?: Bookmark(0, "", page?.first ?: "", page?.second ?: "")
        val pad = (16 * context.resources.displayMetrics.density).toInt()
        val form = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        val title = EditText(context).apply { hint = "title"; setText(b.title); setSingleLine() }
        val url = EditText(context).apply { hint = "address"; setText(b.url); setSingleLine() }
        val group = AutoCompleteTextView(context).apply {
            hint = "group (optional)"; setText(b.group); setSingleLine(); threshold = 0
            setAdapter(ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, db.bookmarkGroups().filter { it.isNotEmpty() }))
            setOnFocusChangeListener { v, f -> if (f) (v as AutoCompleteTextView).showDropDown() }
        }
        form.addView(title); form.addView(url); form.addView(group)
        AlertDialog.Builder(context).setTitle(if (b.id == 0L) "New bookmark" else "Edit bookmark").setView(form)
            .setPositiveButton("Save") { _, _ ->
                val u = url.text.toString().trim()
                if (u.isEmpty()) return@setPositiveButton
                if (b.id == 0L) db.addBookmark(group.text.toString().trim(), title.text.toString().trim(), u)
                else db.updateBookmark(b.copy(group = group.text.toString().trim(), title = title.text.toString().trim(), url = u))
                refresh()
            }
            .setNegativeButton("Cancel", null).show()
    }

}
