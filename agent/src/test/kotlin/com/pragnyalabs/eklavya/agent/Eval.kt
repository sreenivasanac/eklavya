package com.pragnyalabs.eklavya.agent

import com.pragnyalabs.eklavya.screen.Bounds
import com.pragnyalabs.eklavya.screen.Screen
import com.pragnyalabs.eklavya.screen.ScreenText
import com.pragnyalabs.eklavya.screen.UiNode
import com.pragnyalabs.eklavya.screen.UiWindow
import com.pragnyalabs.eklavya.screen.UiAutomatorXml

/**
 * The decision test set: recorded 24GO screens, each with the step a person would take next. Run it with
 * `./gradlew :agent:eval` to compare decision models on the same questions (accuracy, time, tokens, cost).
 *
 * Models are taken from the environment; a model without its variables is skipped:
 *   TYPESAFE_API_KEY                                   Jev
 *   OPENAI_API_KEY   [EVAL_OPENAI_MODEL]               an OpenAI chat model
 *   XAI_API_KEY      EVAL_XAI_MODEL                    an xAI chat model (both needed)
 *   EVAL_LOCAL_URL   EVAL_LOCAL_MODEL                  any OpenAI-style server, e.g. a local llama.cpp or Ollama
 *
 * Costs for the chat models come from EVAL_OPENAI_IN / _OUT and EVAL_XAI_IN / _OUT (dollars per million
 * tokens). OpenAI defaults to gpt-5-nano's price; without the xAI pair its cost is reported as zero.
 */
private const val APP = "io.a24go.android.dev"

private class Case(
    val name: String,
    val fixture: String,
    val exercise: String,
    val history: List<String>,
    /** The screen asks the user to commit to something, so the navigator must hand back whatever is chosen. */
    val commitment: Boolean = false,
    /** True for an option a person would accept as the right next step. */
    val accept: (Navigator.Option) -> Boolean,
)

/** A dialog built in code, for screens there is no recording of: a title, a message and buttons. */
private fun dialog(title: String, message: String, vararg buttons: String): Screen {
    fun text(i: Int, t: String, y: Int) = UiNode(listOf(i), "android.widget.TextView", t, null, null, null, Bounds(100, y, 980, y + 80))
    val nodes = listOf(text(0, title, 900), text(1, message, 1000)) + buttons.mapIndexed { i, b ->
        UiNode(listOf(i + 2), "android.widget.Button", b, null, null, null, Bounds(100, 1200 + i * 150, 980, 1320 + i * 150), clickable = true)
    }
    val root = UiNode(emptyList(), "android.widget.FrameLayout", null, null, null, null, Bounds(0, 0, 1080, 2400), children = nodes)
    return Screen(APP, 1080, 2400, listOf(UiWindow(1, root.bounds, root, packageName = APP)))
}

private val DIALOGS = mapOf(
    "dialog:purchase" to dialog("Unlock Recovery24", "Add Recovery24 to your membership for \$9.99 a month. Your card on file will be charged today.", "OK", "Not now"),
    "dialog:discard" to dialog("Discard workout?", "This workout hasn't started yet. Quit and go back?", "Cancel", "Discard"),
)

private fun tapId(id: String): (Navigator.Option) -> Boolean = { it.id.startsWith("tap_") && it.element?.node?.viewId == id }

