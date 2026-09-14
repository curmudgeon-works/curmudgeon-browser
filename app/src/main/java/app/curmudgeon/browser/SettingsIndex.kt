// SPDX-License-Identifier: GPL-3.0-only
package app.curmudgeon.browser

import android.content.Context
import android.content.res.XmlResourceParser
import org.xmlpull.v1.XmlPullParser

/** Every setting's key, title and summary, read straight from the settings XML files, for search. */
object SettingsIndex {
    data class Entry(val key: String, val title: String, val summary: String, val category: String?, val sectionXml: Int, val sectionTitle: String) {
        fun matches(q: String) = title.contains(q, ignoreCase = true) || summary.contains(q, ignoreCase = true) || sectionTitle.contains(q, ignoreCase = true)
    }

    private const val ANDROID = "http://schemas.android.com/apk/res/android"
    private val simpleCache = mutableMapOf<Int, Boolean>()

    fun build(context: Context): List<Entry> {
        val out = mutableListOf<Entry>()
        read(context, R.xml.prefs_root, SettingsActivity.HOME_TITLE, out, sectionsOnly = true)
        SettingsActivity.SECTIONS.values.forEach { (xml, title) -> read(context, xml, title, out, sectionsOnly = false) }
        return out
    }

    /** Whether any setting on [xml] is shown in simple mode (otherwise its home row is hidden in simple mode). */
    fun hasSimple(context: Context, xml: Int): Boolean = simpleCache.getOrPut(xml) {
        val entries = mutableListOf<Entry>()
        read(context, xml, "", entries, sectionsOnly = false)
        entries.any { it.key in SettingsActivity.SIMPLE_KEYS }
    }

    private fun read(context: Context, xml: Int, sectionTitle: String, out: MutableList<Entry>, sectionsOnly: Boolean) {
        val parser = context.resources.getXml(xml)
        var category: String? = null
        try {
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG) continue
                val title = attr(context, parser, "title") ?: continue
                if (parser.name == "PreferenceCategory") { category = title; continue }
                val key = parser.getAttributeValue(ANDROID, "key") ?: continue
                if (sectionsOnly && key.startsWith("section_")) continue // home rows lead to sections; search the sections instead
                out.add(Entry(key, title, attr(context, parser, "summary")?.replace("%s", "")?.trim().orEmpty(), category, xml, sectionTitle))
            }
        } finally {
            parser.close()
        }
    }

    private fun attr(context: Context, parser: XmlResourceParser, name: String): String? {
        val res = parser.getAttributeResourceValue(ANDROID, name, 0)
        return if (res != 0) context.getString(res) else parser.getAttributeValue(ANDROID, name)
    }
}
