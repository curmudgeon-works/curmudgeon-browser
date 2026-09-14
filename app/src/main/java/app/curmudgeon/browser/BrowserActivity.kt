// SPDX-License-Identifier: GPL-3.0-only
package app.curmudgeon.browser

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.provider.MediaStore
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.ActionMode
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs

@SuppressLint("SetJavaScriptEnabled")
class BrowserActivity : AppCompatActivity(), GestureFrame.Listener, Sidebar.Host {

    private lateinit var prefs: Prefs
    private lateinit var db: BrowserDb
    private lateinit var tabs: TabManager
    private lateinit var tabBar: TabBar
    private lateinit var sidebar: Sidebar
    private lateinit var suggestions: Suggestions

    private lateinit var root: FrameLayout
    private lateinit var column: LinearLayout
    private lateinit var topMenu: View
    private lateinit var contentArea: FrameLayout
    private lateinit var webFrame: GestureFrame
    private lateinit var progress: ProgressBar
    private lateinit var urlField: EditText
    private lateinit var btGo: ImageButton
    private lateinit var bottomMenu: View
    private lateinit var moreRow: HorizontalScrollView
    private lateinit var findBar: View
    private lateinit var findText: EditText
    private lateinit var findCount: TextView
    private lateinit var sidebarView: View
    private lateinit var sidebarScrim: View
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var btUserAgent: TextView

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val density by lazy { resources.displayMetrics.density }

    private var defaultUserAgent = ""
    private var blockAds = false
    private var haltHistory = false
    private var selectTextOnce = false
    private var isFullscreen = false
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var scrollAccumulator = 0
    private val sslAllowedHosts = mutableSetOf<String>()
    private val lastMenuDrag = LongArray(3)
    private var siteJs: Set<String> = emptySet()
    private var siteImages: Set<String> = emptySet()

    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingGeo: Pair<String, GeolocationPermissions.Callback>? = null
    private var pendingPermission: PermissionRequest? = null
    private var pendingDownload: (() -> Unit)? = null

