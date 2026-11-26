package com.pragnyalabs.eklavya.agent

/**
 * What Eklavya knows about each app that its screens do not say. This is data, not code: when an app
 * changes how it behaves, the notes change and the navigator stays the same.
 */
object AppNotes {
    /** A tab that shows on 24GO's main screens; see Goal.home. */
    const val TWENTY_FOUR_GO_HOME = "Workouts"

    /** 24GO 1.94.0. */
    val TWENTY_FOUR_GO = listOf(
        "Exercise demo videos are found with the search box on the Workouts tab.",
        "Typing in the search field does not search. The search runs only after the keyboard's search key is pressed.",
        "In the search results, a result lasting \"1 minutes\" is an exercise demo. Longer results are workouts, not demos. Demos are listed below the workouts, so scroll the results down if no demo is visible.",
        "A demo's page has a \"Start Demo Exercise\" button. A page with a \"Preview Workout\" or \"Start\" button is a workout, not a demo: go back.",
        "The demo's page only shows a small preview. The task is not complete on that page: tap \"Start Demo Exercise\".",
        "The task is complete only when the full-screen video player is showing: a screen with no \"Start Demo Exercise\" button and no exercise description, only player controls.",
        "If a video player or a demo page is already showing before any action has been taken, it is left over from an earlier request and may be a different exercise: press back and search for the exercise.",
        "If the search says no results were found after the search key was pressed, the app has no demo under that name.",
        "A \"Discard workout?\" dialog saying the workout hasn't started yet can be answered with Discard.",
    )
}
