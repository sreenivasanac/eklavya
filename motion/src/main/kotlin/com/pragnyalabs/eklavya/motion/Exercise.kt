package com.pragnyalabs.eklavya.motion

import com.pragnyalabs.eklavya.motion.Joint.ANKLE
import com.pragnyalabs.eklavya.motion.Joint.ELBOW
import com.pragnyalabs.eklavya.motion.Joint.HIP
import com.pragnyalabs.eklavya.motion.Joint.KNEE
import com.pragnyalabs.eklavya.motion.Joint.SHOULDER
import com.pragnyalabs.eklavya.motion.Joint.WRIST

/**
 * The exercises the camera can count. The coach is told which one is coming (it is not recognised from
 * the picture), so each needs only one number that goes up and down once per rep:
 *
 * - squat: how far the hips have dropped, as a fraction of their standing height above the ankles
 * - curl: how far the elbow has bent from straight, in degrees
 * - press: how high the wrists are above the shoulders, in torso lengths
 *
 * [minRise] is the smallest movement that counts as a rep and [typicalRise] the usual size of one, both in
 * that number's units. They are tuning values: set from recorded sets, to be adjusted against real ones.
 */
enum class Exercise(
    val plural: String,
    val needs: List<Joint>,
    val minRise: Float,
    val typicalRise: Float,
    /** How to stand for the form checks to work, said once when a set starts. */
    val view: String,
) {
    SQUAT("squats", listOf(SHOULDER, HIP, KNEE, ANKLE), 0.12f, 0.5f, "side on"),
    CURL("curls", listOf(SHOULDER, ELBOW, WRIST, HIP), 45f, 100f, "side on"),
    PRESS("shoulder presses", listOf(SHOULDER, ELBOW, WRIST, HIP), 0.25f, 0.6f, "facing me");

    companion object {
        /** The exercise a spoken or typed name means ("goblet squat", "dumbbell bicep curls"), or null. */
        fun named(text: String): Exercise? {
            val t = text.lowercase()
            return when {
                "squat" in t -> SQUAT
                "curl" in t && "leg" !in t && "hamstring" !in t -> CURL
                "press" in t && listOf("shoulder", "overhead", "military").any { it in t } -> PRESS
                else -> null
            }
        }
    }
}

/**
 * The mistakes the camera checks for, in the order they are worth mentioning, each with the few words
 * the coach says. These are the common beginner faults that can be judged from joint positions in a
 * flat picture; anything that needs depth or a view of the feet is left out.
 */
enum class Fault(val cue: String) {
    SHALLOW("Sit deeper."),
    LEAN("Chest up."),
    KNEES_IN("Push your knees out."),
    SWING("Don't swing."),
    ELBOW_DRIFT("Elbows by your sides."),
    SHORT_TOP("Curl all the way up."),
    SHORT_BOTTOM("Straighten your arms at the bottom."),
    FAST_DOWN("Lower it slower."),
    LOCKOUT("Press all the way up."),
    UNEVEN("Keep both arms level."),
}

/** What a finished set amounted to: said aloud in one line, and kept for when the user asks the coach about it. */
class SetReport(val exercise: Exercise, val reps: Int, val seconds: Int, val faults: Map<Fault, Int>) {
    /** The one thing to fix: the commonest fault, if it showed on at least a third of the reps. */
    val main: Fault? = faults.filter { it.value >= maxOf(2, reps / 3) }.maxByOrNull { it.value }?.key

    fun spoken(): String = when {
        reps == 0 -> "I didn't see any reps."
        else -> (if (reps == 1) "1 rep." else "$reps reps.") +
            (main?.let { " Next set, ${it.cue.replaceFirstChar(Char::lowercase)}" } ?: "")
    }

    /** The same facts as plain text for the voice model, which may be asked "how was that set?". */
    fun facts(): String = "$reps ${exercise.plural} in $seconds seconds. " +
        if (faults.isEmpty()) "No form faults were detected by the checks that could run."
        else "Form faults seen: " + faults.entries.sortedByDescending { it.value }
            .joinToString("; ") { "\"${it.key.cue}\" on ${it.value} of $reps reps" } + "."
}
