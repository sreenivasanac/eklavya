package com.pragnyalabs.eklavya.screen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Replays real 24GO screens (dumped from a phone, personal details replaced) through [ScreenText]. */
class ScreenTextTest {
    private fun screen(name: String) = ScreenText.of(UiAutomatorXml.load("/24go/$name.xml"))

    @Test
    fun `home screen - every clickable element gets a label and the tabs are reachable`() {
        val s = screen("home")
        val unlabeled = s.elements.filter { it.node.clickable && it.label.isEmpty() }
        assertTrue(unlabeled.isEmpty(), "clickable elements without a label: ${unlabeled.map { it.index }}")
        val workouts = assertNotNull(s.byLabel("Workouts"))
        assertTrue(workouts.node.clickable)
        assertTrue(s.text.startsWith("app: io.a24go.android.dev | 1080x2400"))
    }

    @Test
    fun `a button built from an icon and text views takes the text inside it as its label`() {
        val s = screen("home")
        // The card is one clickable View; "Recovery24" and "Tap to unlock" are separate text views inside it.
        val card = assertNotNull(s.elements.firstOrNull { it.label.contains("Recovery24") && it.node.clickable })
        assertTrue(card.label.contains("Tap to unlock"))
        // Text used for a label is not listed a second time as its own line.
        assertEquals(1, s.elements.count { it.label.contains("Recovery24") })
    }

    @Test
    fun `search overlay - the screen underneath is left out`() {
        val s = screen("search-open")
        val input = assertNotNull(s.byId("searchInput"))
        assertTrue(input.node.editable)
        assertNotNull(s.byLabel("Cancel"))
        // The Workouts tab is still in the accessibility tree under the full-screen search layer.
        assertNull(s.byLabel("Plans"), "bottom tab under the overlay must not be listed")
        assertTrue(s.elements.none { it.label.contains("Triceps Workout") }, "covered card must not be listed")
    }

    @Test
    fun `demo page - icon buttons without text are named from their view id`() {
        val s = screen("demo-page")
        assertEquals("fab back", assertNotNull(s.byId("fabBack")).label)
        // The toolbar's own back button sits under the floating one at the same spot: only the top one is listed.
        assertNull(s.byId("toolbarBackButton"))
        // The form tips are plain text inside a scroll container and must stay readable in full.
        assertTrue(s.text.contains("6. Press Ground Away"), s.text)
        val start = assertNotNull(s.byId("ctaPrimaryActionView"))
        assertEquals("Start Demo Exercise", start.label)
        assertTrue(start.node.clickable)
    }

    @Test
    fun `a window above hides the elements under it`() {
        val button = UiNode(listOf(0), "android.widget.Button", "Pay", null, null, "pay", Bounds(100, 1900, 500, 2000), clickable = true)
        val app = UiWindow(1, Bounds(0, 0, 1080, 2400), UiNode(emptyList(), "android.widget.FrameLayout", null, null, null, null, Bounds(0, 0, 1080, 2400), children = listOf(button)))
        val keyboard = UiWindow(2, Bounds(0, 1500, 1080, 2400), root = null, readable = false)

        assertNotNull(ScreenText.of(Screen("app", 1080, 2400, listOf(app))).byId("pay"))
        val covered = ScreenText.of(Screen("app", 1080, 2400, listOf(app, keyboard), keyboardOpen = true))
        assertNull(covered.byId("pay"))
        assertTrue(covered.text.contains("keyboard open"))
    }
}
