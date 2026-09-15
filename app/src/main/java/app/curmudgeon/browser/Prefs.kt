// SPDX-License-Identifier: GPL-3.0-only
package app.curmudgeon.browser

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/** Every preference key used by the app, with typed accessors and defaults in one place. */
class Prefs(context: Context) {
    val sp: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    private fun bool(key: String, def: Boolean) = sp.getBoolean(key, def)
    private fun str(key: String, def: String) = sp.getString(key, def) ?: def
    private fun int(key: String, def: Int) = str(key, def.toString()).toIntOrNull() ?: def

    // --- settings menu ---
    val advancedSettings get() = bool(ADVANCED_SETTINGS, false)

    // --- content ---
    val javascript get() = bool(JAVASCRIPT, true)
    /** 0 = on, 1 = only over Wi-Fi, 2 = off */
    val imagePolicy get() = int(IMAGE_POLICY, 0)
    val zoomReflowsText get() = bool(ZOOM_REFLOWS_TEXT, false)
    val wideViewport get() = bool(WIDE_VIEWPORT, true)
    val openInOverview get() = bool(OPEN_IN_OVERVIEW, false)
    val textZoom get() = sp.getInt(TEXT_ZOOM, 100)
    val textAutoSize get() = bool(TEXT_AUTO_SIZE, false)
    val zoomButtons get() = bool(ZOOM_BUTTONS, false)
    val forceDarkPages get() = bool(FORCE_DARK_PAGES, false)

    // --- search ---
    val searchSuggestions get() = bool(SEARCH_SUGGESTIONS, false)
    /** 0 = URL keyboard, 1 = auto space & mic, 2 = auto space, mic & keyboard suggestions */
    val urlBarKeyboard get() = int(URL_BAR_KEYBOARD, 0)
    val searchUrl get() = str(SEARCH_URL, DEFAULT_SEARCH_URL).ifBlank { DEFAULT_SEARCH_URL }
    val searchEngines get() = str(SEARCH_ENGINES, "").ifBlank { DEFAULT_SEARCH_ENGINES }
    val suggestBookmarksFirst get() = bool(SUGGEST_BOOKMARKS_FIRST, true)

    // --- tabs ---
    val rememberTabs get() = bool(REMEMBER_TABS, true)
    val tabRows get() = int(TAB_ROWS, 2)
    /** 0 = touch the current tab, 1 = long press, 2 = vertical swipe */
    val tabCloseMethod get() = int(TAB_CLOSE_METHOD, 0)
    val tabCacheThreshold get() = int(TAB_CACHE_THRESHOLD, 6)
    /** 0 = current tab, 1 = new tab & keep sidebar open, 2 = new tab */
    val bookmarkOpenBehavior get() = int(BOOKMARK_OPEN_BEHAVIOR, 0)
    val bottomTabsAndUrl get() = bool(BOTTOM_TABS_AND_URL, false)
    val tabWidthDp get() = sp.getInt(TAB_WIDTH, 110)

    // --- home page ---
    val homePage get() = str(HOME_PAGE, HOME_URL).ifBlank { HOME_URL }
    val homeSearchBox get() = bool(HOME_SEARCH_BOX, true)
    val homeBookmarks get() = bool(HOME_BOOKMARKS, true)
    val homeHistory get() = bool(HOME_HISTORY, true)
    val keyboardOnNewTab get() = bool(KEYBOARD_ON_NEW_TAB, true)
    val homeHtml get() = str(HOME_HTML, "")

