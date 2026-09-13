// SPDX-License-Identifier: GPL-3.0-only
package app.curmudgeon.browser

/** Everything a gesture, corner hotspot or key can be bound to. Stored by [name] in preferences. */
enum class GestureAction(val label: String) {
    NONE("none"),
    ZOOM_IN("zoom in"),
    ZOOM_OUT("zoom out"),
    PAGE_TOP("page top"),
    PAGE_END("page end"),
    NEW_TAB("new tab"),
    CLOSE_TAB("close tab"),
    SWITCH_TAB_RIGHT("switch tab right"),
    SWITCH_TAB_LEFT("switch tab left"),
    BACK("navigate back"),
    FORWARD("navigate forward"),
    PAGE_UP("scroll one page up"),
    PAGE_DOWN("scroll one page down"),
    SHOW_MENUS("show menus"),
    SHOW_URL_AND_KEYBOARD("show URL & keyboard"),
    REFLOW_TEXT("re-flow text"),
    SHARE_URL("share URL"),
    FIND_ON_PAGE("find on page"),
    STOP_RELOAD("stop/reload"),
    UNDO_CLOSE_TAB("undo close tab"),
    SHOW_BOOKMARKS("show bookmarks"),
    GO_HOME("go home page"),
    TOGGLE_FULLSCREEN("toggle fullscreen"),
    TOGGLE_NIGHT_MODE("toggle night mode"),
    TOGGLE_DESKTOP_MODE("toggle desktop site"),
    COPY_URL("copy URL");

    companion object {
        fun fromName(name: String?, default: GestureAction) = entries.firstOrNull { it.name == name } ?: default
        val labels get() = entries.map { it.label }.toTypedArray()
        val names get() = entries.map { it.name }.toTypedArray()
    }
}
