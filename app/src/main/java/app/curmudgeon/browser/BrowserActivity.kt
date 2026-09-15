// SPDX-License-Identifier: GPL-3.0-only
package app.curmudgeon.browser

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
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
import android.os.Parcel
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.MediaStore
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Base64
import android.util.Rational
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
import android.webkit.MimeTypeMap
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
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
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
    private var siteThirdPartyCookies: Set<String> = emptySet()
    private val drmAllowedHosts = mutableSetOf<String>()
    /** DownloadManager ids started here; true = open the file when done if it is a PDF (a tapped link). */
    private val downloadIds = mutableMapOf<Long, Boolean>()
    /** Pending blob: download — the page posts the file back with this token (see [fetchBlob]). */
    private var blobToken = ""
    private var blobTokenTime = 0L
    private var actionBar: View? = null
    private var cameraFile: File? = null
    private var cameraAsked = false
    private var pendingCameraChoice: ((Boolean) -> Unit)? = null
    private var pipFitListener: View.OnLayoutChangeListener? = null
    /** The tab whose address the address bar belongs to; switching tabs discards unfinished typing. */
    private var shownTabId = -1L
    /** Width / height of the video shown fullscreen, when the page told us (0 = unknown). */
    private var fullscreenVideoRatio = 0f

    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingGeo: Pair<String, GeolocationPermissions.Callback>? = null
    private var pendingPermission: PermissionRequest? = null
    private var pendingDownload: (() -> Unit)? = null

    private val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val picked = mutableListOf<Uri>()
        if (result.resultCode == RESULT_OK) {
            result.data?.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { picked += it } }
            if (picked.isEmpty()) result.data?.data?.let { picked += it }
            val photo = cameraFile
            if (picked.isEmpty() && photo != null && photo.length() > 0) picked += FileProvider.getUriForFile(this, "$packageName.files", photo)
        }
        cameraFile = null
        pendingFileCallback?.onReceiveValue(picked.toTypedArray().takeIf { it.isNotEmpty() })
        pendingFileCallback = null
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pendingCameraChoice?.invoke(granted)
        pendingCameraChoice = null
    }
    private val downloadDone = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onDownloadComplete(intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1))
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
        // Pages that go away hand keyboard focus here instead of to the address bar (see [detachPage]).
        webFrame.isFocusableInTouchMode = true
        sidebarScrim.setOnClickListener { closeSidebar() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onBack()
        })

        ContextCompat.registerReceiver(this, downloadDone, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)
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
        if (prefs.rememberTabs) saveTabs()
        // Fullscreen video keeps playing: Android may pause the app just before it becomes a picture-in-picture
        // window. If the app is really hidden instead, onStop pauses the page.
        if (!isInPictureInPictureMode && customView == null) tabs.current?.webView?.onPause()
        if (isInPictureInPictureMode) freezeFullscreenVideo()
        CookieManager.getInstance().flush()
    }

    override fun onStop() {
        super.onStop()
        tabs.current?.webView?.onPause() // also when the picture-in-picture window is closed
    }

    override fun onDestroy() {
        if (isFinishing) tabs.tabs.forEach { t -> t.webView?.let { destroyWebView(t) } }
        runCatching { unregisterReceiver(downloadDone) }
        io.shutdown()
        super.onDestroy()
    }

    /** Leaving the app during fullscreen video shrinks it to a picture-in-picture window (Android 8-11; 12+ enters automatically). */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (customView != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
            runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(pictureInPictureRatio()).build()) }
    }

    /**
     * Sites like YouTube pause and leave fullscreen when the page suddenly becomes tiny, which is what a
     * picture-in-picture window does to it. Keep the fullscreen video view at its full-screen size and only scale
     * the picture down to fit the window, so the page never sees a resize (Chrome's own PiP avoids it the same way).
     */
    private fun freezeFullscreenVideo() {
        val v = customView ?: return
        if (pipFitListener != null || v.width == 0 || v.height == 0) return
        val w = v.width
        val h = v.height
        v.layoutParams = FrameLayout.LayoutParams(w, h)
        val video = fullscreenVideoRatio
        val fit = View.OnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val boxW = (right - left).toFloat()
            val boxH = (bottom - top).toFloat()
            // The video sits centred in the view with bars around it: zoom so the video itself fills the window.
            val videoW = if (video > 0f) minOf(w.toFloat(), h * video) else w.toFloat()
            val videoH = if (video > 0f) minOf(h.toFloat(), w / video) else h.toFloat()
            val scale = minOf(boxW / videoW, boxH / videoH)
            v.pivotX = 0f
            v.pivotY = 0f
            v.scaleX = scale
            v.scaleY = scale
            v.translationX = (boxW - w * scale) / 2
            v.translationY = (boxH - h * scale) / 2
        }
        fullscreenContainer.addOnLayoutChangeListener(fit)
        pipFitListener = fit
    }

    private fun unfreezeFullscreenVideo() {
        val fit = pipFitListener ?: return
        fullscreenContainer.removeOnLayoutChangeListener(fit)
        pipFitListener = null
        customView?.apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            scaleX = 1f
            scaleY = 1f
            translationX = 0f
            translationY = 0f
        }
    }

    /** In the picture-in-picture window show only the page (or its fullscreen video), not the browser's bars. */
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            freezeFullscreenVideo()
            topMenu.isVisible = false
            hideBottomMenu()
            findBar.isVisible = false
            actionBar?.let { root.removeView(it); actionBar = null }
        } else {
            unfreezeFullscreenVideo()
            if (customView == null) showTopMenu()
        }
    }

    /** Like Chrome: fullscreen video turns the screen to landscape, unless the video itself is portrait. */
    private fun rotateForFullscreenVideo() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        main.postDelayed({
            if (customView == null) return@postDelayed
            tabs.current?.webView?.evaluateJavascript(
                "(function(){var e=document.fullscreenElement||document.webkitFullscreenElement;" +
                    "var v=e&&(e.tagName=='VIDEO'?e:e.querySelector('video'));" +
                    "return v&&v.videoWidth?v.videoWidth/v.videoHeight:0;})()"
            ) { result ->
                if (customView == null) return@evaluateJavascript
                fullscreenVideoRatio = result.toFloatOrNull() ?: 0f
                if (fullscreenVideoRatio in 0.01f..0.99f) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
                updatePictureInPicture()
            }
        }, 400)
    }

    private fun updatePictureInPicture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            setPictureInPictureParams(PictureInPictureParams.Builder().setAspectRatio(pictureInPictureRatio()).setAutoEnterEnabled(customView != null).build())
    }

    /** The fullscreen video view's shape, within the limits Android allows for picture-in-picture (2.39:1 either way). */
    private fun pictureInPictureRatio(): Rational {
        val v = customView
        if (v == null || v.width == 0 || v.height == 0 || pipFitListener != null) return Rational(16, 9)
        val r = (if (fullscreenVideoRatio > 0f) fullscreenVideoRatio else v.width.toFloat() / v.height).coerceIn(1 / 2.39f, 2.39f)
        return Rational((r * 1000).toInt(), 1000)
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
        siteThirdPartyCookies = db.sites(BrowserDb.SiteList.THIRD_PARTY_COOKIES)
        tabs.cacheThreshold = prefs.tabCacheThreshold
        CookieManager.getInstance().setAcceptCookie(prefs.saveCookies)
        if (prefs.keepScreenOn || customView != null) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        urlField.inputType = when (prefs.urlBarKeyboard) {
            1 -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            2 -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        tintToggle(R.id.btNightMode, prefs.nightMode)
        tintToggle(R.id.btOpenInApps, prefs.openLinksInApps)
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
        saved.forEachIndexed { i, (url, title) -> tabs.newTab(url, select = false, afterCurrent = false, state = readTabHistory(i)).title = title }
        tabs.select(current)
    }

    private val tabHistoryDir get() = File(filesDir, "tab_history")

    /** Saves the tab list and each tab's back/forward history, so both survive Android killing the app. */
    private fun saveTabs() {
        prefs.savedTabs = tabs.toJson()
        val dir = tabHistoryDir.apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        if (prefs.clearHistoryOnExit) return
        tabs.historyStates().forEachIndexed { i, state ->
            state ?: return@forEachIndexed
            val parcel = Parcel.obtain()
            try {
                parcel.writeBundle(state)
                runCatching { File(dir, "$i").writeBytes(parcel.marshall()) }
            } finally {
                parcel.recycle()
            }
        }
    }

    private fun readTabHistory(index: Int): Bundle? = runCatching {
        val bytes = File(tabHistoryDir, "$index").takeIf { it.exists() }?.readBytes() ?: return null
        val parcel = Parcel.obtain()
        try {
            parcel.unmarshall(bytes, 0, bytes.size)
            parcel.setDataPosition(0)
            parcel.readBundle(classLoader)
        } finally {
            parcel.recycle()
        }
    }.getOrNull()

    private fun createWebView(tab: Tab): WebView {
        // WebView reports prefers-color-scheme from its context theme, so give it a light theme unless dark pages are on
        val themed = if (prefs.forceDarkPages) this else ContextThemeWrapper(this, R.style.Theme_Curmudgeon_WebLight)
        val wv = WebView(themed)
        wv.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        wv.setBackgroundColor(ContextCompat.getColor(this, R.color.page_bg))
        if (defaultUserAgent.isEmpty()) defaultUserAgent = UrlUtils.browserUserAgent(wv.settings.userAgentString)
        applySettings(tab, wv)
        wv.webViewClient = PageClient(tab)
        wv.webChromeClient = ChromeClient(tab)
        wv.setDownloadListener { url, ua, disposition, mime, _ -> onDownloadStart(tab, url, ua, disposition, mime) }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
            WebViewCompat.addWebMessageListener(wv, BLOB_BRIDGE, setOf("*")) { _, message, _, _, _ -> message.data?.let { onBlobMessage(it) } }
        wv.setFindListener { active, count, done -> if (done) findCount.text = if (count == 0) "0/0" else "${active + 1}/$count" }
        wv.setOnScrollChangeListener { _, _, y, _, oldY -> if (tab === tabs.current) onPageScroll(y, oldY) }
        wv.setOnLongClickListener { onPageLongPress(wv) }
        return wv
    }

    /**
     * Removes a page from the screen. If it had keyboard focus, Android would give focus to the address bar, which
     * then swallows the next Back and stops the address from updating; park focus on the page frame instead.
     */
    private fun detachPage(view: View) {
        if (view.hasFocus()) webFrame.requestFocus()
        (view.parent as? ViewGroup)?.removeView(view)
    }

    private fun destroyWebView(tab: Tab) {
        val wv = tab.webView ?: return
        detachPage(wv)
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
        applyBrowserIdentity(s, tab.desktopMode)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING))
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, prefs.forceDarkPages)
        // passkeys: the system's credential manager handles them as for a browser
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION))
            WebSettingsCompat.setWebAuthenticationSupport(s, WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_BROWSER)
        applyPerSite(tab, wv, tab.url)
    }

    /**
     * Besides the user agent string, WebView announces itself in client hints ("Android WebView" brand) and in an
     * X-Requested-With header carrying our package name. Google sign-in rejects embedded web views, so present
     * the Chrome brand and drop the header.
     */
    private fun applyBrowserIdentity(s: WebSettings, desktop: Boolean) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            val meta = WebSettingsCompat.getUserAgentMetadata(s)
            val brands = meta.brandVersionList.map { b ->
                if (b.brand == "Android WebView") UserAgentMetadata.BrandVersion.Builder(b).setBrand("Google Chrome").build() else b
            }
            WebSettingsCompat.setUserAgentMetadata(s, UserAgentMetadata.Builder(meta).setBrandVersionList(brands)
                .setMobile(!desktop).setPlatform(if (desktop) "Linux" else "Android").build())
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST))
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, emptySet())
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
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, prefs.thirdPartyCookies || (!home && UrlUtils.matchesAny(host, siteThirdPartyCookies)))
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
        val switched = tab.id != shownTabId
        if (switched) {
            // Unfinished typing stays with the tab it was typed in; this tab shows its own address (or its own draft).
            if (urlField.hasFocus()) {
                tabs.tabs.firstOrNull { it.id == shownTabId }?.addressDraft = urlField.text.toString()
                webFrame.requestFocus()
                hideIme()
            }
            shownTabId = tab.id
        }
        val editingAddress = urlField.hasFocus()
        for (i in webFrame.childCount - 1 downTo 0) {
            val child = webFrame.getChildAt(i)
            if (child !== wv) detachPage(child)
        }
        if (wv != null && wv.parent !== webFrame) {
            detachPage(wv)
            webFrame.addView(wv)
            // Removing the old page hands focus to the address bar, which then swallows the next Back and
            // stops the address from updating: give it to the page unless the user was typing an address.
            if (!editingAddress) wv.requestFocus()
        }
        webFrame.webView = wv
        tabBar.render(tabs.tabs, tabs.currentIndex)
        updateUrlField()
        if (switched) tab.addressDraft?.let { draft ->
            tab.addressDraft = null
            focusUrl()
            urlField.setText(draft)
            urlField.setSelection(draft.length)
        }
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
        click(R.id.btSavePage) { printPage() }
        long(R.id.btSavePage) { savePage() }
        click(R.id.btOpenInApps) {
            val on = !prefs.openLinksInApps
            prefs.set(Prefs.OPEN_LINKS_IN_APPS, on)
            tintToggle(R.id.btOpenInApps, on)
            toast(if (on) "Links to other sites open in their apps" else "Links stay on the website")
        }
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
        click(R.id.btCookie) { cookieSiteMenu() }
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

    private fun cookieSiteMenu() {
        val tab = tabs.current ?: return
        val host = UrlUtils.host(tab.url)
        if (host.isEmpty()) { toast("No site open"); return }
        val lists = arrayOf(BrowserDb.SiteList.COOKIES_KEEP, BrowserDb.SiteList.THIRD_PARTY_COOKIES)
        val checked = lists.map { host in db.sites(it) }.toBooleanArray()
        val before = checked[1]
        AlertDialog.Builder(this).setTitle("Cookies for $host")
            .setMultiChoiceItems(arrayOf("Keep them when clearing cookies", "Allow third-party cookies (for sign-ins and embedded pages)"), checked) { _, which, on ->
                checked[which] = on
                db.setSite(lists[which], host, on)
                readRuntimePrefs()
            }
            .setPositiveButton("Done") { _, _ ->
                if (checked[1] != before) tab.webView?.let { applyPerSite(tab, it, tab.url); it.reload() }
            }.show()
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
            1 -> { openChildTab(url, select = false); tabBar.render(tabs.tabs, tabs.currentIndex); toast("Opened in a background tab") }
            2 -> openChildTab(url, select = true)
            else -> {
                val items = mutableListOf("Open in new tab", "Open in background tab", "Open in app", "Copy link address", "Share link", "Download link", "Bookmark link")
                if (imageUrl != null) items += listOf("Open image in new tab", "Save image", "Copy image address")
                AlertDialog.Builder(this).setTitle(url).setItems(items.toTypedArray()) { _, which ->
                    when (items[which]) {
                        "Open in new tab" -> openChildTab(url, select = true)
                        "Open in background tab" -> openChildTab(url, select = false)
                        "Open in app" -> if (!openInApp(Uri.parse(url))) toast("No app for this link")
                        "Copy link address" -> copyText(url)
                        "Share link" -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), null))
                        "Download link" -> download(url, tabs.current?.webView?.settings?.userAgentString ?: defaultUserAgent, null, null, openPdf = false)
                        "Bookmark link" -> sidebar.editBookmark(Bookmark(0, "", "", url))
                        "Open image in new tab" -> tabs.newTab(imageUrl!!)
                        "Save image" -> download(imageUrl!!, defaultUserAgent, null, null, openPdf = false)
                        "Copy image address" -> copyText(imageUrl!!)
                    }
                }.show()
            }
        }
    }

    /** A tab for a link on the current page; back at the start of its history returns to this page. */
    private fun openChildTab(url: String, select: Boolean) {
        val opener = tabs.current
        tabs.newTab(url, select = select).openerId = opener?.id
    }

    private fun imageMenu(imageUrl: String) {
        val items = arrayOf("Open image in new tab", "Save image", "Copy image address", "Share image address")
        AlertDialog.Builder(this).setTitle(imageUrl).setItems(items) { _, which ->
            when (which) {
                0 -> tabs.newTab(imageUrl)
                1 -> download(imageUrl, defaultUserAgent, null, null, openPdf = false)
                2 -> copyText(imageUrl)
                3 -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, imageUrl), null))
            }
        }.show()
    }

    /** A page started a download (a tapped link, possibly in a new window). */
    private fun onDownloadStart(tab: Tab, url: String, userAgent: String?, disposition: String?, mime: String?) {
        download(url, userAgent, disposition, mime, tab)
        // A link opened in a new window that turned out to be a file leaves an empty tab: close it and go back.
        // (Not for blob: links, which are read from that tab's page.)
        if (tab.openerId != null && !url.startsWith("blob:") && (tab.url.isEmpty() || tab.url == "about:blank")) {
            main.post { tabs.tabs.indexOf(tab).takeIf { it >= 0 }?.let { tabs.close(it, returnToOpener = true) } }
        }
    }

    private fun download(url: String, userAgent: String?, disposition: String?, mime: String?, tab: Tab? = tabs.current, openPdf: Boolean = true) {
        when {
            // the page reads these itself, which also gives the link's download="name"
            url.startsWith("blob:") -> { fetchBlob(tab, url); return }
            url.startsWith("data:") -> {
                if (tab?.webView?.settings?.javaScriptEnabled == true) fetchBlob(tab, url) else saveDataUrl(url, fallbackFileName(mime), mime)
                return
            }
            !url.startsWith("http") -> { toast("This download can't be saved by the browser"); return }
        }
        if (!prefs.systemDownloader) {
            // hand it to another app, never back to this browser (which would download it again, forever)
            val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
            val chooser = Intent.createChooser(view, "Download with")
                .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(this, BrowserActivity::class.java)))
            if (!tryStart(chooser)) toast("No app can handle this download")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingDownload = { download(url, userAgent, disposition, mime, tab, openPdf) }
            permissionLauncher.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
            return
        }
        val name = URLUtil.guessFileName(url, disposition, mime)
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            if (!mime.isNullOrEmpty()) setMimeType(mime)
            if (!userAgent.isNullOrEmpty()) addRequestHeader("User-Agent", userAgent)
            CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
            tab?.url?.takeIf { it.startsWith("http") }?.let { addRequestHeader("Referer", it) }
            setTitle(name)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        }
        try {
            downloadIds[getSystemService(DownloadManager::class.java).enqueue(request)] = openPdf
            toast("Downloading $name")
        } catch (e: Exception) {
            toast("Download failed: ${e.message}")
        }
    }

    /** A download started here finished: PDFs from a tapped link open right away, other files offer "Open". */
    private fun onDownloadComplete(id: Long) {
        val openPdf = downloadIds.remove(id) ?: return
        val dm = getSystemService(DownloadManager::class.java)
        val (ok, name) = dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) return
            (c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL) to
                c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)).orEmpty()
        }
        if (!ok) { toast("Download failed: $name"); return }
        val uri = dm.getUriForDownloadedFile(id) ?: return
        val mime = dm.getMimeTypeForDownloadedFile(id)?.takeIf { it != "application/octet-stream" }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
        if (openPdf && mime == "application/pdf") openFile(uri, mime)
        else showActionBar("Downloaded $name", "Open") { openFile(uri, mime) }
    }

    private fun openFile(uri: Uri, mime: String?) {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime ?: contentResolver.getType(uri) ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!tryStart(intent)) toast("No app can open this file")
    }

    private fun fallbackFileName(mime: String?) = "download." + (mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: "bin")

    /** Saves a data: URL (a file the page built itself) into Downloads. */
    private fun saveDataUrl(dataUrl: String, name: String, fallbackMime: String?) {
        val comma = dataUrl.indexOf(',')
        if (comma < 0) { toast("This download can't be saved by the browser"); return }
        val header = dataUrl.substring(5, comma)
        val mime = header.substringBefore(';').ifBlank { fallbackMime ?: "application/octet-stream" }
        runIo {
            val bytes = runCatching {
                if (header.endsWith(";base64")) Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
                else Uri.decode(dataUrl.substring(comma + 1)).toByteArray()
            }.getOrNull()
            if (bytes == null) main.post { toast("Could not read this download") } else saveBytes(bytes, name, mime)
        }
    }

    /**
     * blob: (and data:) links point at data inside the page, which DownloadManager cannot fetch. The page reads the blob and
     * posts it back through a web message listener; the one-time [blobToken] makes sure only this download is saved.
     */
    private fun fetchBlob(tab: Tab?, blobUrl: String) {
        val wv = tab?.webView ?: run { toast("This download can't be saved by the browser"); return }
        blobToken = java.util.UUID.randomUUID().toString()
        blobTokenTime = System.currentTimeMillis()
        val token = JSONObject.quote(blobToken)
        val js = "(async function(){var t=$token,u=${JSONObject.quote(blobUrl)};" +
            "function send(o){o.t=t;$BLOB_BRIDGE.postMessage(JSON.stringify(o));}" +
            "try{var a=document.querySelector('a[href='+JSON.stringify(u)+']');var n=(a&&a.download)||'';" +
            "var b=await (await fetch(u)).blob();if(b.size>$MAX_BLOB_BYTES){send({e:'too large'});return;}" +
            "var r=new FileReader();r.onload=function(){send({n:n,m:b.type,d:r.result});};" +
            "r.onerror=function(){send({e:'unreadable'});};r.readAsDataURL(b);" +
            "}catch(x){send({e:String(x)});}})()"
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) { toast("This download can't be saved by the browser"); return }
        wv.evaluateJavascript(js, null)
    }

    private fun onBlobMessage(data: String) {
        val o = runCatching { JSONObject(data) }.getOrNull() ?: return
        if (blobToken.isEmpty() || o.optString("t") != blobToken || System.currentTimeMillis() - blobTokenTime > 120_000) return
        blobToken = ""
        if (o.has("e")) { toast("Could not save this download (${o.optString("e")})"); return }
        val dataUrl = o.optString("d")
        val mime = o.optString("m").ifBlank { null }
        val name = o.optString("n").replace(Regex("[\\/:*?\"<>|]+"), "_").ifBlank { fallbackFileName(mime) }
        saveDataUrl(dataUrl, name, mime)
    }

    /** Writes [bytes] into the public Downloads folder, then offers to open the file. Runs on [io]. */
    private fun saveBytes(bytes: ByteArray, name: String, mime: String) {
        val uri = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                }
                val u = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("no uri")
                contentResolver.openOutputStream(u)!!.use { it.write(bytes) }
                u
            } else {
                val f = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name)
                f.writeBytes(bytes)
                FileProvider.getUriForFile(this, "$packageName.files", f)
            }
        }.getOrNull()
        main.post {
            if (uri == null) toast("Could not save $name")
            else showActionBar("Saved $name to Downloads", "Open") { openFile(uri, mime) }
        }
    }

    /** A short message with one action along the bottom of the screen (like a snackbar); hides itself. */
    private fun showActionBar(text: String, action: String, onAction: () -> Unit) {
        actionBar?.let { root.removeView(it) }
        val pad = (12 * density).toInt()
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@BrowserActivity, R.color.menu_bg))
            setPadding(pad + pad / 2, pad / 3, pad / 2, pad / 3)
            elevation = 8 * density
        }
        val dismiss = { if (actionBar === bar) { root.removeView(bar); actionBar = null } }
        bar.addView(TextView(this).apply {
            this.text = text
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setTextColor(ContextCompat.getColor(this@BrowserActivity, R.color.menu_icon))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(TextView(this).apply {
            this.text = action.uppercase()
            setPadding(pad, pad, pad, pad)
            setTextColor(ContextCompat.getColor(this@BrowserActivity, R.color.accent))
            setOnClickListener { onAction(); dismiss() }
        })
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
            .apply { bottomMargin = column.paddingBottom + (if (bottomMenu.isVisible) bottomMenu.height else 0) })
        actionBar = bar
        main.postDelayed({ dismiss() }, 8000)
    }

    /** Android's print dialog for the page, which also offers "Save as PDF". */
    private fun printPage() {
        hideBottomMenu()
        val tab = tabs.current ?: return
        val wv = tab.webView ?: return
        val name = tab.label.take(60).ifBlank { "page" }
        getSystemService(PrintManager::class.java).print(name, wv.createPrintDocumentAdapter(name), PrintAttributes.Builder().build())
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
            runIo {
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

    /**
     * Hands non-web links (tel:, mailto:, intent:, market: …), optionally YouTube, and (with "Open links in apps")
     * tapped links to other sites to other apps. Returns true if handled. [tapped] is false for navigations the
     * page started on its own; [fromUrl] is the page the link is on (null for typed addresses and bookmarks).
     */
    private fun openExternally(url: String, tapped: Boolean = true, fromUrl: String? = null): Boolean {
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme == "http" || scheme == "https") {
            val host = UrlUtils.host(url)
            if (prefs.forceYouTubeApp && (host == "youtube.com" || host == "m.youtube.com" || host == "youtu.be")) {
                return tryStart(Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.youtube"))
            }
            return prefs.openLinksInApps && tapped && fromUrl != null && fromUrl.startsWith("http") &&
                !UrlUtils.sameSite(fromUrl, url) && openInApp(uri)
        }
        if (scheme in setOf("about", "data", "javascript", "blob", "file", "content")) return false
        if (!tapped) {
            // ad and tracking redirects like to jump into the Play Store without a tap: ask first
            AlertDialog.Builder(this).setTitle("Open another app?")
                .setMessage("${UrlUtils.host(fromUrl).ifEmpty { "This page" }} wants to open a $scheme: link in another app.")
                .setPositiveButton("Open") { _, _ -> launchNonWebLink(url, scheme) }
                .setNegativeButton("Cancel", null).show()
            return true
        }
        launchNonWebLink(url, scheme)
        return true
    }

    private fun launchNonWebLink(url: String, scheme: String) {
        if (scheme == "intent") {
            val intent = runCatching { Intent.parseUri(url, Intent.URI_INTENT_SCHEME) }.getOrNull() ?: return
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            intent.component = null
            intent.selector = null
            if (tryStart(intent)) return
            val fallback = intent.getStringExtra("browser_fallback_url")
            when {
                fallback != null && fallback.startsWith("http") -> navigate(fallback)
                intent.`package` != null -> if (!tryStart(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${intent.`package`}"))))
                    navigate("https://play.google.com/store/apps/details?id=${intent.`package`}")
                else -> toast("No app can open this link")
            }
            return
        }
        if (!tryStart(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))) toast("No app can open $scheme: links")
    }

    /** Opens a web link in an installed app that handles it (never a browser). False if there is none. */
    private fun openInApp(uri: Uri): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return tryStart(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER))
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
            tabs.exists(tab?.openerId) -> tabs.close(tabs.currentIndex, returnToOpener = true)
            tab?.openedExternally == true && tabs.tabs.size > 1 -> { closeTab(tabs.currentIndex); moveTaskToBack(true) }
            else -> moveTaskToBack(true)
        }
    }

    private fun exitBrowser() {
        if (prefs.rememberTabs && tabs.tabs.size > 1) saveTabs() else { prefs.savedTabs = ""; tabHistoryDir.listFiles()?.forEach { it.delete() } }
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

    /** Runs [task] on the background thread. WebView callbacks can still arrive after onDestroy shut it down: skip them then. */
    private fun runIo(task: () -> Unit) {
        try { io.execute(task) } catch (e: java.util.concurrent.RejectedExecutionException) { }
    }

    // ---------------------------------------------------------------- WebView clients

    private inner class PageClient(private val tab: Tab) : WebViewClient() {
        private var reflowPending = false

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            val url = request.url.toString()
            if (openExternally(url, tapped = request.hasGesture(), fromUrl = tab.url)) return true
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
                runIo { db.recordVisit(url, t) }
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
            detachPage(view)
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
            if (!haltHistory && prefs.saveHistory && !title.isNullOrBlank()) runIo { db.updateTitle(url, title) }
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) { callback.onCustomViewHidden(); return }
            customView = view
            customViewCallback = callback
            fullscreenContainer.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (customView === view && pipFitListener == null) updatePictureInPicture() }
            fullscreenContainer.isVisible = true
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            updatePictureInPicture()
            rotateForFullscreenVideo()
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
            pipFitListener?.let { fullscreenContainer.removeOnLayoutChangeListener(it) }
            pipFitListener = null
            fullscreenVideoRatio = 0f
            customViewCallback = null
            updatePictureInPicture()
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            if (!prefs.keepScreenOn) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (!isFullscreen) WindowInsetsControllerCompat(window, root).show(WindowInsetsCompat.Type.systemBars())
        }

        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
            pendingFileCallback?.onReceiveValue(null)
            pendingFileCallback = callback
            val mimes = acceptedMimeTypes(params.acceptTypes)
            val photo = mimes.isEmpty() || mimes.any { it.startsWith("image/") }
            val video = mimes.isEmpty() || mimes.any { it.startsWith("video/") }
            val camera = (photo || video) && packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
            val granted = ContextCompat.checkSelfPermission(this@BrowserActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            val launch = { cameraOk: Boolean -> launchFileChooser(params, mimes, cameraOk && photo, cameraOk && video) }
            when {
                !camera -> launch(false)
                granted -> launch(true)
                cameraAsked -> launch(false)
                else -> { cameraAsked = true; pendingCameraChoice = launch; cameraPermission.launch(Manifest.permission.CAMERA) }
            }
            return true
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
            if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID in request.resources) { protectedMediaRequest(request); return }
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
                val newTab = tabs.newTabForWindow(tab)
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

    /** Page accept types (image MIME wildcards, ".pdf", …) as MIME types; empty means any file (also when one is unknown). */
    private fun acceptedMimeTypes(accept: Array<String>?): List<String> {
        val out = mutableListOf<String>()
        for (raw in accept.orEmpty().flatMap { it.split(',') }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }) {
            out += if (raw.startsWith(".")) MimeTypeMap.getSingleton().getMimeTypeFromExtension(raw.substring(1)) ?: return emptyList() else raw
        }
        return out.distinct()
    }

    private fun launchFileChooser(params: WebChromeClient.FileChooserParams, mimes: List<String>, photo: Boolean, video: Boolean) {
        val pick = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).apply {
            type = mimes.singleOrNull() ?: "*/*"
            if (mimes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimes.toTypedArray())
            if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        val captures = mutableListOf<Intent>()
        if (photo) {
            val dir = File(cacheDir, "uploads").apply { mkdirs() }
            dir.listFiles()?.forEach { if (it.lastModified() < System.currentTimeMillis() - 86_400_000) it.delete() }
            val file = File(dir, "photo_${System.currentTimeMillis()}.jpg").also { cameraFile = it }
            captures += Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                .putExtra(MediaStore.EXTRA_OUTPUT, FileProvider.getUriForFile(this, "$packageName.files", file))
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (video) captures += Intent(MediaStore.ACTION_VIDEO_CAPTURE)
        val intent = if (params.isCaptureEnabled && captures.isNotEmpty()) {
            captures.first() // capture="…": straight to the camera (photo when images are accepted, else video)
        } else {
            Intent.createChooser(pick, null).apply { if (captures.isNotEmpty()) putExtra(Intent.EXTRA_INITIAL_INTENTS, captures.toTypedArray()) }
        }
        try {
            fileChooser.launch(intent)
        } catch (e: ActivityNotFoundException) {
            pendingFileCallback?.onReceiveValue(null)
            pendingFileCallback = null
        }
    }

    /** DRM playback (Spotify, Netflix-style players) needs a device identifier: ask once per site, or always allow. */
    private fun protectedMediaRequest(request: PermissionRequest) {
        val drm = arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)
        val host = UrlUtils.host(request.origin.toString())
        if (host in drmAllowedHosts || UrlUtils.matchesAny(host, db.sites(BrowserDb.SiteList.DRM_ALLOW))) { request.grant(drm); return }
        AlertDialog.Builder(this).setTitle("Play protected content?")
            .setMessage("$host wants to play copy-protected (DRM) media. This lets the site identify this device.")
            .setPositiveButton("Allow") { _, _ -> drmAllowedHosts += host; request.grant(drm) }
            .setNeutralButton("Always allow") { _, _ -> db.setSite(BrowserDb.SiteList.DRM_ALLOW, host, true); request.grant(drm) }
            .setNegativeButton("Block") { _, _ -> request.deny() }
            .setOnCancelListener { request.deny() }
            .show()
    }

    companion object {
        private const val MENU_PASTE_GO = 0x5047
        /** Name of the page-side object that posts blob: downloads back (see [fetchBlob]). */
        private const val BLOB_BRIDGE = "__curmudgeonDownload"
        private const val MAX_BLOB_BYTES = 64 * 1024 * 1024
    }
}