    // --- gestures and menus ---
    /** GestureAction name for volume keys: see [VolumeAction] */
    val volumeAction get() = int(VOLUME_ACTION, 0)
    /** 0 = context menu, 1 = open in new tab, 2 = open in new tab & view */
    val linkLongPress get() = int(LINK_LONG_PRESS, 0)
    val longPressSelectsText get() = bool(LONG_PRESS_SELECTS_TEXT, true)
    val doubleTapOverviewZoom get() = bool(DOUBLE_TAP_ZOOM, true)
    val doubleTapTopMenu get() = bool(DOUBLE_TAP_TOP_MENU, false)
    val doubleTapBottomMenu get() = bool(DOUBLE_TAP_BOTTOM_MENU, false)
    val doubleTapFullscreen get() = bool(DOUBLE_TAP_FULLSCREEN, false)
    val doubleTapReflow get() = bool(DOUBLE_TAP_REFLOW, false)
    /** see [MenuGesture] */
    val topMenuGesture get() = int(TOP_MENU_GESTURE, MenuGesture.ONCE_AND_EDGE)
    val sideMenuGesture get() = int(SIDE_MENU_GESTURE, MenuGesture.FROM_EDGE)
    val bottomMenuGesture get() = int(BOTTOM_MENU_GESTURE, MenuGesture.FROM_EDGE)
    val topMenuOnLoad get() = bool(TOP_MENU_ON_LOAD, true)
    val hideMenusOnScroll get() = bool(HIDE_MENUS_ON_SCROLL, true)
    fun gesture(key: String): GestureAction = GestureAction.fromName(sp.getString(key, null), GESTURE_DEFAULTS[key] ?: GestureAction.NONE)

    // --- data ---
    val clearHistoryOnExit get() = bool(CLEAR_HISTORY_ON_EXIT, false)
    val clearCacheOnExit get() = bool(CLEAR_CACHE_ON_EXIT, false)
    val clearCookiesOnExit get() = bool(CLEAR_COOKIES_ON_EXIT, false)
    val saveHistory get() = bool(SAVE_HISTORY, true)
    val saveBasicAuth get() = bool(SAVE_BASIC_AUTH, false)
    val saveCookies get() = bool(SAVE_COOKIES, true)
    val thirdPartyCookies get() = bool(THIRD_PARTY_COOKIES, false)

    // --- privacy ---
    val blockAds get() = bool(BLOCK_ADS, false)
    val blockListUrl get() = str(BLOCK_LIST_URL, DEFAULT_BLOCK_LIST_URL)
    val doNotTrack get() = bool(DO_NOT_TRACK, true)
    val safeBrowsing get() = bool(SAFE_BROWSING, true)

    // --- etc ---
    /** 0 = block, 1 = allow, 2 = prompt */
    val securityExceptions get() = int(SECURITY_EXCEPTIONS, 2)
    val allowPopups get() = bool(ALLOW_POPUPS, false)
    val systemDownloader get() = bool(SYSTEM_DOWNLOADER, true)
    val geolocation get() = bool(GEOLOCATION, false)
    val backgroundLoadIntents get() = bool(BACKGROUND_LOAD_INTENTS, false)
    val keepScreenOn get() = bool(KEEP_SCREEN_ON, false)
    val forceYouTubeApp get() = bool(FORCE_YOUTUBE_APP, false)
    /** Tapped links to other sites open in their app when one is installed (off: stay on the website). */
    val openLinksInApps get() = bool(OPEN_LINKS_IN_APPS, false)
    val altShortcuts get() = bool(ALT_SHORTCUTS, false)
    val desktopUserAgent get() = str(DESKTOP_USER_AGENT, "")

    // --- night mode (page recolouring) ---
    val nightMode get() = bool(NIGHT_MODE, false)
    val nightTextColor get() = str(NIGHT_TEXT_COLOR, "C8C8C8")
    val nightLinkColor get() = str(NIGHT_LINK_COLOR, "7FB2F0")
    val nightVisitedLinkColor get() = str(NIGHT_VISITED_LINK_COLOR, "B89AF0")
    val nightImageBrightness get() = sp.getInt(NIGHT_IMAGE_BRIGHTNESS, 75)

    // --- runtime state (not shown in settings) ---
    var savedTabs: String
        get() = str(STATE_TABS, "")
        set(v) = sp.edit().putString(STATE_TABS, v).apply()

    fun set(key: String, value: Boolean) = sp.edit().putBoolean(key, value).apply()

