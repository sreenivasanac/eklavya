package com.pragnyalabs.eklavya.voice

import android.content.Context
import android.util.Log
import com.pragnyalabs.eklavya.EklavyaService
import com.pragnyalabs.eklavya.GymApps
import com.pragnyalabs.eklavya.Settings
import com.pragnyalabs.eklavya.agent.AppNotes
import com.pragnyalabs.eklavya.agent.Goal
import com.pragnyalabs.eklavya.agent.Navigator
import org.json.JSONArray
import org.json.JSONObject

/** What the coach can do on the phone: the tools declared to the voice backend, and how each is carried out. */
object CoachTools {
    private const val TAG = "Eklavya"
    private const val SHOW_DEMO = "show_exercise_demo"

    /** Only tools that work are declared: a model offered a tool will use it and report success. */
    val DECLARATIONS: JSONArray = JSONArray().put(
        JSONObject()
            .put("type", "function")
            .put("name", SHOW_DEMO)
            .put("description", "Find an exercise's demo video in the gym's app on the user's phone and play it. " +
                "The result says whether a video is playing and which one, or why not.")
            .put("parameters", JSONObject()
                .put("type", "object")
                .put("properties", JSONObject().put("exercise", JSONObject().put("type", "string")
                    .put("description", "The exercise name as the user said it, e.g. \"pistol squat\".")))
                .put("required", JSONArray().put("exercise"))
                .put("additionalProperties", false))
            .put("strict", true),
    )

    /** Runs a tool and returns its result for the model. Blocks for as long as the phone takes. */
    fun run(context: Context, name: String, arguments: JSONObject): JSONObject = when (name) {
        SHOW_DEMO -> showDemo(context, arguments.optString("exercise"))
        else -> failed("unknown tool $name")
    }

    private fun showDemo(context: Context, exercise: String): JSONObject {
        if (exercise.isBlank()) return failed("no exercise name was given")
        val service = EklavyaService.instance ?: return failed("Eklavya's accessibility service is switched off on the phone")
        val decider = Settings.decider(context) ?: return failed("no decision model key is saved in the Eklavya app")
        val goal = Goal(
            GymApps.TWENTY_FOUR_GO, "Play the demo video for the exercise \"$exercise\".", exercise,
            AppNotes.TWENTY_FOUR_GO, AppNotes.TWENTY_FOUR_GO_HOME,
        )
        val outcome = try {
            Navigator(service.driver, decider) { Log.i(TAG, "nav: $it") }.run(goal)
        } catch (e: Exception) {
            Log.e(TAG, "navigation failed", e)
            return failed("the phone could not reach the decision model")
        }
        // Which result was opened, so the coach can name it when it differs from what was asked.
        val opened = outcome.steps.lastOrNull { "CardView" in it }?.substringAfter('"')?.substringBeforeLast('"')?.removeSuffix(" 1 minutes")
        // The navigator stops with "no way forward" both when a search comes back empty and when it is lost.
        // Having pressed search and opened nothing means the first: say so, or the coach invents a reason.
        val searched = outcome.steps.any { "search or enter key" in it }
        return when {
            outcome.done -> JSONObject().put("status", "playing").put("video", opened ?: exercise)
            outcome.needsUser -> JSONObject().put("status", "needs_user").put("reason", outcome.reason)
            searched && opened == null && outcome.reason == Navigator.NO_WAY_FORWARD -> JSONObject().put("status", "not_found")
                .put("reason", "the gym app's search has no demo video under the name \"$exercise\"; it may be listed under another name")
            else -> JSONObject().put("status", "not_playing").put("reason", outcome.reason)
        }
    }

    private fun failed(reason: String) = JSONObject().put("status", "not_playing").put("reason", reason)
}
