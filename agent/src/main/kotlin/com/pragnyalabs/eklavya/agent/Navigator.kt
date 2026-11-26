package com.pragnyalabs.eklavya.agent

import com.pragnyalabs.eklavya.screen.Element
import com.pragnyalabs.eklavya.screen.ScreenControl
import com.pragnyalabs.eklavya.screen.ScreenText

/** What to get done in which app. */
class Goal(
    val appPackage: String,
    val task: String,
    /** The text to type when a field needs it (a search term); null when the task involves no typing. */
    val text: String? = null,
    /** Facts about the app that the screen does not show. */
    val notes: List<String> = emptyList(),
    /**
     * The label of an element found on the app's main screens (a tab, say). When set, the navigator first
     * presses back until it shows, so every request starts from a known place instead of wherever the last
     * one ended.
     */
    val home: String? = null,
)

class Outcome(
    val done: Boolean,
    val reason: String,
    val steps: List<String>,
    val inputTokens: Int,
    val costUsd: Double,
    /** The navigator stopped at a screen that asks for a decision only the user may take. */
    val needsUser: Boolean = false,
)

/**
 * Works towards a [Goal] one step at a time: read the screen, offer the model every possible action as a
 * choice, carry out the one it picks, repeat. The model never produces free text or coordinates; it can only
 * choose from actions that exist on the current screen.
 */
