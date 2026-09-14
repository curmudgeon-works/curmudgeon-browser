// SPDX-License-Identifier: GPL-3.0-only
package app.curmudgeon.browser

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.SwitchPreferenceCompat

/**
 * Settings, structured like Curmudgeon Keyboard and Rotation: a home list of sections, one screen per section,
 * and a top bar with back, title, the Simple / Advanced switch and search (search always covers every setting).
 */
class SettingsActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var title: TextView
    private lateinit var searchField: EditText
    private lateinit var searchResults: ListView
    private lateinit var container: View
    private val index by lazy { SettingsIndex.build(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = Prefs(this)
        val root = findViewById<View>(R.id.settingsRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            v.updatePadding(top = bars.top, bottom = bars.bottom, left = bars.left, right = bars.right)
            insets
        }
        title = findViewById(R.id.title)
        searchField = findViewById(R.id.searchField)
        searchResults = findViewById(R.id.searchResults)
        container = findViewById(R.id.settingsContainer)

        findViewById<View>(R.id.backButton).setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        val switch = findViewById<SwitchCompat>(R.id.advancedSwitch)
        switch.isChecked = prefs.advancedSettings
        switch.setOnCheckedChangeListener { _, checked ->
            prefs.set(Prefs.ADVANCED_SETTINGS, checked)
            (supportFragmentManager.findFragmentById(R.id.settingsContainer) as? ScreenFragment)?.applyMode()
        }
        findViewById<View>(R.id.searchButton).setOnClickListener { if (searchField.isVisible) closeSearch() else openSearch() }
        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = showResults(s.toString())
        })
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    searchField.isVisible -> closeSearch()
                    supportFragmentManager.backStackEntryCount > 0 -> supportFragmentManager.popBackStack()
                    else -> finish()
                }
            }
        })

        if (savedInstanceState == null) show(R.xml.prefs_root, HOME_TITLE, addToBackStack = false)
        supportFragmentManager.addOnBackStackChangedListener { updateTitle() }
        updateTitle()
    }

    fun show(xml: Int, title: String, addToBackStack: Boolean = true, focusKey: String? = null) {
        val tx = supportFragmentManager.beginTransaction()
            .replace(R.id.settingsContainer, ScreenFragment.newInstance(xml, title, focusKey))
        if (addToBackStack) tx.addToBackStack(title)
        tx.commit()
        this.title.text = title
    }

    private fun updateTitle() {
        val f = supportFragmentManager.findFragmentById(R.id.settingsContainer) as? ScreenFragment
        title.text = f?.arguments?.getString(ARG_TITLE) ?: HOME_TITLE
    }

    // --- search ---

    private fun openSearch() {
        title.isVisible = false
        searchField.isVisible = true
        searchField.setText("")
        searchField.requestFocus()
        WindowInsetsControllerCompat(window, searchField).show(WindowInsetsCompat.Type.ime())
    }

    private fun closeSearch() {
        WindowInsetsControllerCompat(window, searchField).hide(WindowInsetsCompat.Type.ime())
        searchField.isVisible = false
        title.isVisible = true
        searchResults.isVisible = false
        container.isVisible = true
    }

    private fun showResults(query: String) {
        val q = query.trim()
        if (q.isEmpty()) { searchResults.isVisible = false; container.isVisible = true; return }
        val hits = index.filter { it.matches(q) }
        searchResults.adapter = object : ArrayAdapter<SettingsIndex.Entry>(this, R.layout.list_row, R.id.rowTitle, hits) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val e = hits[position]
                v.findViewById<TextView>(R.id.rowTitle).text = e.title
                v.findViewById<TextView>(R.id.rowSubtitle).text = listOfNotNull(e.sectionTitle, e.category).joinToString(" › ")
                return v
            }
        }
        searchResults.setOnItemClickListener { _, _, position, _ ->
            val e = hits[position]
            closeSearch()
            supportFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
            if (e.sectionXml == R.xml.prefs_root) show(R.xml.prefs_root, HOME_TITLE, addToBackStack = false, focusKey = e.key)
            else show(e.sectionXml, e.sectionTitle, focusKey = e.key)
        }
        container.isVisible = false
        searchResults.isVisible = true
    }

    /** One settings screen. Hides advanced items in simple mode; search-style lookups are not filtered. */
    class ScreenFragment : PreferenceFragmentCompat() {
        private val prefs by lazy { Prefs(requireContext()) }
        private val db by lazy { BrowserDb.get(requireContext()) }

        private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { importBookmarks(it) } }
        private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> uri?.let { exportBookmarks(it) } }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(requireArguments().getInt(ARG_XML), rootKey)
            setUpGestureLists(preferenceScreen)
            SECTIONS.keys.forEach { key -> findPreference<Preference>(key)?.widgetLayoutResource = R.layout.pref_chevron }
            findPreference<ListPreference>(Prefs.SEARCH_URL)?.let { setUpSearchEngines(it) }
            findPreference<Preference>("about")?.summary = "Version ${BuildConfig.VERSION_NAME} · GPL-3.0 · no ads, no accounts, no analytics"
            findPreference<SwitchPreferenceCompat>(Prefs.BLOCK_ADS)?.setOnPreferenceChangeListener { _, on ->
                if (on == true && !AdBlocker.hasList(requireContext())) updateBlockList()
                true
            }
            findPreference<SwitchPreferenceCompat>(Prefs.SAVE_HISTORY)?.setOnPreferenceChangeListener { _, on ->
                if (on == false) DataCleaner.clearHistory(requireContext()); true
            }
            findPreference<SwitchPreferenceCompat>(Prefs.SAVE_COOKIES)?.setOnPreferenceChangeListener { _, on ->
                if (on == false) android.webkit.CookieManager.getInstance().removeAllCookies(null); true
            }
            updateBlockListSummary()
            applyMode()
        }

        /**
         * Shows everything in advanced mode; in simple mode only [SIMPLE_KEYS] and categories that still have children.
         * Home rows hide in simple mode when their whole section is advanced. Opened from search: everything shows.
         */
        fun applyMode() {
            val advanced = prefs.advancedSettings || requireArguments().getString(ARG_FOCUS) != null
            fun apply(group: PreferenceGroup): Boolean {
                var anyVisible = false
                for (i in 0 until group.preferenceCount) {
                    val p = group.getPreference(i)
                    p.isIconSpaceReserved = false
                    val section = SECTIONS[p.key]
                    val visible = when {
                        p is PreferenceGroup -> apply(p)
                        section != null -> advanced || SettingsIndex.hasSimple(requireContext(), section.first)
                        else -> advanced || p.key in SIMPLE_KEYS
                    }
                    p.isVisible = visible
                    anyVisible = anyVisible || visible
                }
                return anyVisible
            }
            apply(preferenceScreen)
        }

        private fun setUpGestureLists(group: PreferenceGroup) {
            for (i in 0 until group.preferenceCount) {
                val p = group.getPreference(i)
                if (p is PreferenceGroup) setUpGestureLists(p)
                else if (p is ListPreference && p.key in GESTURE_KEYS) {
                    p.entries = GestureAction.labels
                    p.entryValues = GestureAction.names
                    if (p.value == null) p.value = (Prefs.GESTURE_DEFAULTS[p.key] ?: GestureAction.NONE).name
                }
            }
        }

        private fun setUpSearchEngines(list: ListPreference) {
            val engines = UrlUtils.parseSearchEngines(prefs.searchEngines)
            val current = prefs.searchUrl
            val all = if (engines.any { it.second == current }) engines else engines + ("custom" to current)
            list.entries = all.map { it.first }.toTypedArray()
            list.entryValues = all.map { it.second }.toTypedArray()
            if (list.value == null) list.value = current
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            requireArguments().getString(ARG_FOCUS)?.let { key -> view.post { scrollToPreference(key) } }
        }

        override fun onPreferenceTreeClick(preference: Preference): Boolean {
            val activity = activity as? SettingsActivity ?: return super.onPreferenceTreeClick(preference)
            SECTIONS[preference.key]?.let { (xml, title) -> activity.show(xml, title); return true }
            when (preference.key) {
                "about" -> showAbout()
                "clear_history_now" -> confirm("Clear all history?") { DataCleaner.clearHistory(requireContext()); toast("History cleared") }
                "clear_cache_now" -> confirm("Clear cache and site storage?") { DataCleaner.clearCache(requireContext(), null); toast("Cache cleared") }
                "clear_cookies_now" -> confirm("Clear cookies? Kept sites are not cleared.") {
                    DataCleaner.clearCookies(requireContext()) { activity.runOnUiThread { toast("Cookies cleared") } }
                }
                "sites_javascript" -> editSiteList(BrowserDb.SiteList.JAVASCRIPT, "Sites with JavaScript")
                "sites_images" -> editSiteList(BrowserDb.SiteList.IMAGES, "Sites with images")
                "sites_cookies" -> editSiteList(BrowserDb.SiteList.COOKIES_KEEP, "Sites whose cookies are kept")
                "sites_geo" -> {
                    editSiteList(BrowserDb.SiteList.GEO_ALLOW, "Sites allowed location")
                }
                "import_bookmarks" -> importLauncher.launch(arrayOf("text/*"))
                "export_bookmarks" -> exportLauncher.launch("bookmarks.txt")
                "delete_bookmarks" -> confirm("Delete all bookmarks?") { db.deleteAllBookmarks(); toast("Bookmarks deleted") }
                "update_block_list" -> updateBlockList()
                else -> return super.onPreferenceTreeClick(preference)
            }
            return true
        }

        private fun confirm(message: String, action: () -> Unit) {
            AlertDialog.Builder(requireContext()).setMessage(message)
                .setPositiveButton("Yes") { _, _ -> action() }
                .setNegativeButton("No", null).show()
        }

        private fun toast(text: String) = Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()

        /** Lists the hosts in a site list; checked ones are kept, unchecked ones removed on OK. */
        private fun editSiteList(list: BrowserDb.SiteList, title: String) {
            val hosts = db.sites(list).toList()
            if (hosts.isEmpty()) {
                AlertDialog.Builder(requireContext()).setTitle(title).setMessage("No sites yet.").setPositiveButton("OK", null).show()
                return
            }
            val checked = BooleanArray(hosts.size) { true }
            AlertDialog.Builder(requireContext()).setTitle(title)
                .setMultiChoiceItems(hosts.toTypedArray(), checked) { _, i, c -> checked[i] = c }
                .setPositiveButton("Save") { _, _ -> hosts.forEachIndexed { i, h -> if (!checked[i]) db.setSite(list, h, false) } }
                .setNegativeButton("Cancel", null).show()
        }

        private fun importBookmarks(uri: Uri) {
            val text = runCatching { requireContext().contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
            if (text == null) { toast("Could not read the file"); return }
            toast("Imported ${db.importBookmarks(text)} bookmarks")
        }

        private fun exportBookmarks(uri: Uri) {
            val ok = runCatching {
                requireContext().contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(db.exportBookmarks()) }
            }.isSuccess
            toast(if (ok) "Bookmarks exported" else "Could not write the file")
        }

        private fun updateBlockList() {
            val ctx = requireContext().applicationContext
            findPreference<Preference>("update_block_list")?.summary = "Downloading…"
            AdBlocker.update(ctx, prefs.blockListUrl) { result ->
                activity?.runOnUiThread {
                    result.onSuccess { toast("Block list updated: $it hosts") }
                        .onFailure { toast("Block list update failed: ${it.message}") }
                    updateBlockListSummary()
                }
            }
        }

        private fun updateBlockListSummary() {
            val p = findPreference<Preference>("update_block_list") ?: return
            p.summary = when {
                AdBlocker.loaded -> "${AdBlocker.size} hosts loaded"
                AdBlocker.hasList(requireContext()) -> "List downloaded; loads when blocking is on"
                else -> "No list downloaded yet"
            }
        }

        private fun showAbout() {
            AlertDialog.Builder(requireContext())
                .setTitle("Curmudgeon Browser ${BuildConfig.VERSION_NAME}")
                .setMessage(
                    "A small, fast browser that does what you tell it.\n\n" +
                        "Free software under the GNU GPL v3. No ads, no accounts, no analytics, no crash reporting. " +
                        "The browser only connects to the sites you open, the block list address when you update it, " +
                        "and Google's suggestion service if you turn suggestions on.\n\n" +
                        "Pages are rendered by Android System WebView.\n\ncurmudgeon.works"
                )
                .setPositiveButton("OK", null)
                .setNeutralButton("Website") { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://curmudgeon.works"))) }
                .show()
        }

        companion object {
            fun newInstance(xml: Int, title: String, focusKey: String? = null) = ScreenFragment().apply {
                arguments = Bundle().apply { putInt(ARG_XML, xml); putString(ARG_TITLE, title); putString(ARG_FOCUS, focusKey) }
            }
        }
    }

    companion object {
        const val ARG_XML = "xml"
        const val ARG_TITLE = "title"
        const val ARG_FOCUS = "focus"
        const val HOME_TITLE = "Curmudgeon Browser Settings"

        /** Home row key -> (screen XML, screen title). */
        val SECTIONS = linkedMapOf(
            "section_content" to (R.xml.prefs_content to "Pages and zoom"),
            "section_tabs" to (R.xml.prefs_tabs to "Tabs"),
            "section_gestures" to (R.xml.prefs_gestures to "Gestures and actions"),
            "section_search" to (R.xml.prefs_search to "Search"),
            "section_homepage" to (R.xml.prefs_homepage to "Home page"),
            "section_privacy" to (R.xml.prefs_privacy to "Privacy and blocking"),
            "section_data" to (R.xml.prefs_data to "Data"),
            "section_etc" to (R.xml.prefs_etc to "Downloads, popups and more"),
            "section_more" to (R.xml.prefs_more to "More configuration"),
        )

        val GESTURE_KEYS = setOf(
            Prefs.TWO_FINGER_UP, Prefs.TWO_FINGER_DOWN, Prefs.TWO_FINGER_LEFT, Prefs.TWO_FINGER_RIGHT,
            Prefs.DOUBLE_TAP_UP, Prefs.DOUBLE_TAP_DOWN, Prefs.DOUBLE_TAP_LEFT, Prefs.DOUBLE_TAP_RIGHT,
            Prefs.BOTTOM_SWIPE_LEFT, Prefs.BOTTOM_SWIPE_RIGHT,
            Prefs.BOTTOM_LEFT_CORNER_ACROSS, Prefs.BOTTOM_RIGHT_CORNER_ACROSS,
            Prefs.BOTTOM_LEFT_CORNER_UP, Prefs.BOTTOM_RIGHT_CORNER_UP,
            Prefs.BOTTOM_LEFT_CORNER_TAP, Prefs.BOTTOM_RIGHT_CORNER_TAP,
        )

        /** Shown in simple mode. Everything else appears only with the Advanced switch on. */
        val SIMPLE_KEYS = setOf(
            "about",
            Prefs.JAVASCRIPT, Prefs.IMAGE_POLICY, Prefs.TEXT_ZOOM, Prefs.FORCE_DARK_PAGES,
            Prefs.SEARCH_URL, Prefs.SEARCH_SUGGESTIONS,
            Prefs.TAB_CLOSE_METHOD, Prefs.TAB_ROWS, Prefs.REMEMBER_TABS, Prefs.BOTTOM_TABS_AND_URL,
            Prefs.HOME_PAGE, Prefs.KEEP_SCREEN_ON,
            // Data options
            "clear_history_now", "clear_cache_now", "clear_cookies_now",
            Prefs.CLEAR_HISTORY_ON_EXIT, Prefs.CLEAR_COOKIES_ON_EXIT, Prefs.SAVE_HISTORY, Prefs.THIRD_PARTY_COOKIES,
            "import_bookmarks", "export_bookmarks",
            // Gestures
            Prefs.VOLUME_ACTION, Prefs.LINK_LONG_PRESS, Prefs.TOP_MENU_GESTURE, Prefs.BOTTOM_MENU_GESTURE, Prefs.SIDE_MENU_GESTURE,
            Prefs.DOUBLE_TAP_ZOOM, Prefs.TWO_FINGER_LEFT, Prefs.TWO_FINGER_RIGHT,
            // Privacy
            Prefs.BLOCK_ADS, "update_block_list", Prefs.DO_NOT_TRACK, Prefs.SAFE_BROWSING,
        )
    }
}