    private val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pendingFileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        pendingFileCallback = null
    }
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        pendingGeo?.let { (origin, cb) -> cb.invoke(origin, granted.values.any { it }, false) }
        pendingGeo = null
        pendingPermission?.let { req ->
            val allowed = req.resources.filter { r ->
                (r == PermissionRequest.RESOURCE_VIDEO_CAPTURE && granted[Manifest.permission.CAMERA] == true) ||
                    (r == PermissionRequest.RESOURCE_AUDIO_CAPTURE && granted[Manifest.permission.RECORD_AUDIO] == true)
            }
            if (allowed.isEmpty()) req.deny() else req.grant(allowed.toTypedArray())
        }
        pendingPermission = null
        pendingDownload?.let { if (granted.values.all { it }) it() }
        pendingDownload = null
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_browser)
        prefs = Prefs(this)
        db = BrowserDb.get(this)
        bindViews()

        tabs = TabManager(::createWebView, ::destroyWebView, ::loadTab)
        tabs.onChanged = ::onTabsChanged
        tabBar = TabBar(findViewById(R.id.tabScroll), findViewById(R.id.tabStrip), prefs,
            onSelect = { tabs.select(it) }, onClose = { closeTab(it) }, onTabMenu = ::tabMenu)
        tabBar.currentIndexProvider = { tabs.currentIndex }
        sidebar = Sidebar(sidebarView, db, this)
        suggestions = Suggestions(this, findViewById(R.id.suggestions), db, prefs, io,
            onPick = { url -> navigate(url); urlField.clearFocus(); hideIme() },
            onPickWithEngine = ::searchWithEnginePicker)

        setUpInsets()
        setUpTopMenu()
        setUpBottomMenu()
        setUpFindBar()
        webFrame.listener = this
        sidebarScrim.setOnClickListener { closeSidebar() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onBack()
        })

        readRuntimePrefs()
        applyLayoutPrefs()
        restoreTabs()
        if (!handleIntent(intent) && tabs.tabs.isEmpty()) tabs.newTab(prefs.homePage)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        readRuntimePrefs()
        applyLayoutPrefs()
        tabs.tabs.forEach { t -> t.webView?.let { applySettings(t, it) } }
        tabs.current?.webView?.onResume()
        onTabsChanged()
    }

    override fun onPause() {
        super.onPause()
        if (prefs.rememberTabs) prefs.savedTabs = tabs.toJson()
        tabs.current?.webView?.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        if (isFinishing) tabs.tabs.forEach { t -> t.webView?.let { destroyWebView(t) } }
        io.shutdown()
        super.onDestroy()
    }

    private fun bindViews() {
        root = findViewById(R.id.root)
        column = findViewById(R.id.column)
        topMenu = findViewById(R.id.topMenu)
        contentArea = findViewById(R.id.contentArea)
        webFrame = findViewById(R.id.webFrame)
        progress = findViewById(R.id.progress)
        urlField = findViewById(R.id.urlField)
        btGo = findViewById(R.id.btGo)
        bottomMenu = findViewById(R.id.bottomMenu)
        moreRow = findViewById(R.id.moreRow)
        findBar = findViewById(R.id.findBar)
        findText = findViewById(R.id.findText)
        findCount = findViewById(R.id.findCount)
        sidebarView = findViewById(R.id.sidebar)
        sidebarScrim = findViewById(R.id.sidebarScrim)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
        btUserAgent = findViewById(R.id.btUserAgent)
    }

    /** Values read often (per request or per navigation) are cached here and refreshed on resume. */
    private fun readRuntimePrefs() {
        blockAds = prefs.blockAds
        if (blockAds) AdBlocker.ensureLoaded(this)
        siteJs = db.sites(BrowserDb.SiteList.JAVASCRIPT)
        siteImages = db.sites(BrowserDb.SiteList.IMAGES)
        tabs.cacheThreshold = prefs.tabCacheThreshold
        CookieManager.getInstance().setAcceptCookie(prefs.saveCookies)
        if (prefs.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        urlField.inputType = when (prefs.urlBarKeyboard) {
            1 -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            2 -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        tintToggle(R.id.btNightMode, prefs.nightMode)
    }

    /** Tabs and address at the top (default) or the bottom of the screen. */
    private fun applyLayoutPrefs() {
        val bottom = prefs.bottomTabsAndUrl
        val index = column.indexOfChild(topMenu)
        val wanted = if (bottom) column.childCount - 1 else 0
        if (index != wanted) {
            column.removeView(topMenu)
            column.addView(topMenu, if (bottom) column.childCount else 0)
        }
        (findViewById<ListView>(R.id.suggestions).layoutParams as FrameLayout.LayoutParams).gravity = if (bottom) Gravity.BOTTOM else Gravity.TOP
        (progress.layoutParams as FrameLayout.LayoutParams).gravity = if (bottom) Gravity.BOTTOM else Gravity.TOP
    }

    private fun setUpInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            column.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            // with gesture navigation the home-swipe zone reaches above the navigation bar
            val gestures = insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures())
            webFrame.bottomReserved = if (ime.bottom > 0) 0 else (gestures.bottom - bars.bottom).coerceAtLeast(0) + (8 * density).toInt()
            sidebarView.setPadding(bars.left, bars.top, 0, bars.bottom)
            insets
        }
    }

    // ---------------------------------------------------------------- tabs

    private fun restoreTabs() {
        if (!prefs.rememberTabs) return
        val (saved, current) = TabManager.parseSaved(prefs.savedTabs)
        if (saved.isEmpty()) return
        saved.forEach { (url, title) -> tabs.newTab(url, select = false, afterCurrent = false).title = title }
        tabs.select(current)
    }

    private fun createWebView(tab: Tab): WebView {
        // WebView reports prefers-color-scheme from its context theme, so give it a light theme unless dark pages are on
        val themed = if (prefs.forceDarkPages) this else ContextThemeWrapper(this, R.style.Theme_Curmudgeon_WebLight)
        val wv = WebView(themed)
        wv.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        wv.setBackgroundColor(ContextCompat.getColor(this, R.color.page_bg))
        if (defaultUserAgent.isEmpty()) defaultUserAgent = wv.settings.userAgentString
        applySettings(tab, wv)
        wv.webViewClient = PageClient(tab)
        wv.webChromeClient = ChromeClient(tab)
        wv.setDownloadListener { url, ua, disposition, mime, _ -> download(url, ua, disposition, mime) }
        wv.setFindListener { active, count, done -> if (done) findCount.text = if (count == 0) "0/0" else "${active + 1}/$count" }
        wv.setOnScrollChangeListener { _, _, y, _, oldY -> if (tab === tabs.current) onPageScroll(y, oldY) }
        wv.setOnLongClickListener { onPageLongPress(wv) }
        return wv
    }

    private fun destroyWebView(tab: Tab) {
        val wv = tab.webView ?: return
        (wv.parent as? ViewGroup)?.removeView(wv)
        wv.stopLoading()
        wv.destroy()
    }

    /** Settings that follow global preferences; per-site ones are set in [applyPerSite] before each navigation. */
    private fun applySettings(tab: Tab, wv: WebView) {
        val s = wv.settings
        s.domStorageEnabled = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = prefs.zoomButtons
        s.useWideViewPort = prefs.wideViewport || tab.desktopMode
        s.loadWithOverviewMode = prefs.openInOverview || tab.desktopMode
        s.textZoom = prefs.textZoom
        s.layoutAlgorithm = if (prefs.textAutoSize) WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING else WebSettings.LayoutAlgorithm.NORMAL
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = prefs.allowPopups
        s.mediaPlaybackRequiresUserGesture = true
        s.allowFileAccess = false
        s.safeBrowsingEnabled = prefs.safeBrowsing
        s.setGeolocationEnabled(prefs.geolocation)
        s.userAgentString = if (tab.desktopMode) prefs.desktopUserAgent.ifBlank { UrlUtils.desktopUserAgent(defaultUserAgent) } else defaultUserAgent
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING))
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, prefs.forceDarkPages)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, prefs.thirdPartyCookies)
        applyPerSite(tab, wv, tab.url)
    }

    private fun applyPerSite(tab: Tab, wv: WebView, url: String) {
        val s = wv.settings
        val host = UrlUtils.host(url)
        val home = url == Prefs.HOME_URL || url.startsWith(HomePage.BASE_URL)
        s.javaScriptEnabled = home || prefs.javascript || tab.tempJavaScript || UrlUtils.matchesAny(host, siteJs)
        val images = home || tab.tempImages || UrlUtils.matchesAny(host, siteImages) || when (prefs.imagePolicy) {
            0 -> true
            1 -> onWifi()
            else -> false
        }
        s.loadsImagesAutomatically = images
        s.blockNetworkImage = !images
    }

    private fun onWifi(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun loadTab(tab: Tab) = navigate(tab.url, tab)

    /** Loads [url] (or what the user typed, already converted) in [tab], the current tab by default. */
    fun navigate(url: String, tab: Tab? = tabs.current) {
        val t = tab ?: return
        val wv = t.webView ?: run { t.url = url; return }
        if (url == Prefs.HOME_URL) {
            t.url = Prefs.HOME_URL
            t.title = ""
            applyPerSite(t, wv, HomePage.BASE_URL)
            wv.loadDataWithBaseURL(HomePage.BASE_URL, HomePage.build(prefs, db), "text/html", "utf-8", HomePage.BASE_URL)
            if (t === tabs.current) updateUrlField()
            return
        }
        if (url.startsWith("javascript:", ignoreCase = true)) {
            // bookmarklets run on the current page and leave the address alone
            wv.evaluateJavascript(Uri.decode(url.substring("javascript:".length)), null)
            return
        }
        if (openExternally(url)) return
        t.url = url
        applyPerSite(t, wv, url)
        wv.loadUrl(url, extraHeaders())
        if (t === tabs.current) updateUrlField()
    }

    private fun extraHeaders(): Map<String, String> = if (prefs.doNotTrack) mapOf("DNT" to "1", "Sec-GPC" to "1") else emptyMap()

    private fun onTabsChanged() {
        val tab = tabs.current ?: return
        val wv = tab.webView
        for (i in webFrame.childCount - 1 downTo 0) {
            val child = webFrame.getChildAt(i)
            if (child !== wv) webFrame.removeViewAt(i)
        }
        if (wv != null && wv.parent !== webFrame) {
            (wv.parent as? ViewGroup)?.removeView(wv)
            webFrame.addView(wv)
        }
        webFrame.webView = wv
        tabBar.render(tabs.tabs, tabs.currentIndex)
        updateUrlField()
        updateProgress(tab)
        btUserAgent.text = if (tab.desktopMode) "desktop" else "android"
    }

    private fun newTabHome() {
        tabs.newTab(prefs.homePage)
        if (prefs.keyboardOnNewTab) focusUrl()
    }

    private fun closeTab(index: Int) {
        tabs.close(index)
    }

    private fun tabMenu(index: Int) {
        val tab = tabs.tabs.getOrNull(index) ?: return
        val items = arrayOf("Close tab", "Close other tabs", "Duplicate tab", "Copy address", "Reload")
        AlertDialog.Builder(this).setTitle(tab.label).setItems(items) { _, which ->
            when (which) {
                0 -> closeTab(index)
                1 -> { tabs.select(index); (tabs.tabs.size - 1 downTo 0).filter { it != tabs.currentIndex }.forEach { tabs.close(it) } }
                2 -> tabs.newTab(tab.url)
                3 -> copyText(tab.url)
                4 -> tab.webView?.reload()
            }
        }.show()
    }

    private fun closedTabsMenu() {
        if (tabs.closed.isEmpty()) { toast("No closed tabs"); return }
        val labels = tabs.closed.map { UrlUtils.tabLabel(it.title, it.url) }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Reopen closed tab").setItems(labels) { _, which -> tabs.undoClose(which) }.show()
    }

    // ---------------------------------------------------------------- top menu, address bar, suggestions

    private fun setUpTopMenu() {
        findViewById<View>(R.id.btNewTab).setOnClickListener { newTabHome() }
        findViewById<View>(R.id.btNewTab).setOnLongClickListener { closedTabsMenu(); true }
        findViewById<View>(R.id.btHome).setOnClickListener { navigate(prefs.homePage) }
        findViewById<View>(R.id.btHome).setOnLongClickListener { newTabHome(); true }
        btGo.setOnClickListener {
            val tab = tabs.current
            when {
                urlField.hasFocus() -> go()
                tab != null && tab.progress < 100 -> tab.webView?.stopLoading()
                else -> tab?.webView?.reload()
            }
        }
        btGo.setOnLongClickListener { searchWithEnginePicker(urlField.text.toString()); true }
        urlField.setOnEditorActionListener { _, id, event ->
            if (id == EditorInfo.IME_ACTION_GO || (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) { go(); true } else false
        }
        urlField.setOnFocusChangeListener { _, focused ->
            if (focused) {
                btGo.setImageResource(R.drawable.ic_go)
                tintUrlField(tabs.current)
                showTopMenu()
            } else {
                suggestions.hide()
                updateUrlField()
            }
        }
        urlField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { if (urlField.hasFocus()) suggestions.update(s.toString()) }
        })
        // Long press keeps normal editing (cursor, select, paste at the cursor); "Paste and go" is added alongside.
        val pasteAndGo = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                if (clipboardText() != null) menu.add(Menu.NONE, MENU_PASTE_GO, 100, "Paste and go")
                return true
            }
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                if (item.itemId != MENU_PASTE_GO) return false
                clipboardText()?.let { navigate(UrlUtils.toUrl(it, prefs.searchUrl)); urlField.clearFocus(); hideIme() }
                mode.finish()
                return true
            }
            override fun onDestroyActionMode(mode: ActionMode) {}
        }
        urlField.customSelectionActionModeCallback = pasteAndGo
        urlField.customInsertionActionModeCallback = pasteAndGo
    }

    private fun clipboardText(): String? =
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    private fun go() {
        val text = urlField.text.toString()
        navigate(UrlUtils.toUrl(text, prefs.searchUrl))
        urlField.clearFocus()
        hideIme()
    }

    private fun searchWithEnginePicker(query: String) {
        val engines = UrlUtils.parseSearchEngines(prefs.searchEngines)
        if (engines.isEmpty()) return
        AlertDialog.Builder(this).setTitle(if (query.isBlank()) "Search engine" else "Search “${query.trim()}” with")
            .setItems(engines.map { it.first }.toTypedArray()) { _, which ->
                val (_, prefix) = engines[which]
                if (query.isBlank()) { prefs.sp.edit().putString(Prefs.SEARCH_URL, prefix).apply(); toast("Default search: ${engines[which].first}") }
                else { navigate(prefix + java.net.URLEncoder.encode(query.trim(), "UTF-8")); urlField.clearFocus(); hideIme() }
            }.show()
    }

    private fun updateUrlField() {
        if (urlField.hasFocus()) return
        val tab = tabs.current ?: return
        val shown = if (tab.url == Prefs.HOME_URL) "" else tab.url
        if (urlField.text.toString() != shown) urlField.setText(shown)
        btGo.setImageResource(if (tab.progress < 100) R.drawable.ic_close else R.drawable.ic_reload)
        tintUrlField(tab)
    }

    /** Like Naked Browser, the address bar shows whether the page is secure: tinted for https, grey for http. */
    private fun tintUrlField(tab: Tab?) {
        val color = when {
            urlField.hasFocus() || tab == null || tab.url == Prefs.HOME_URL || !tab.url.startsWith("http") -> R.color.url_bg
            tab.url.startsWith("https://") && UrlUtils.host(tab.url) !in sslAllowedHosts -> R.color.url_secure_bg
            else -> R.color.url_insecure_bg
        }
        urlField.setBackgroundColor(ContextCompat.getColor(this, color))
    }

    private fun updateProgress(tab: Tab) {
        if (tab !== tabs.current) return
        progress.isVisible = tab.progress < 100
        progress.progress = tab.progress
        if (!urlField.hasFocus()) btGo.setImageResource(if (tab.progress < 100) R.drawable.ic_close else R.drawable.ic_reload)
        findViewById<ImageButton>(R.id.btReload).setImageResource(if (tab.progress < 100) R.drawable.ic_close else R.drawable.ic_reload)
    }

    private fun focusUrl() {
        showTopMenu()
        urlField.requestFocus()
        urlField.setSelection(urlField.text.length)
        WindowInsetsControllerCompat(window, urlField).show(WindowInsetsCompat.Type.ime())
    }

    private fun hideIme() = WindowInsetsControllerCompat(window, urlField).hide(WindowInsetsCompat.Type.ime())

    // ---------------------------------------------------------------- menus

    private fun showTopMenu() { if (customView == null) topMenu.isVisible = true }
    private fun hideTopMenu() { if (!urlField.hasFocus()) topMenu.isVisible = false }
    private fun showBottomMenu() { if (customView == null) { bottomMenu.isVisible = true; findBar.isVisible = false } }
    private fun hideBottomMenu() { bottomMenu.isVisible = false }
    private fun showMenus() { showTopMenu(); showBottomMenu() }

    private fun onPageScroll(y: Int, oldY: Int) {
        if (!prefs.hideMenusOnScroll) return
        val dy = y - oldY
        scrollAccumulator = if ((dy > 0) == (scrollAccumulator > 0)) scrollAccumulator + dy else dy
        if (scrollAccumulator > 24 * density && y > 48 * density) {
            if (topMenu.isVisible) hideTopMenu()
            if (bottomMenu.isVisible) hideBottomMenu()
        }
    }

    private fun setUpBottomMenu() {
        fun click(id: Int, action: () -> Unit) = findViewById<View>(id).setOnClickListener { action() }
        fun long(id: Int, action: () -> Unit) = findViewById<View>(id).setOnLongClickListener { action(); true }
        click(R.id.btReload) { perform(GestureAction.STOP_RELOAD) }
        long(R.id.btReload) { reloadWithoutCache() }
        click(R.id.btUserAgent) { perform(GestureAction.TOGGLE_DESKTOP_MODE) }
        click(R.id.btPageTop) { perform(GestureAction.PAGE_TOP) }
        click(R.id.btFullscreen) { perform(GestureAction.TOGGLE_FULLSCREEN) }
        click(R.id.btMore) {
            moreRow.isVisible = !moreRow.isVisible
            findViewById<ImageButton>(R.id.btMore).setImageResource(if (moreRow.isVisible) R.drawable.ic_more_down else R.drawable.ic_more_up)
        }
        click(R.id.btLinks) { hideBottomMenu(); openSidebar() }
        click(R.id.btExit) { exitBrowser() }
        click(R.id.btPageEnd) { perform(GestureAction.PAGE_END) }
        click(R.id.btForward) { perform(GestureAction.FORWARD) }
        click(R.id.btOptions) { hideBottomMenu(); startActivity(Intent(this, SettingsActivity::class.java)) }
        click(R.id.btFind) { perform(GestureAction.FIND_ON_PAGE) }
        click(R.id.btDownloads) { openDownloads() }
        long(R.id.btDownloads) {
            val on = !prefs.systemDownloader
            prefs.set(Prefs.SYSTEM_DOWNLOADER, on)
            toast(if (on) "System downloader on" else "System downloader off: downloads go to another app")
        }
        click(R.id.btShare) { perform(GestureAction.SHARE_URL) }
        click(R.id.btSavePage) { savePage() }
        click(R.id.btNightMode) { perform(GestureAction.TOGGLE_NIGHT_MODE) }
        click(R.id.btHaltHistory) {
            haltHistory = !haltHistory
            tintToggle(R.id.btHaltHistory, haltHistory)
            toast(if (haltHistory) "History paused" else "History resumed")
        }
        click(R.id.btShowKeyboard) { hideBottomMenu(); focusUrl() }
        click(R.id.btBookmarkPage) { sidebar.editBookmark(null) }
        click(R.id.btCopyUrl) { perform(GestureAction.COPY_URL) }
        click(R.id.btSelectText) { selectTextOnce = true; toast("Long press the page to select text") }
        click(R.id.btJsReload) { tabs.current?.let { it.tempJavaScript = true; it.webView?.let { wv -> applyPerSite(it, wv, it.url); wv.reload() } } }
        long(R.id.btJsReload) { toggleSiteList(BrowserDb.SiteList.JAVASCRIPT, "Always allow JavaScript on") }
        click(R.id.btImages) { tabs.current?.let { it.tempImages = true; it.webView?.let { wv -> applyPerSite(it, wv, it.url); wv.reload() } } }
        long(R.id.btImages) { toggleSiteList(BrowserDb.SiteList.IMAGES, "Always load images on") }
        click(R.id.btCookie) { toggleSiteList(BrowserDb.SiteList.COOKIES_KEEP, "Keep cookies when clearing, for") }
        click(R.id.btGeo) { geoSiteMenu() }
        click(R.id.btBack) { perform(GestureAction.BACK) }
    }

    private fun tintToggle(id: Int, on: Boolean) {
        findViewById<ImageButton>(id)?.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, if (on) R.color.accent else R.color.menu_icon))
    }

    private fun toggleSiteList(list: BrowserDb.SiteList, question: String) {
        val host = UrlUtils.host(tabs.current?.url)
        if (host.isEmpty()) { toast("No site open"); return }
        val present = host in db.sites(list)
        AlertDialog.Builder(this).setTitle(host)
            .setMultiChoiceItems(arrayOf("$question $host"), booleanArrayOf(present)) { _, _, checked ->
                db.setSite(list, host, checked)
                readRuntimePrefs()
            }
            .setPositiveButton("Done", null).show()
    }

    private fun geoSiteMenu() {
        val url = tabs.current?.url ?: return
        val host = UrlUtils.host(url)
        if (host.isEmpty()) return
        val allowed = host in db.sites(BrowserDb.SiteList.GEO_ALLOW)
        val denied = host in db.sites(BrowserDb.SiteList.GEO_DENY)
        val options = arrayOf("Ask each time", "Always allow", "Always deny")
        val selected = if (allowed) 1 else if (denied) 2 else 0
        AlertDialog.Builder(this).setTitle("Location for $host")
            .setSingleChoiceItems(options, selected) { d, which ->
                db.setSite(BrowserDb.SiteList.GEO_ALLOW, host, which == 1)
                db.setSite(BrowserDb.SiteList.GEO_DENY, host, which == 2)
                GeolocationPermissions.getInstance().clear(Uri.parse(url).let { "${it.scheme}://${it.authority}" })
                d.dismiss()
            }.show()
    }

    // ---------------------------------------------------------------- sidebar

    private fun openSidebar() {
        val width = (resources.displayMetrics.widthPixels * 0.88f).toInt().coerceAtMost((460 * density).toInt())
        sidebarView.layoutParams = (sidebarView.layoutParams as FrameLayout.LayoutParams).also { it.width = width }
        sidebar.show()
        sidebarScrim.isVisible = true
        sidebarView.isVisible = true
        sidebarView.translationX = -width.toFloat()
        sidebarView.animate().translationX(0f).setDuration(180).start()
        hideIme()
    }

    override fun closeSidebar() {
        if (!sidebarView.isVisible) return
        sidebarView.animate().translationX(-sidebarView.width.toFloat()).setDuration(160).withEndAction {
            sidebarView.isVisible = false
            sidebarScrim.isVisible = false
        }.start()
    }

    override fun openUrl(url: String, newTab: Boolean, keepSidebar: Boolean) {
        if (newTab) tabs.newTab(url, select = !keepSidebar) else navigate(url)
        if (!keepSidebar) closeSidebar() else toast("Opened in a new tab")
    }

    override fun currentPage(): Pair<String, String>? = tabs.current?.let { it.title to (if (it.url == Prefs.HOME_URL) "" else it.url) }

    // ---------------------------------------------------------------- gestures

    override fun isBound(gestureKey: String) = prefs.gesture(gestureKey) != GestureAction.NONE
    override fun onGesture(gestureKey: String) = perform(prefs.gesture(gestureKey))

    override fun edgeEnabled(edge: GestureFrame.Edge) = Prefs.MenuGesture.edge(when (edge) {
        GestureFrame.Edge.TOP_DOWN -> prefs.topMenuGesture
        GestureFrame.Edge.BOTTOM_UP -> prefs.bottomMenuGesture
        GestureFrame.Edge.LEFT_IN -> prefs.sideMenuGesture
    })

    override fun onEdgeSwipe(edge: GestureFrame.Edge) = when (edge) {
        GestureFrame.Edge.TOP_DOWN -> showMenus()
        GestureFrame.Edge.BOTTOM_UP -> showBottomMenu()
        GestureFrame.Edge.LEFT_IN -> openSidebar()
    }

    override fun onPageDrag(dx: Float, dy: Float, startAtTop: Boolean, startAtBottom: Boolean, startAtLeft: Boolean) {
        val min = 48 * density
        fun trigger(slot: Int, setting: Int): Boolean {
            val now = System.currentTimeMillis()
            return when {
                Prefs.MenuGesture.once(setting) -> true
                setting == Prefs.MenuGesture.TWICE -> (now - lastMenuDrag[slot] < 1500).also { lastMenuDrag[slot] = if (it) 0 else now }
                else -> false
            }
        }
        when {
            abs(dy) > abs(dx) && dy > min && startAtTop -> if (trigger(0, prefs.topMenuGesture)) showTopMenu()
            abs(dy) > abs(dx) && dy < -min && startAtBottom -> if (trigger(1, prefs.bottomMenuGesture)) showBottomMenu()
            abs(dx) > abs(dy) && dx > min && startAtLeft -> if (trigger(2, prefs.sideMenuGesture)) openSidebar()
        }
    }

    override fun onDoubleTap() {
        if (prefs.doubleTapTopMenu) showTopMenu()
        if (prefs.doubleTapBottomMenu) showBottomMenu()
        if (prefs.doubleTapFullscreen) toggleFullscreen()
        if (prefs.doubleTapReflow) reflow()
    }

    override fun pageDoubleTapZoom() = prefs.doubleTapOverviewZoom

    override fun onTouchDown() {
        if (bottomMenu.isVisible) hideBottomMenu()
        if (urlField.hasFocus()) { urlField.clearFocus(); hideIme() }
    }

    fun perform(action: GestureAction) {
        val tab = tabs.current
        val wv = tab?.webView
        when (action) {
            GestureAction.NONE -> {}
            GestureAction.ZOOM_IN -> wv?.zoomIn()
            GestureAction.ZOOM_OUT -> wv?.zoomOut()
            GestureAction.PAGE_TOP -> wv?.pageUp(true)
            GestureAction.PAGE_END -> wv?.pageDown(true)
            GestureAction.NEW_TAB -> newTabHome()
            GestureAction.CLOSE_TAB -> closeTab(tabs.currentIndex)
            GestureAction.SWITCH_TAB_RIGHT -> tabs.move(1)
            GestureAction.SWITCH_TAB_LEFT -> tabs.move(-1)
            GestureAction.BACK -> if (wv?.canGoBack() == true) wv.goBack()
            GestureAction.FORWARD -> if (wv?.canGoForward() == true) wv.goForward()
            GestureAction.PAGE_UP -> wv?.pageUp(false)
            GestureAction.PAGE_DOWN -> wv?.pageDown(false)
            GestureAction.SHOW_MENUS -> showMenus()
            GestureAction.SHOW_URL_AND_KEYBOARD -> focusUrl()
            GestureAction.REFLOW_TEXT -> reflow()
            GestureAction.SHARE_URL -> tab?.let {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, it.url).putExtra(Intent.EXTRA_SUBJECT, it.title), null))
            }
            GestureAction.FIND_ON_PAGE -> showFindBar()
            GestureAction.STOP_RELOAD -> if (tab != null && tab.progress < 100) wv?.stopLoading() else if (tab?.url == Prefs.HOME_URL) navigate(Prefs.HOME_URL) else wv?.reload()
            GestureAction.UNDO_CLOSE_TAB -> if (tabs.undoClose() == null) toast("No closed tabs")
            GestureAction.SHOW_BOOKMARKS -> openSidebar()
            GestureAction.GO_HOME -> navigate(prefs.homePage)
            GestureAction.TOGGLE_FULLSCREEN -> toggleFullscreen()
            GestureAction.TOGGLE_NIGHT_MODE -> {
                val on = !prefs.nightMode
                prefs.set(Prefs.NIGHT_MODE, on)
                tintToggle(R.id.btNightMode, on)
                tabs.tabs.forEach { t -> t.webView?.let { applyNightMode(it) } }
            }
            GestureAction.TOGGLE_DESKTOP_MODE -> tab?.let {
                it.desktopMode = !it.desktopMode
                btUserAgent.text = if (it.desktopMode) "desktop" else "android"
                wv?.let { w -> applySettings(it, w); w.reload() }
            }
            GestureAction.COPY_URL -> tab?.let { copyText(it.url) }
        }
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        val c = WindowInsetsControllerCompat(window, root)
        if (isFullscreen) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
            hideTopMenu(); hideBottomMenu()
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
            showTopMenu()
        }
        findViewById<ImageButton>(R.id.btFullscreen).setImageResource(if (isFullscreen) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen)
    }

    /** Wraps text blocks to the visible width at the current zoom. */
    private fun reflow() {
        tabs.current?.webView?.evaluateJavascript(
            "(function(){var w=Math.floor(window.visualViewport?visualViewport.width:innerWidth);" +
                "var s=document.getElementById('__cm_reflow')||document.createElement('style');s.id='__cm_reflow';" +
                "s.textContent='p,li,dd,dt,blockquote,h1,h2,h3,h4,h5,h6,figcaption,td{max-width:'+w+'px!important;overflow-wrap:break-word!important;}pre{white-space:pre-wrap!important;}';" +
                "(document.head||document.documentElement).appendChild(s);})()", null
        )
    }

    private fun reloadWithoutCache() {
        val wv = tabs.current?.webView ?: return
        wv.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        wv.reload()
        main.postDelayed({ wv.settings.cacheMode = WebSettings.LOAD_DEFAULT }, 3000)
    }

    private fun applyNightMode(wv: WebView) {
        val js = if (prefs.nightMode) {
            val img = prefs.nightImageBrightness / 100.0
            val css = "html,body{background:#000!important}*{background-color:transparent!important;color:#${hex(prefs.nightTextColor)}!important;border-color:#333!important}" +
                "a,a *{color:#${hex(prefs.nightLinkColor)}!important}a:visited,a:visited *{color:#${hex(prefs.nightVisitedLinkColor)}!important}" +
                "img,video,picture,svg,canvas{opacity:$img!important}input,textarea,select,button{background:#222!important}"
            "(function(){var s=document.getElementById('__cm_night')||document.createElement('style');s.id='__cm_night';s.textContent='$css';(document.head||document.documentElement).appendChild(s);})()"
        } else "(function(){var s=document.getElementById('__cm_night');if(s)s.remove();})()"
        wv.evaluateJavascript(js, null)
    }

    private fun hex(s: String) = s.trim().removePrefix("#").takeIf { it.matches(Regex("[0-9a-fA-F]{6}")) } ?: "CCCCCC"

    // ---------------------------------------------------------------- find, page long press, downloads, save

    private fun setUpFindBar() {
        findText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { tabs.current?.webView?.findAllAsync(s.toString()) }
        })
        findText.setOnEditorActionListener { _, _, _ -> tabs.current?.webView?.findNext(true); true }
        findViewById<View>(R.id.findNext).setOnClickListener { tabs.current?.webView?.findNext(true) }
        findViewById<View>(R.id.findPrev).setOnClickListener { tabs.current?.webView?.findNext(false) }
        findViewById<View>(R.id.findClose).setOnClickListener { hideFindBar() }
    }

    private fun showFindBar() {
        hideBottomMenu()
        findBar.isVisible = true
        findText.requestFocus()
        WindowInsetsControllerCompat(window, findText).show(WindowInsetsCompat.Type.ime())
    }

    private fun hideFindBar() {
        tabs.current?.webView?.clearMatches()
        findBar.isVisible = false
        findCount.text = ""
        WindowInsetsControllerCompat(window, findText).hide(WindowInsetsCompat.Type.ime())
    }

    private fun onPageLongPress(wv: WebView): Boolean {
        val hit = wv.hitTestResult
        return when (hit.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> { linkLongPress(hit.extra ?: return false, null); true }
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                val image = hit.extra
                val msg = Handler(Looper.getMainLooper()) { m ->
                    val href = m.data.getString("url")
                    if (!href.isNullOrEmpty()) linkLongPress(href, image) else image?.let { imageMenu(it) }
                    true
                }.obtainMessage()
                wv.requestFocusNodeHref(msg)
                true
            }
            WebView.HitTestResult.IMAGE_TYPE -> { hit.extra?.let { imageMenu(it) }; true }
            WebView.HitTestResult.EDIT_TEXT_TYPE -> false
            else -> if (!prefs.longPressSelectsText && !selectTextOnce) true else { selectTextOnce = false; false }
        }
    }

    private fun linkLongPress(url: String, imageUrl: String?) {
        when (prefs.linkLongPress) {
            1 -> { tabs.newTab(url, select = false); tabBar.render(tabs.tabs, tabs.currentIndex); toast("Opened in a background tab") }
            2 -> tabs.newTab(url)
            else -> {
                val items = mutableListOf("Open in new tab", "Open in background tab", "Copy link address", "Share link", "Download link", "Bookmark link")
                if (imageUrl != null) items += listOf("Open image in new tab", "Save image", "Copy image address")
                AlertDialog.Builder(this).setTitle(url).setItems(items.toTypedArray()) { _, which ->
                    when (items[which]) {
                        "Open in new tab" -> tabs.newTab(url)
                        "Open in background tab" -> tabs.newTab(url, select = false)
                        "Copy link address" -> copyText(url)
                        "Share link" -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), null))
                        "Download link" -> download(url, tabs.current?.webView?.settings?.userAgentString ?: defaultUserAgent, null, null)
                        "Bookmark link" -> sidebar.editBookmark(Bookmark(0, "", "", url))
                        "Open image in new tab" -> tabs.newTab(imageUrl!!)
                        "Save image" -> download(imageUrl!!, defaultUserAgent, null, null)
                        "Copy image address" -> copyText(imageUrl!!)
                    }
                }.show()
            }
        }
    }

    private fun imageMenu(imageUrl: String) {
        val items = arrayOf("Open image in new tab", "Save image", "Copy image address", "Share image address")
        AlertDialog.Builder(this).setTitle(imageUrl).setItems(items) { _, which ->
            when (which) {
                0 -> tabs.newTab(imageUrl)
                1 -> download(imageUrl, defaultUserAgent, null, null)
                2 -> copyText(imageUrl)
                3 -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, imageUrl), null))
            }
        }.show()
    }

    private fun download(url: String, userAgent: String?, disposition: String?, mime: String?) {
        if (!url.startsWith("http")) { toast("This download can't be saved by the browser"); return }
        if (!prefs.systemDownloader) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(null)) }
            catch (e: ActivityNotFoundException) { toast("No app can handle this download") }
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingDownload = { download(url, userAgent, disposition, mime) }
            permissionLauncher.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
            return
        }
        val name = URLUtil.guessFileName(url, disposition, mime)
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            if (!mime.isNullOrEmpty()) setMimeType(mime)
            if (!userAgent.isNullOrEmpty()) addRequestHeader("User-Agent", userAgent)
            CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
            setTitle(name)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        }
        try {
            getSystemService(DownloadManager::class.java).enqueue(request)
            toast("Downloading $name")
        } catch (e: Exception) {
            toast("Download failed: ${e.message}")
        }
    }

    private fun openDownloads() {
        try { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: ActivityNotFoundException) { toast("No downloads app") }
    }

    /** Saves the page as a web archive (.mht) into Downloads. */
    private fun savePage() {
        val tab = tabs.current ?: return
        val wv = tab.webView ?: return
        val base = tab.label.replace(Regex("[^\\w .-]+"), "_").take(60).ifBlank { "page" }
        val tmp = File(cacheDir, "save.mht")
        wv.saveWebArchive(tmp.path, false) { path ->
            if (path == null) { toast("Could not save this page"); return@saveWebArchive }
            io.execute {
                val ok = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, "$base.mht")
                            put(MediaStore.Downloads.MIME_TYPE, "multipart/related")
                        }
                        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("no uri")
                        contentResolver.openOutputStream(uri)!!.use { out -> File(path).inputStream().use { it.copyTo(out) } }
                    } else {
                        File(path).copyTo(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "$base.mht"), overwrite = true)
                    }
                    File(path).delete()
                }.isSuccess
                main.post { toast(if (ok) "Saved $base.mht to Downloads" else "Could not save this page") }
            }
        }
    }

    // ---------------------------------------------------------------- external apps and intents

    /** Hands non-web links (tel:, mailto:, intent:, market: …) and optionally YouTube to other apps. Returns true if handled. */
    private fun openExternally(url: String): Boolean {
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme == "http" || scheme == "https") {
            val host = UrlUtils.host(url)
            if (prefs.forceYouTubeApp && (host == "youtube.com" || host == "m.youtube.com" || host == "youtu.be")) {
                return tryStart(Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.youtube"))
            }
            return false
        }
        if (scheme in setOf("about", "data", "javascript", "blob", "file", "content")) return false
        if (scheme == "intent") {
            val intent = runCatching { Intent.parseUri(url, Intent.URI_INTENT_SCHEME) }.getOrNull() ?: return true
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            intent.component = null
            intent.selector = null
            if (tryStart(intent)) return true
            intent.getStringExtra("browser_fallback_url")?.let { if (it.startsWith("http")) navigate(it) }
            return true
        }
        if (!tryStart(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))) toast("No app can open $scheme: links")
        return true
    }

    private fun tryStart(intent: Intent) = try { startActivity(intent); true } catch (e: ActivityNotFoundException) { false } catch (e: SecurityException) { false }

    private fun handleIntent(intent: Intent?): Boolean {
        intent ?: return false
        val url = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_WEB_SEARCH, Intent.ACTION_SEARCH -> intent.getStringExtra("query")?.let { UrlUtils.toUrl(it, prefs.searchUrl) }
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                Regex("https?://\\S+").find(text)?.value ?: UrlUtils.toUrl(text, prefs.searchUrl)
            }
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.let { UrlUtils.toUrl(it, prefs.searchUrl) }
            else -> null
        } ?: return false
        intent.action = Intent.ACTION_MAIN // don't reopen the same link after a configuration change
        val tab = tabs.newTab(url)
        tab.openedExternally = true
        if (prefs.backgroundLoadIntents) moveTaskToBack(true)
        return true
    }

    private fun onBack() {
        val tab = tabs.current
        val wv = tab?.webView
        when {
            customView != null -> customViewCallback?.onCustomViewHidden()
            sidebarView.isVisible -> closeSidebar()
            findBar.isVisible -> hideFindBar()
            urlField.hasFocus() -> { urlField.clearFocus(); hideIme() }
            bottomMenu.isVisible -> hideBottomMenu()
            wv?.canGoBack() == true -> wv.goBack()
            tab?.openedExternally == true && tabs.tabs.size > 1 -> { closeTab(tabs.currentIndex); moveTaskToBack(true) }
            else -> moveTaskToBack(true)
        }
    }

    private fun exitBrowser() {
        prefs.savedTabs = if (prefs.rememberTabs && tabs.tabs.size > 1) tabs.toJson() else ""
        DataCleaner.clearOnExit(this, tabs.current?.webView)
        finishAndRemoveTask()
    }

    // ---------------------------------------------------------------- keys

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && (event.isCtrlPressed || (prefs.altShortcuts && event.isAltPressed))) {
            val handled = when (event.keyCode) {
                KeyEvent.KEYCODE_T -> { newTabHome(); true }
                KeyEvent.KEYCODE_W -> { closeTab(tabs.currentIndex); true }
                KeyEvent.KEYCODE_R -> { perform(GestureAction.STOP_RELOAD); true }
                KeyEvent.KEYCODE_L -> { focusUrl(); true }
                KeyEvent.KEYCODE_F -> { showFindBar(); true }
                KeyEvent.KEYCODE_TAB -> { tabs.move(if (event.isShiftPressed) -1 else 1); true }
                KeyEvent.KEYCODE_LEFT_BRACKET -> { perform(GestureAction.BACK); true }
                KeyEvent.KEYCODE_RIGHT_BRACKET -> { perform(GestureAction.FORWARD); true }
                else -> false
            }
            if (handled) return true
        }
        if (customView == null && (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            val up = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP
            val action = when (prefs.volumeAction) {
                Prefs.VolumeAction.SCROLL -> if (up) GestureAction.PAGE_UP else GestureAction.PAGE_DOWN
                Prefs.VolumeAction.SWITCH_TAB -> if (up) GestureAction.SWITCH_TAB_LEFT else GestureAction.SWITCH_TAB_RIGHT
                Prefs.VolumeAction.ZOOM -> if (up) GestureAction.ZOOM_IN else GestureAction.ZOOM_OUT
                Prefs.VolumeAction.TOP_END -> if (up) GestureAction.PAGE_TOP else GestureAction.PAGE_END
                else -> null
            }
            if (action != null) {
                if (event.action == KeyEvent.ACTION_DOWN) perform(action)
                return true
            }
        }
        if (event.keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_UP) {
            if (bottomMenu.isVisible) hideBottomMenu() else showMenus()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------------------------------------------------------------- helpers

    private fun copyText(text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("address", text))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) toast("Copied")
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    // ---------------------------------------------------------------- WebView clients

    private inner class PageClient(private val tab: Tab) : WebViewClient() {
        private var reflowPending = false

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            val url = request.url.toString()
            if (openExternally(url)) return true
            applyPerSite(tab, view, url)
            return false
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            tab.url = if (url.startsWith(HomePage.BASE_URL)) Prefs.HOME_URL else url
            if (tab.url == Prefs.HOME_URL) tab.title = ""
            tabs.tabs.indexOf(tab).takeIf { it >= 0 }?.let { tabBar.updateLabel(it, tab) }
            if (tab === tabs.current) {
                updateUrlField()
                if (prefs.topMenuOnLoad) showTopMenu()
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (tab === tabs.current) updateUrlField()
            if (prefs.nightMode) applyNightMode(view)
            val title = view.title.orEmpty()
            if (!haltHistory && prefs.saveHistory && !url.startsWith(HomePage.BASE_URL) && url.startsWith("http")) {
                val t = if (title == url) "" else title
                io.execute { db.recordVisit(url, t) }
            }
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            if (url.startsWith(HomePage.BASE_URL)) return
            tab.url = url
            if (tab === tabs.current) updateUrlField()
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (!blockAds || request.isForMainFrame) return null
            val host = request.url.host?.lowercase()?.removePrefix("www.") ?: return null
            return if (AdBlocker.isBlocked(host)) WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))) else null
        }

        override fun onScaleChanged(view: WebView, oldScale: Float, newScale: Float) {
            if (!prefs.zoomReflowsText || reflowPending || tab !== tabs.current) return
            reflowPending = true
            main.postDelayed({ reflowPending = false; reflow() }, 350)
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            val host = UrlUtils.host(error.url)
            if (host in sslAllowedHosts) { handler.proceed(); return }
            when (prefs.securityExceptions) {
                1 -> handler.proceed()
                0 -> { handler.cancel(); toast("Blocked $host: certificate problem") }
                else -> AlertDialog.Builder(this@BrowserActivity)
                    .setTitle("Certificate problem")
                    .setMessage("$host has an invalid security certificate (${sslReason(error)}). Someone could be intercepting the connection.")
                    .setPositiveButton("Go back") { _, _ -> handler.cancel() }
                    .setNegativeButton("Continue anyway") { _, _ -> sslAllowedHosts.add(host); handler.proceed() }
                    .setOnCancelListener { handler.cancel() }
                    .show()
            }
        }

        private fun sslReason(e: SslError) = when (e.primaryError) {
            SslError.SSL_EXPIRED -> "expired"
            SslError.SSL_IDMISMATCH -> "issued for a different site"
            SslError.SSL_NOTYETVALID -> "not valid yet"
            SslError.SSL_UNTRUSTED -> "not from a trusted authority"
            SslError.SSL_DATE_INVALID -> "invalid date"
            else -> "invalid"
        }

        override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) {
            val dbw = WebViewDatabase.getInstance(this@BrowserActivity)
            if (prefs.saveBasicAuth && handler.useHttpAuthUsernamePassword()) {
                dbw.getHttpAuthUsernamePassword(host, realm)?.let { creds ->
                    if (creds.size == 2 && creds[0] != null && creds[1] != null) { handler.proceed(creds[0], creds[1]); return }
                }
            }
            val pad = (16 * density).toInt()
            val form = LinearLayout(this@BrowserActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
            val user = EditText(this@BrowserActivity).apply { hint = "username"; setSingleLine() }
            val pass = EditText(this@BrowserActivity).apply { hint = "password"; setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
            form.addView(user); form.addView(pass)
            AlertDialog.Builder(this@BrowserActivity).setTitle("Sign in to $host").setMessage(realm).setView(form)
                .setPositiveButton("Sign in") { _, _ ->
                    val u = user.text.toString(); val p = pass.text.toString()
                    if (prefs.saveBasicAuth) dbw.setHttpAuthUsernamePassword(host, realm, u, p)
                    handler.proceed(u, p)
                }
                .setNegativeButton("Cancel") { _, _ -> handler.cancel() }
                .setOnCancelListener { handler.cancel() }
                .show()
        }

        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
            // the page's renderer crashed or was killed: drop this WebView and let the tab reload when shown
            val index = tabs.tabs.indexOf(tab)
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
            tab.webView = null
            tab.savedState = null
            if (index == tabs.currentIndex) tabs.select(index) else tabBar.render(tabs.tabs, tabs.currentIndex)
            return true
        }
    }

    private inner class ChromeClient(private val tab: Tab) : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            tab.progress = newProgress
            updateProgress(tab)
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            if (tab.url == Prefs.HOME_URL) return
            tab.title = title.orEmpty()
            val index = tabs.tabs.indexOf(tab)
            if (index >= 0) tabBar.updateLabel(index, tab)
            val url = tab.url
            if (!haltHistory && prefs.saveHistory && !title.isNullOrBlank()) io.execute { db.updateTitle(url, title) }
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) { callback.onCustomViewHidden(); return }
            customView = view
            customViewCallback = callback
            fullscreenContainer.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            fullscreenContainer.isVisible = true
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            WindowInsetsControllerCompat(window, root).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }

        override fun onHideCustomView() {
            val v = customView ?: return
            fullscreenContainer.removeView(v)
            fullscreenContainer.isVisible = false
            customView = null
            customViewCallback = null
            if (!prefs.keepScreenOn) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (!isFullscreen) WindowInsetsControllerCompat(window, root).show(WindowInsetsCompat.Type.systemBars())
        }

        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            pendingFileCallback?.onReceiveValue(null)
            pendingFileCallback = callback
            return try { fileChooser.launch(params.createIntent()); true } catch (e: ActivityNotFoundException) { pendingFileCallback = null; false }
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            val host = UrlUtils.host(origin)
            if (!prefs.geolocation || host in db.sites(BrowserDb.SiteList.GEO_DENY)) { callback.invoke(origin, false, false); return }
            val grant = {
                val perms = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                if (perms.any { ContextCompat.checkSelfPermission(this@BrowserActivity, it) == PackageManager.PERMISSION_GRANTED }) callback.invoke(origin, true, false)
                else { pendingGeo = origin to callback; permissionLauncher.launch(perms) }
            }
            if (host in db.sites(BrowserDb.SiteList.GEO_ALLOW)) { grant(); return }
            AlertDialog.Builder(this@BrowserActivity).setTitle("Location").setMessage("$host wants to know your location.")
                .setPositiveButton("Allow") { _, _ -> grant() }
                .setNeutralButton("Always allow") { _, _ -> db.setSite(BrowserDb.SiteList.GEO_ALLOW, host, true); grant() }
                .setNegativeButton("Deny") { _, _ -> callback.invoke(origin, false, false) }
                .setOnCancelListener { callback.invoke(origin, false, false) }
                .show()
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            val wanted = request.resources.filter { it == PermissionRequest.RESOURCE_VIDEO_CAPTURE || it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }
            if (wanted.isEmpty()) { request.deny(); return }
            val what = wanted.joinToString(" and ") { if (it == PermissionRequest.RESOURCE_VIDEO_CAPTURE) "camera" else "microphone" }
            AlertDialog.Builder(this@BrowserActivity).setTitle("Allow $what?").setMessage("${UrlUtils.host(request.origin.toString())} wants to use your $what.")
                .setPositiveButton("Allow") { _, _ ->
                    val perms = wanted.map { if (it == PermissionRequest.RESOURCE_VIDEO_CAPTURE) Manifest.permission.CAMERA else Manifest.permission.RECORD_AUDIO }.toTypedArray()
                    pendingPermission = request
                    permissionLauncher.launch(perms)
                }
                .setNegativeButton("Deny") { _, _ -> request.deny() }
                .setOnCancelListener { request.deny() }
                .show()
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (!isUserGesture && !prefs.allowPopups) return false
            val open = {
                val newTab = tabs.newTabForWindow()
                (resultMsg.obj as WebView.WebViewTransport).webView = newTab.webView
                resultMsg.sendToTarget()
            }
            if (isUserGesture) { open(); return true }
            AlertDialog.Builder(this@BrowserActivity).setTitle("Popup").setMessage("${UrlUtils.host(tab.url)} wants to open a new window.")
                .setPositiveButton("Open") { _, _ -> open() }
                .setNegativeButton("Block") { _, _ ->
                    val sink = WebView(this@BrowserActivity)
                    (resultMsg.obj as WebView.WebViewTransport).webView = sink
                    resultMsg.sendToTarget()
                    main.post { sink.destroy() }
                }
                .setCancelable(false)
                .show()
            return true
        }

        override fun onCloseWindow(window: WebView) {
            val index = tabs.indexOf(window)
            if (index >= 0) closeTab(index)
        }
    }

    companion object {
        private const val MENU_PASTE_GO = 0x5047
    }
}
