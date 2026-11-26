package com.pragnyalabs.eklavya

import com.pragnyalabs.eklavya.a11y.ScreenDriver
import com.pragnyalabs.eklavya.screen.Element

/**
 * A fixed route through the 24GO app: search for an exercise and play its demo video.
 *
 * It proves the screen layer on a real app. The view ids and labels below are 24GO's own (version 1.94.0)
 * and will break when 24GO changes its layout; a decision model choosing each step is meant to replace this.
 */
class TwentyFourGo(private val driver: ScreenDriver, private val log: (String) -> Unit) {

    /** Returns true once the demo video player is on screen. */
    fun playDemo(exercise: String): Boolean {
        if (!driver.openApp(GymApps.TWENTY_FOUR_GO)) return fail("24GO is not installed")

        // Wherever 24GO was left (a video, a detail page, an open search), go back until its tab bar shows.
        var tab: Element? = null
        repeat(MAX_BACKS) {
            if (tab == null) {
                val screen = driver.read()
                tab = screen.takeIf { it.packageName == GymApps.TWENTY_FOUR_GO }?.byLabel(TAB_WORKOUTS)
                val discard = screen.byLabel(DISCARD)
                when {
                    tab != null -> Unit
                    // Leaving a workout that was opened but never started asks for confirmation. Only that case
                    // is confirmed here; a workout in progress is the user's, and the route stops instead.
                    discard != null && screen.elements.any { NOT_STARTED in it.label } -> driver.tap(discard)
                    discard != null -> return fail("24GO is asking whether to discard a workout in progress")
                    else -> driver.back()
                }
            }
        }
        if (!driver.tap(tab ?: return fail("could not reach 24GO's tab bar"))) return fail("could not open the Workouts tab")
        log("opened the Workouts tab")

        if (!driver.tap(driver.waitFor { it.byId(ID_SEARCH_BOX) } ?: return fail("no search box on the Workouts tab"))) return fail("could not open search")
        val input = driver.waitFor { it.byId(ID_SEARCH_INPUT) } ?: return fail("search did not open")
        if (!driver.setText(input, exercise)) return fail("could not type into search")
        // 24GO only searches when the keyboard's search key is pressed.
        val typed = driver.read().byId(ID_SEARCH_INPUT) ?: return fail("search field went away")
        if (!driver.submit(typed)) return fail("could not submit the search")
        log("searched for \"$exercise\"")

        // Some one-minute results are short workouts, and only their page says so; try the next result then.
        val tried = HashSet<String>()
        repeat(MAX_CANDIDATES) {
            val demo = findDemo(exercise, tried) ?: return fail("no demo found for \"$exercise\"")
            tried += demo.label
            log("opening \"${demo.label}\"")
            if (!driver.tap(demo)) return fail("could not open the result")
            val start = driver.waitFor { it.byId(ID_START) } ?: return fail("no start button on the page")
            if (start.label == START_DEMO) {
                if (!driver.tap(start)) return fail("could not start the demo")
                // The player is a new page that loads its video from the network.
                val playing = driver.waitFor { it.byId(ID_PLAYER) } != null
                log(if (playing) "demo video is playing" else "the player did not appear")
                return playing
            }
            log("that is a workout, not a demo; going back")
            driver.back()
        }
        return fail("no demo among the first $MAX_CANDIDATES results for \"$exercise\"")
    }

    /**
     * Results list workouts first and demos after them. A demo is a result lasting one minute; prefer the one
     * whose name is exactly the request, then one containing all its words, then the first demo.
     */
    private fun findDemo(exercise: String, tried: Set<String>): Element? {
        val words = exercise.lowercase().split(' ').filter { it.isNotBlank() }
        repeat(MAX_PAGES) {
            val screen = driver.read()
            val demos = screen.elements.filter { it.node.viewId == ID_RESULT && it.label.endsWith(DEMO_SUFFIX) && it.label !in tried }
            val named = demos.map { it to it.label.removeSuffix(DEMO_SUFFIX).trim().lowercase() }
            (named.firstOrNull { it.second == exercise.lowercase() }
                ?: named.firstOrNull { (_, name) -> words.all(name::contains) }
                ?: named.firstOrNull())?.let { return it.first }
            val list = screen.elements.firstOrNull { it.node.scrollable } ?: return null
            if (!driver.scroll(list, forward = true)) return null
        }
        return null
    }

    private fun fail(reason: String): Boolean {
        log("stopped: $reason")
        return false
    }

    private companion object {
        const val TAB_WORKOUTS = "Workouts"
        const val DISCARD = "Discard"
        const val NOT_STARTED = "hasn't started"
        const val ID_SEARCH_BOX = "searchBox"
        const val ID_SEARCH_INPUT = "searchInput"
        const val ID_RESULT = "search_item_layout"
        const val ID_START = "ctaPrimaryActionView"
        const val ID_PLAYER = "player_view"
        const val START_DEMO = "Start Demo Exercise"
        const val MAX_CANDIDATES = 4
        /** How a demo ends in the results list. The leading space keeps "21 minutes" workouts out. */
        const val DEMO_SUFFIX = " 1 minutes"
        const val MAX_BACKS = 6
        const val MAX_PAGES = 15
    }
}