class Navigator(
    private val control: ScreenControl,
    private val decider: Decider,
    private val log: (String) -> Unit = {},
) {
    fun run(goal: Goal): Outcome {
        val history = ArrayList<String>()
        val seen = HashSet<String>()
        var tokens = 0
        var cost = 0.0
        fun end(done: Boolean, reason: String): Outcome {
            log(if (done) "done: $reason" else "stopped: $reason")
            return Outcome(done, reason, history, tokens, cost)
        }

        if (control.read().packageName != goal.appPackage && !control.openApp(goal.appPackage)) {
            return end(false, "the app is not installed")
        }
        backToHome(goal)
        var misses = 0
        repeat(MAX_STEPS) {
            // Only the goal app's windows are read: nothing from another app is sent to the model or tapped.
            val screen = control.read(goal.appPackage)
            // Acting on whatever is in front after leaving the app would be acting blind.
            if (screen.packageName != goal.appPackage) return end(false, "the app is no longer in front")
            // A screen left over from an earlier request can look finished (a video is playing, but of what?).
            // "Done" is believable only once this request has acted on the thing asked for: typed its name or
            // tapped an element carrying it. Without a name to look for, any action counts.
            val options = optionsFor(screen, goal, canBeDone(history, goal))
            val decision = decider.decide(DecisionRequest(goal.task, goal.notes, history, screen.text, options.associate { it.id to it.description }))
            tokens += decision.inputTokens
            cost += decision.costUsd
            val chosen = options.firstOrNull { it.id == decision.option } ?: return end(false, "the model chose \"${decision.option}\", which was not offered")
            log("${chosen.id} (${"%.2f".format(decision.confidence)}): ${chosen.description}")

            // A payment, booking or account change is the user's to confirm, whatever the model wanted to tap.
            if (decision.asksForCommitment >= COMMITMENT_LIMIT) {
                log("stopped: this screen asks for a decision that is yours")
                return Outcome(false, "the app is asking you to confirm something (a payment, a booking or an account change)", history, tokens, cost, needsUser = true)
            }
            if (chosen.id == STUCK) return end(false, NO_WAY_FORWARD)
            // A guess is not acted on, and a guessed "done" is not reported as success.
            if (decision.confidence < MIN_CONFIDENCE) return end(false, "unsure what to do next (wanted to: ${chosen.description})")
            if (chosen.id == DONE) return end(true, "task complete")
            // The same choice on the same screen twice means the action changes nothing.
            if (chosen.id + "\n" + screen.text in seen) return end(false, "going in circles at: ${chosen.description}")
            // The model took a moment to answer: "back" has no element to re-check, so check the app is still in front.
            if (chosen.element == null && control.read().packageName != goal.appPackage) return end(false, "the app is no longer in front")
            if (perform(chosen, goal)) {
                seen.add(chosen.id + "\n" + screen.text)
                history.add(chosen.description)
            } else {
                // The screen moved on while the model was deciding (a page was still loading): look again.
                if (++misses > MAX_MISSES) return end(false, "could not: ${chosen.description}")
                log("the screen changed before that could be done; looking again")
            }
        }
        return end(false, "gave up after $MAX_STEPS steps")
    }

    /**
     * Presses back until [Goal.home] is on screen. Stops early when back leads somewhere already seen (a
     * confirmation dialog that back only opens and closes: the model deals with it) or out of the app.
     */
    private fun backToHome(goal: Goal) {
        val home = goal.home ?: return
        val visited = HashSet<String>()
        var backs = 0
        while (backs < MAX_BACKS) {
            val screen = control.read(goal.appPackage)
            if (screen.packageName != goal.appPackage) {
                // Back went past the app's first screen: bring it to the front again and start there.
                control.openApp(goal.appPackage)
                break
            }
            if (screen.byLabel(home) != null || !visited.add(screen.text)) break
            control.back()
            backs++
        }
        if (backs > 0) log("went back $backs time(s) to start from the app's main screen")
    }

    private fun perform(o: Option, goal: Goal): Boolean {
        val e = o.element
        return when (o.id.substringBefore('_')) {
            "tap" -> control.tap(e!!)
            "type" -> control.setText(e!!, goal.text.orEmpty())
            "submit" -> control.submit(e!!)
            "scroll" -> control.scroll(e!!, forward = true)
            BACK -> control.back()
            else -> false
        }
    }

    /** One thing that can be done on the current screen. */
    class Option(val id: String, val description: String, val element: Element?)

    companion object {
        /** Whether the actions taken so far touched the thing asked for; see the comment in [run]. */
        fun canBeDone(history: List<String>, goal: Goal): Boolean =
            history.any { goal.text == null || it.contains(goal.text, ignoreCase = true) }

        /** Every action possible on [screen] for [goal], in screen order, then back / done / stuck. */
        fun optionsFor(screen: ScreenText, goal: Goal, canBeDone: Boolean = true): List<Option> {
            val out = ArrayList<Option>()
            for (e in screen.elements) {
                if (RISKY.containsMatchIn(e.label)) continue
                val name = "[${e.index}] ${e.node.className?.substringAfterLast('.') ?: "View"} \"${e.label.take(MAX_NAME)}\""
                if (e.node.editable) {
                    goal.text?.let { out.add(Option("type_${e.index}", "type \"$it\" into $name", e)) }
                    // Pressing search on an empty field does nothing useful, so it is offered only once there is text.
                    if (e.node.text != null) out.add(Option("submit_${e.index}", "press the keyboard's search or enter key in $name", e))
                } else if (e.node.clickable) {
                    out.add(Option("tap_${e.index}", "tap $name", e))
                }
                if (e.node.scrollable) out.add(Option("scroll_${e.index}", "scroll $name down to see more", e))
            }
            out.add(Option(BACK, "press the system back button: leave this page, or close a dialog or an open search", null))
            if (canBeDone) out.add(Option(DONE, "the task is already complete on this screen", null))
            out.add(Option(STUCK, "no action on this screen can make progress on the task", null))
            return out
        }

        /** Why a run ended when the model chose "stuck". */
        const val NO_WAY_FORWARD = "no way forward from this screen"
        const val DONE = "done"
        const val STUCK = "stuck"
        const val BACK = "back"
        private const val MAX_STEPS = 15

        /** How far the navigator backs out when looking for the app's main screen. */
        private const val MAX_BACKS = 6

        /** How many times an action may fail because the screen changed under it before giving up. */
        private const val MAX_MISSES = 3
        private const val MAX_NAME = 80

        /** Below this the model is guessing; stop and say what it wanted rather than tap on a guess. */
        const val MIN_CONFIDENCE = 0.4

        /** At or above this the screen is treated as asking for a commitment and the navigator hands back. */
        const val COMMITMENT_LIMIT = 0.5

        /**
         * Elements that spend money, post, or end something are never offered to the model, whatever the task.
         * ponytail: a keyword list in English; replace with a per-app allow list or a second model check
         * before driving apps where a wrong tap costs more than a wasted minute.
         */
        private val RISKY = Regex(
            """\b(pay|buy|purchase|subscribe|upgrade|checkout|order|delete|remove|log ?out|sign ?out|cancel membership|share|post|send|book|reserve|unlock)\b""",
            RegexOption.IGNORE_CASE,
        )
    }
}
