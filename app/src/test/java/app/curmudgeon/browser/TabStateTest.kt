// SPDX-License-Identifier: GPL-3.0-only
package app.curmudgeon.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TabStateTest {
    @Test fun savedTabsParse() {
        val (tabs, current) = TabManager.parseSaved("""{"current":1,"tabs":[{"url":"https://a.com","title":"A"},{"url":"about:home","title":""}]}""")
        assertEquals(listOf("https://a.com" to "A", "about:home" to ""), tabs)
        assertEquals(1, current)
    }

    @Test fun badSavedStateIsEmpty() {
        assertTrue(TabManager.parseSaved("").first.isEmpty())
        assertTrue(TabManager.parseSaved("{not json").first.isEmpty())
        assertEquals(0, TabManager.parseSaved("""{"current":9,"tabs":[{"url":"https://a.com"}]}""").second)
    }

    @Test fun gestureDefaultsMatchNaked() {
        assertEquals(GestureAction.ZOOM_OUT, Prefs.GESTURE_DEFAULTS[Prefs.DOUBLE_TAP_LEFT])
        assertEquals(GestureAction.ZOOM_IN, Prefs.GESTURE_DEFAULTS[Prefs.DOUBLE_TAP_RIGHT])
        assertEquals(GestureAction.NONE, GestureAction.fromName("NOT_A_THING", GestureAction.NONE))
        assertEquals(GestureAction.entries.size, GestureAction.names.distinct().size)
    }
}