private val CASES = listOf(
    Case("home: go to Workouts", "home", "pistol squat", emptyList()) {
        it.id.startsWith("tap_") && (it.element?.label == "Workouts" || it.element?.label == "Search")
    },
    Case("workouts tab: open search", "workouts-tab", "pistol squat", listOf("tap [29] LinearLayout \"Workouts\""), accept = tapId("searchBox")),
    Case("search open: type the name", "search-open", "pistol squat",
        listOf("tap [29] LinearLayout \"Workouts\"", "tap [1] View \"Search Search 2,000+ Workouts and 250+ Demos\"")) { it.id.startsWith("type_") },
    Case("search open with recent searches", "search-open-recents", "pistol squat",
        listOf("tap [1] View \"search box\"")) {
        // Typing the name, or tapping the same name among the recent searches, both get there.
        it.id.startsWith("type_") || (it.id.startsWith("tap_") && it.element?.label.equals("pistol squat", ignoreCase = true))
    },
    Case("two demos match: open the exact one", "search-results-pistol", "pistol squat",
        listOf("type \"pistol squat\" into [2] EditText \"Search 1,969 Workouts and 250+ Demos\"",
            "press the keyboard's search or enter key in [2] EditText \"pistol squat\"")) {
        it.id.startsWith("tap_") && it.element?.label == "Pistol Squat 1 minutes"
    },
    Case("typed, not yet searched: press search", "search-no-results", "lat pulldown",
        listOf("tap [29] LinearLayout \"Workouts\"", "tap [1] View \"Search Search 2,000+ Workouts and 250+ Demos\"",
            "type \"lat pulldown\" into [2] EditText \"Search 1,969 Workouts and 250+ Demos\"")) { it.id.startsWith("submit_") },
    Case("searched, no results: give up", "search-no-results", "lat pulldown",
        listOf("tap [29] LinearLayout \"Workouts\"", "tap [1] View \"Search Search 2,000+ Workouts and 250+ Demos\"",
            "type \"lat pulldown\" into [2] EditText \"Search 1,969 Workouts and 250+ Demos\"",
            "press the keyboard's search or enter key in [2] EditText \"lat pulldown\"")) { it.id == Navigator.STUCK },
    Case("results show only workouts: scroll", "search-results", "pistol squat",
        listOf("type \"pistol squat\" into [2] EditText \"Search 1,969 Workouts and 250+ Demos\"",
            "press the keyboard's search or enter key in [2] EditText \"pistol squat\"")) { it.id.startsWith("scroll_") },
    Case("demos visible: open the named one", "search-results-demos", "pistol squat",
        listOf("press the keyboard's search or enter key in [2] EditText \"pistol squat\"", "scroll [5] RecyclerView \"\" down to see more")) {
        it.id.startsWith("tap_") && it.element?.label?.startsWith("Pistol Squat") == true
    },
    Case("demos visible: a different name", "search-results-demos", "box squat",
        listOf("press the keyboard's search or enter key in [2] EditText \"box squat\"", "scroll [5] RecyclerView \"\" down to see more")) {
        it.id.startsWith("tap_") && it.element?.label?.startsWith("Box Squat") == true
    },
    Case("demo page: start it", "demo-page", "barbell front squat",
        listOf("press the keyboard's search or enter key in [2] EditText \"barbell front squat\"", "tap [9] CardView \"Barbell Front Squat 1 minutes\""),
        accept = tapId("ctaPrimaryActionView")),
    Case("demo page with its preview playing: start it", "demo-page-preview-playing", "barbell front squat",
        listOf("press the keyboard's search or enter key in [2] EditText \"barbell front squat\"", "tap [5] CardView \"Barbell Front Squat 1 minutes\""),
        accept = tapId("ctaPrimaryActionView")),
    // A video left playing by an earlier request: nothing says which exercise it is, so start over.
    Case("player showing at the start of a request: leave it", "player", "goblet squat", emptyList()) { it.id == Navigator.BACK },
    // A plain "OK" that would charge a card: no keyword on the button gives it away.
    Case("purchase dialog: hand back to the user", "dialog:purchase", "pistol squat",
        listOf("tap [7] View \"Access control: Recovery24 Recovery24\""), commitment = true) { true },
    Case("discard an unstarted workout: allowed", "dialog:discard", "pistol squat",
        listOf("tap [5] CardView \"Squat Challenge #1 15 minutes\"", "press the system back button: leave this page, or close a dialog or an open search")) {
        it.id.startsWith("tap_") && it.element?.label == "Discard"
    },
    Case("player showing: done", "player", "barbell front squat",
        listOf("tap [9] CardView \"Barbell Front Squat 1 minutes\"", "tap [14] ViewGroup \"Start Demo Exercise\"")) { it.id == Navigator.DONE },
)

fun main() {
    val env = System.getenv()
    val deciders = buildList<Pair<String, Decider>> {
        env["TYPESAFE_API_KEY"]?.let { add("jev" to JevDecider(it)) }
        env["OPENAI_API_KEY"]?.let { key ->
            val model = env["EVAL_OPENAI_MODEL"] ?: "gpt-5-nano"
            add(model to ChatDecider("https://api.openai.com/v1", model, key, env["EVAL_OPENAI_IN"]?.toDouble() ?: 0.05, env["EVAL_OPENAI_OUT"]?.toDouble() ?: 0.40))
        }
        env["XAI_API_KEY"]?.let { key ->
            env["EVAL_XAI_MODEL"]?.let { model ->
                add(model to ChatDecider("https://api.x.ai/v1", model, key, env["EVAL_XAI_IN"]?.toDouble() ?: 0.0, env["EVAL_XAI_OUT"]?.toDouble() ?: 0.0))
            }
        }
        env["EVAL_LOCAL_URL"]?.let { url -> env["EVAL_LOCAL_MODEL"]?.let { add("local:$it" to ChatDecider(url, it)) } }
    }
    if (deciders.isEmpty()) return println("No model configured; see the comment at the top of Eval.kt.")

    for ((name, decider) in deciders) {
        println("\n== $name")
        var right = 0
        var tokens = 0
        var cost = 0.0
        val times = ArrayList<Long>()
        for (case in CASES) {
            val screen = ScreenText.of(DIALOGS[case.fixture] ?: UiAutomatorXml.load("/24go/${case.fixture}.xml"))
            val goal = Goal(APP, "Play the demo video for the exercise \"${case.exercise}\".", case.exercise, AppNotes.TWENTY_FOUR_GO)
            val options = Navigator.optionsFor(screen, goal, Navigator.canBeDone(case.history, goal))
            val request = DecisionRequest(goal.task, goal.notes, case.history, screen.text, options.associate { it.id to it.description })
            val start = System.nanoTime()
            val line = try {
                val d = decider.decide(request)
                val ms = (System.nanoTime() - start) / 1_000_000
                times.add(ms)
                tokens += d.inputTokens
                cost += d.costUsd
                val chosen = options.firstOrNull { it.id == d.option }
                val flagged = d.asksForCommitment >= Navigator.COMMITMENT_LIMIT
                val ok = chosen != null && flagged == case.commitment && (flagged || case.accept(chosen))
                if (ok) right++
                "${if (ok) "ok  " else "MISS"} ${"%5d".format(ms)} ms  conf ${"%.2f".format(d.confidence)}  " +
                    "${if (flagged) "[hands back] " else ""}${chosen?.description?.take(70) ?: d.option}"
            } catch (e: Exception) {
                "ERR  ${e.message?.take(140)}"
            }
            println("  ${case.name.padEnd(40)} $line")
        }
        val median = times.sorted().let { if (it.isEmpty()) 0 else it[it.size / 2] }
        println("  -> $right/${CASES.size} right, median $median ms, $tokens input tokens, $${"%.5f".format(cost)}")
    }
}