    object MenuGesture {
        const val NONE = 4
        const val ONCE = 1
        const val TWICE = 2
        const val FROM_EDGE = 0
        const val ONCE_AND_EDGE = 3
        fun edge(v: Int) = v == FROM_EDGE || v == ONCE_AND_EDGE
        fun once(v: Int) = v == ONCE || v == ONCE_AND_EDGE
    }

    object VolumeAction {
        const val VOLUME = 0
        const val SCROLL = 1
        const val SWITCH_TAB = 2
        const val ZOOM = 3
        const val TOP_END = 4
    }

    companion object {
        const val HOME_URL = "about:home"
        const val DEFAULT_SEARCH_URL = "https://duckduckgo.com/?q="
        const val DEFAULT_SEARCH_ENGINES = "DuckDuckGo|https://duckduckgo.com/?q=\n" +
            "Google|https://www.google.com/search?q=\n" +
            "Bing|https://www.bing.com/search?q=\n" +
            "Startpage|https://www.startpage.com/do/search?q=\n" +
            "Wikipedia|https://en.wikipedia.org/w/index.php?search=\n" +
            "YouTube|https://www.youtube.com/results?search_query="
        const val DEFAULT_BLOCK_LIST_URL = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"

        const val ADVANCED_SETTINGS = "advanced_settings"
        const val JAVASCRIPT = "javascript"
        const val IMAGE_POLICY = "image_policy"
        const val ZOOM_REFLOWS_TEXT = "zoom_reflows_text"
        const val WIDE_VIEWPORT = "wide_viewport"
        const val OPEN_IN_OVERVIEW = "open_in_overview"
        const val TEXT_ZOOM = "text_zoom"
        const val TEXT_AUTO_SIZE = "text_auto_size"
        const val ZOOM_BUTTONS = "zoom_buttons"
        const val FORCE_DARK_PAGES = "force_dark_pages"
        const val SEARCH_SUGGESTIONS = "search_suggestions"
        const val URL_BAR_KEYBOARD = "url_bar_keyboard"
        const val SEARCH_URL = "search_url"
        const val SEARCH_ENGINES = "search_engines"
        const val SUGGEST_BOOKMARKS_FIRST = "suggest_bookmarks_first"
        const val REMEMBER_TABS = "remember_tabs"
        const val TAB_ROWS = "tab_rows"
        const val TAB_CLOSE_METHOD = "tab_close_method"
        const val TAB_CACHE_THRESHOLD = "tab_cache_threshold"
        const val BOOKMARK_OPEN_BEHAVIOR = "bookmark_open_behavior"
        const val BOTTOM_TABS_AND_URL = "bottom_tabs_and_url"
        const val TAB_WIDTH = "tab_width"
        const val HOME_PAGE = "home_page"
        const val HOME_SEARCH_BOX = "home_search_box"
        const val HOME_BOOKMARKS = "home_bookmarks"
        const val HOME_HISTORY = "home_history"
        const val KEYBOARD_ON_NEW_TAB = "keyboard_on_new_tab"
        const val HOME_HTML = "home_html"
        const val VOLUME_ACTION = "volume_action"
        const val LINK_LONG_PRESS = "link_long_press"
        const val LONG_PRESS_SELECTS_TEXT = "long_press_selects_text"
        const val DOUBLE_TAP_ZOOM = "double_tap_zoom"
        const val DOUBLE_TAP_TOP_MENU = "double_tap_top_menu"
        const val DOUBLE_TAP_BOTTOM_MENU = "double_tap_bottom_menu"
        const val DOUBLE_TAP_FULLSCREEN = "double_tap_fullscreen"
        const val DOUBLE_TAP_REFLOW = "double_tap_reflow"
        const val TOP_MENU_GESTURE = "top_menu_gesture"
        const val SIDE_MENU_GESTURE = "side_menu_gesture"
        const val BOTTOM_MENU_GESTURE = "bottom_menu_gesture"
        const val TOP_MENU_ON_LOAD = "top_menu_on_load"
        const val HIDE_MENUS_ON_SCROLL = "hide_menus_on_scroll"
        const val TWO_FINGER_UP = "two_finger_up"
        const val TWO_FINGER_DOWN = "two_finger_down"
        const val TWO_FINGER_LEFT = "two_finger_left"
        const val TWO_FINGER_RIGHT = "two_finger_right"
        const val DOUBLE_TAP_UP = "double_tap_up"
        const val DOUBLE_TAP_DOWN = "double_tap_down"
        const val DOUBLE_TAP_LEFT = "double_tap_left"
        const val DOUBLE_TAP_RIGHT = "double_tap_right"
        const val BOTTOM_SWIPE_LEFT = "bottom_swipe_left"
        const val BOTTOM_SWIPE_RIGHT = "bottom_swipe_right"
        const val BOTTOM_LEFT_CORNER_ACROSS = "bottom_left_corner_across"
        const val BOTTOM_RIGHT_CORNER_ACROSS = "bottom_right_corner_across"
        const val BOTTOM_LEFT_CORNER_UP = "bottom_left_corner_up"
        const val BOTTOM_RIGHT_CORNER_UP = "bottom_right_corner_up"
        const val BOTTOM_LEFT_CORNER_TAP = "bottom_left_corner_tap"
        const val BOTTOM_RIGHT_CORNER_TAP = "bottom_right_corner_tap"
        const val CLEAR_HISTORY_ON_EXIT = "clear_history_on_exit"
        const val CLEAR_CACHE_ON_EXIT = "clear_cache_on_exit"
        const val CLEAR_COOKIES_ON_EXIT = "clear_cookies_on_exit"
        const val SAVE_HISTORY = "save_history"
        const val SAVE_BASIC_AUTH = "save_basic_auth"
        const val SAVE_COOKIES = "save_cookies"
        const val THIRD_PARTY_COOKIES = "third_party_cookies"
        const val BLOCK_ADS = "block_ads"
        const val BLOCK_LIST_URL = "block_list_url"
        const val DO_NOT_TRACK = "do_not_track"
        const val SAFE_BROWSING = "safe_browsing"
        const val SECURITY_EXCEPTIONS = "security_exceptions"
        const val ALLOW_POPUPS = "allow_popups"
        const val SYSTEM_DOWNLOADER = "system_downloader"
        const val GEOLOCATION = "geolocation"
        const val BACKGROUND_LOAD_INTENTS = "background_load_intents"
        const val KEEP_SCREEN_ON = "keep_screen_on"
        const val FORCE_YOUTUBE_APP = "force_youtube_app"
        const val OPEN_LINKS_IN_APPS = "open_links_in_apps"
        const val ALT_SHORTCUTS = "alt_shortcuts"
        const val DESKTOP_USER_AGENT = "desktop_user_agent"
        const val NIGHT_MODE = "night_mode"
        const val NIGHT_TEXT_COLOR = "night_text_color"
        const val NIGHT_LINK_COLOR = "night_link_color"
        const val NIGHT_VISITED_LINK_COLOR = "night_visited_link_color"
        const val NIGHT_IMAGE_BRIGHTNESS = "night_image_brightness"
        const val STATE_TABS = "state_tabs"

        /** Defaults match Naked Browser's: double tap & drag left/right zooms, corners show menus. */
        val GESTURE_DEFAULTS = mapOf(
            DOUBLE_TAP_LEFT to GestureAction.ZOOM_OUT,
            DOUBLE_TAP_RIGHT to GestureAction.ZOOM_IN,
            BOTTOM_LEFT_CORNER_ACROSS to GestureAction.SHOW_MENUS,
            BOTTOM_RIGHT_CORNER_ACROSS to GestureAction.SHOW_MENUS,
            BOTTOM_LEFT_CORNER_UP to GestureAction.SHOW_MENUS,
            BOTTOM_RIGHT_CORNER_UP to GestureAction.SHOW_MENUS,
        )
    }
}
