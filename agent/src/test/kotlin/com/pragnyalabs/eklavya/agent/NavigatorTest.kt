package com.pragnyalabs.eklavya.agent

import com.pragnyalabs.eklavya.screen.Element
import com.pragnyalabs.eklavya.screen.ScreenControl
import com.pragnyalabs.eklavya.screen.ScreenText
import com.pragnyalabs.eklavya.screen.UiAutomatorXml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val APP = "io.a24go.android.dev"

/** Replays recorded 24GO screens: every successful action moves on to the next one. */
private class Replay(vararg names: String) : ScreenControl {
    private val pages = names.map { ScreenText.of(UiAutomatorXml.load("/24go/$it.xml")) }
    private var at = 0
    val actions = ArrayList<String>()

    override fun read(onlyPackage: String?) = pages[minOf(at, pages.lastIndex)]
    private fun did(what: String): Boolean { actions.add(what); at++; return true }
    override fun tap(e: Element) = did("tap ${e.node.viewId ?: e.label}")
    override fun setText(e: Element, text: String) = did("type $text")
    override fun submit(e: Element) = did("submit")
    override fun scroll(e: Element, forward: Boolean) = did("scroll")
    override fun back() = did("back")
    override fun openApp(packageName: String) = true
}

class NavigatorTest {
    private val goal = Goal(APP, "Play the demo video for the exercise \"squat\"", text = "squat")

    /** Picks by a rule over the offered options, like a model would; fails the test if nothing matches. */
    private fun scripted(vararg picks: (DecisionRequest) -> String?) = object : Decider {
        var step = 0
        override fun decide(request: DecisionRequest) =
            Decision(picks[step++](request) ?: error("no option matched at step $step: ${request.options}"), 0.9, 100, 0.001)
    }

    private fun find(text: String): (DecisionRequest) -> String? = { r -> r.options.entries.firstOrNull { text in it.value }?.key }

    @Test
    fun `carries out the chosen actions in order and stops at done`() {
        // search-no-results is the search screen with text typed and not yet submitted.
        val phone = Replay("home", "workouts-tab", "search-open", "search-no-results", "search-results-demos", "demo-page", "player")
        val decider = scripted(
            find("\"Workouts\""), find("tap [1]"), find("type \"squat\""), find("press the keyboard"),
            find("Pistol Squat"), find("Start Demo Exercise"), { Navigator.DONE },
        )
        val outcome = Navigator(phone, decider).run(goal)

        assertTrue(outcome.done, outcome.reason)
        assertEquals(listOf("tap Workouts", "tap searchBox", "type squat", "submit", "tap search_item_layout", "tap ctaPrimaryActionView"), phone.actions)
        assertEquals(700, outcome.inputTokens)
    }

    @Test
    fun `stops without acting when the model is unsure`() {
        val phone = Replay("home")
        val unsure = Decider { r -> Decision(r.options.keys.first { it.startsWith("tap_") }, 0.2) }
        val outcome = Navigator(phone, unsure).run(goal)
        assertFalse(outcome.done)
        assertTrue(phone.actions.isEmpty())
        assertTrue("unsure" in outcome.reason, outcome.reason)
    }

    @Test
    fun `stops when the same action on the same screen changes nothing`() {
        val stuckPhone = object : ScreenControl by Replay("home") {
            override fun back() = true // "succeeds" but the screen stays the same
        }
        val outcome = Navigator(stuckPhone, { Decision(Navigator.BACK, 0.9) }).run(goal)
        assertFalse(outcome.done)
        assertTrue("circles" in outcome.reason, outcome.reason)
    }

    @Test
    fun `elements that unlock a paid feature are never offered`() {
        val home = ScreenText.of(UiAutomatorXml.load("/24go/home.xml"))
        val options = Navigator.optionsFor(home, goal)
        // The home screen has two "... Tap to unlock" cards for paid add-ons.
        assertTrue(home.elements.any { "Tap to unlock" in it.label && it.node.clickable })
        assertTrue(options.none { "Tap to unlock" in it.description })
        assertTrue(options.any { "\"Workouts\"" in it.description })
        assertEquals(listOf(Navigator.BACK, Navigator.DONE, Navigator.STUCK), options.takeLast(3).map { it.id })
    }

    @Test
    fun `a screen that already looks finished cannot end a request that has done nothing`() {
        // The player left on screen by an earlier request says nothing about which exercise it shows.
        val phone = Replay("player", "player", "demo-page")
        val noDone: (DecisionRequest) -> Unit = { r -> check(Navigator.DONE !in r.options) { "done was offered without the exercise having been acted on" } }
        // Tapping a player control is an action, but not one that involves the exercise asked for.
        val decider = scripted({ r -> noDone(r); find("Show player controls")(r) }, { r -> noDone(r); Navigator.BACK }, { Navigator.STUCK })
        val outcome = Navigator(phone, decider).run(goal)
        assertFalse(outcome.done)
        assertEquals(listOf("tap player_view", "back"), phone.actions)
    }

    @Test
    fun `with a home marker the navigator backs out to the main screen before asking the model`() {
        val phone = Replay("player", "demo-page", "search-results-pistol", "workouts-tab")
        var firstScreen = ""
        val decider = Decider { r -> firstScreen = r.screen; Decision(Navigator.STUCK, 0.9) }
        Navigator(phone, decider).run(Goal(APP, "Play the demo video for the exercise \"squat\"", "squat", home = "Workouts"))

        assertEquals(listOf("back", "back", "back"), phone.actions)
        assertTrue("#searchBox" in firstScreen, "the model should first be asked on the Workouts tab")
    }

    @Test
    fun `typing is offered only when the goal has text and only for editable fields`() {
        val search = ScreenText.of(UiAutomatorXml.load("/24go/search-open.xml"))
        assertEquals(1, Navigator.optionsFor(search, goal).count { it.id.startsWith("type_") })
        // The field is empty (it shows only its hint), so pressing search is not offered yet.
        assertEquals(0, Navigator.optionsFor(search, goal).count { it.id.startsWith("submit_") })
        val typed = ScreenText.of(UiAutomatorXml.load("/24go/search-no-results.xml"))
        assertEquals(1, Navigator.optionsFor(typed, goal).count { it.id.startsWith("submit_") })
        assertEquals(0, Navigator.optionsFor(search, Goal(APP, "open the Workouts tab")).count { it.id.startsWith("type_") })
    }
}
