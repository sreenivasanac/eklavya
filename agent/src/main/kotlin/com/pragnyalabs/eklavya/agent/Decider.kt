package com.pragnyalabs.eklavya.agent

/** One "what next?" question: everything a model needs to pick an option. */
class DecisionRequest(
    /** What the user wants, in a sentence. */
    val task: String,
    /** Facts about the app being driven that the screen does not show (how its search behaves, and so on). */
    val notes: List<String>,
    /** The actions already taken for this task, oldest first. */
    val history: List<String>,
    /** The current screen as numbered lines (ScreenText.text). */
    val screen: String,
    /** Option id -> what choosing it does. Insertion order is kept. */
    val options: Map<String, String>,
)

class Decision(
    val option: String,
    /** 0..1: how sure the model is. Models that cannot say report 1. */
    val confidence: Double,
    val inputTokens: Int = 0,
    val costUsd: Double = 0.0,
)

/**
 * Picks the next step. Implementations: [JevDecider] (TypeSafe Jev, cloud) and [ChatDecider] (any chat model
 * behind an OpenAI-style endpoint, cloud or on the device). Which one runs is a setting, not a code change.
 */
fun interface Decider {
    /** Blocks until answered; throws on a network or model error. */
    fun decide(request: DecisionRequest): Decision

    companion object {
        /** The question put to every model, so they are compared on the same words. */
        const val INSTRUCTIONS =
            "You operate a phone app for a user by choosing one action at a time. You are given the task, notes about " +
                "the app, the actions already taken in order, and the current screen as numbered elements. " +
                "Choose the single action to take next so that the task gets completed. " +
                "Choose \"done\" only when the current screen shows that the task is already complete. " +
                "Choose \"stuck\" when no action on this screen can make progress on the task."
    }
}
