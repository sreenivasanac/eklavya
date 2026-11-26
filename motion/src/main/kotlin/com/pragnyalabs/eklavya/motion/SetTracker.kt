package com.pragnyalabs.eklavya.motion

import com.pragnyalabs.eklavya.motion.Joint.ANKLE
import com.pragnyalabs.eklavya.motion.Joint.ELBOW
import com.pragnyalabs.eklavya.motion.Joint.HIP
import com.pragnyalabs.eklavya.motion.Joint.KNEE
import com.pragnyalabs.eklavya.motion.Joint.SHOULDER
import com.pragnyalabs.eklavya.motion.Joint.WRIST
import kotlin.math.abs
import kotlin.math.exp

/**
 * Watches one set of one exercise. Give it every camera frame's pose (or null when nobody is found) and it
 * says what the coach should do: confirm it can see the person, count a rep, give a form cue, or close the
 * set. It holds no Android code, so the same class runs on recorded poses in the tests.
 */
class SetTracker(val exercise: Exercise) {
    sealed interface Event

    /** The joints this exercise needs have been in view for a moment: the user can start. */
    data object Ready : Event

    /** A few seconds in and the needed joints are still not in view. */
    data object CantSee : Event

    /** A rep was completed; [cue] is a correction worth saying now, if any. */
    class Counted(val count: Int, val cue: Fault?) : Event

    /** The set is over: no rep for a while, or the user left the picture. Nothing more is reported after this. */
    class Ended(val report: SetReport) : Event

    private class Frame(val pose: Pose, val s: Float)

    private val counter = RepCounter(exercise.minRise, exercise.typicalRise)
    private val frames = ArrayList<Frame>()
    private val recent = ArrayDeque<Float>()
    private var smooth = Float.NaN
    private var smoothMs = 0L

    /** Squat only: the highest the hips have been above the ankles, taken as standing height. */
    private var standing = 0f

    /** Left-minus-right visibility over the last few seconds: which side of the body faces the camera now. */
    private var leftness = 0f

    private var startMs = -1L
    private var seenSinceMs = -1L
    private var lastSeenMs = -1L
    private var lastRepMs = -1L
    private var ready = false
    private var warned = false
    private var ended = false

    private var count = 0
    private val totals = sortedMapOf<Fault, Int>()
    private val streak = HashMap<Fault, Int>()
    private var lastCue: Fault? = null
    private var lastCueRep = 0

    fun add(tMs: Long, pose: Pose?): Event? {
        if (ended) return null
        if (startMs < 0) {
            startMs = tMs
            lastSeenMs = tMs
        }
        val seen = pose != null && pose.upright() && exercise.needs.all(pose::sees)
        if (seen) {
            lastSeenMs = tMs
            if (seenSinceMs < 0) seenSinceMs = tMs
        } else {
            seenSinceMs = -1
        }
        val over = if (count > 0) tMs - lastRepMs > REST_END_MS || tMs - lastSeenMs > GONE_END_MS else tMs - startMs > NO_REPS_END_MS
        if (over) return end(tMs)
        if (!seen) {
            if (!ready && !warned && tMs - startMs > CANT_SEE_MS) {
                warned = true
                return CantSee
            }
            return null
        }

        // Fading the past lets a turn-around before the set (walking in, picking up the weights) be forgotten.
        leftness = leftness * SIDE_MEMORY + pose!!.leftness()
        val s = smoothed(tMs, signal(pose))
        frames += Frame(pose, s)
        if (frames.size > MAX_FRAMES) frames.removeAt(0)

        val rep = counter.add(tMs, s)
        if (rep != null) {
            count++
            lastRepMs = tMs
            val found = faults(rep, frames.filter { it.pose.tMs >= rep.startMs })
            frames.clear()
            for (f in Fault.entries) streak[f] = if (f in found) (streak[f] ?: 0) + 1 else 0
            for (f in found) totals[f] = (totals[f] ?: 0) + 1
            return Counted(count, cueFor())
        }
        if (!ready && tMs - seenSinceMs >= READY_MS) {
            ready = true
            return Ready
        }
        return null
    }

    /** Close the set now (the user said stop, or the session is ending). */
    fun end(tMs: Long): Ended {
        ended = true
        // No frame at all (the camera never opened) is a set of no length, not one as long as the phone has been on.
        val seconds = if (startMs < 0) 0 else ((tMs - startMs) / 1000).toInt()
        return Ended(SetReport(exercise, count, seconds, totals))
    }

    /** The exercise's one number for this frame, before smoothing (see [Exercise]). */
    private fun signal(p: Pose): Float {
        val left = leftness >= 0
        return when (exercise) {
            Exercise.SQUAT -> p.mid(ANKLE).y - p.mid(HIP).y
            Exercise.CURL -> 180 - Pose.angle(p.at(SHOULDER, left), p.at(ELBOW, left), p.at(WRIST, left))
            Exercise.PRESS -> (p.mid(SHOULDER).y - p.mid(WRIST).y) / p.torso()
        }
    }

    /** Median of five frames to drop a stray bad one, then a short average to take out the jitter. */
    private fun smoothed(tMs: Long, raw: Float): Float {
        recent.addLast(raw)
        if (recent.size > MEDIAN) recent.removeFirst()
        val median = recent.sorted()[recent.size / 2]
        smooth = if (smooth.isNaN()) median else {
            val a = 1 - exp(-(tMs - smoothMs) / SMOOTH_MS)
            smooth + a * (median - smooth)
        }
        smoothMs = tMs
        if (exercise != Exercise.SQUAT) return smooth
        standing = maxOf(standing, smooth)
        return 1 - smooth / standing.coerceAtLeast(0.01f)
    }

    /** The form checks for one rep, run on the frames from its start to the moment it was counted. */
    private fun faults(rep: RepCounter.Rep, fs: List<Frame>): Set<Fault> {
        if (fs.isEmpty()) return emptySet()
        val left = leftness >= 0
        val start = fs.first().pose
        val peak = fs.minBy { abs(it.pose.tMs - rep.peakMs) }.pose
        val sideOn = start.facing() < SIDE_ON
        val frontOn = start.facing() > FRONT_ON
        fun clear(p: Pose, vararg joints: Joint) = joints.all { p.visibility(it, left) >= CLEAR }
        /** Clear on whichever side faces the camera: enough for a measure taken from the pair's middle. */
        fun eitherClear(p: Pose, vararg joints: Joint) =
            joints.all { maxOf(p.visibility(it, true), p.visibility(it, false)) >= CLEAR }
        fun bothClear(p: Pose, vararg joints: Joint) =
            joints.all { p.visibility(it, true) >= CLEAR && p.visibility(it, false) >= CLEAR }

        val found = HashSet<Fault>()
        when (exercise) {
            Exercise.SQUAT -> {
                if (eitherClear(peak, HIP, ANKLE) && rep.high < if (sideOn) SHALLOW_SIDE else SHALLOW_FRONT) found += Fault.SHALLOW
                // Leaning is only measurable from the side: towards the camera it cannot be seen.
                if (sideOn && clear(peak, SHOULDER, HIP) &&
                    abs(Pose.lean(peak.at(HIP, left), peak.at(SHOULDER, left))) > MAX_SQUAT_LEAN
                ) found += Fault.LEAN
                if (frontOn && bothClear(peak, KNEE, ANKLE)) {
                    val knees = abs(peak.at(KNEE, true).x - peak.at(KNEE, false).x)
                    val ankles = abs(peak.at(ANKLE, true).x - peak.at(ANKLE, false).x)
                    if (knees < KNEES_IN * ankles) found += Fault.KNEES_IN
                }
            }

            Exercise.CURL -> {
                val steady = fs.map { it.pose }.filter { clear(it, SHOULDER, ELBOW, HIP) }
                if (steady.size >= fs.size / 2) {
                    val leans = steady.map { Pose.lean(it.at(HIP, left), it.at(SHOULDER, left)) }
                    if (leans.max() - leans.min() > MAX_SWING) found += Fault.SWING
                    // The upper arm should hang by the torso; the 90th percentile ignores one jumpy frame.
                    val drift = steady.map { Pose.angle(it.at(HIP, left), it.at(SHOULDER, left), it.at(ELBOW, left)) }.sorted()
                    if (drift[drift.size * 9 / 10] > MAX_ELBOW_DRIFT) found += Fault.ELBOW_DRIFT
                }
                if (clear(peak, SHOULDER, ELBOW, WRIST) && rep.high < CURL_TOP) found += Fault.SHORT_TOP
                if (clear(start, SHOULDER, ELBOW, WRIST) && rep.low > CURL_BOTTOM) found += Fault.SHORT_BOTTOM
                if (rep.endMs - rep.peakMs < FAST_DOWN_MS) found += Fault.FAST_DOWN
            }

            Exercise.PRESS -> if (bothClear(peak, SHOULDER, ELBOW, WRIST)) {
                val elbows = listOf(true, false).map { Pose.angle(peak.at(SHOULDER, it), peak.at(ELBOW, it), peak.at(WRIST, it)) }
                if (elbows.average() < LOCKOUT) found += Fault.LOCKOUT
                val width = abs(peak.at(SHOULDER, true).x - peak.at(SHOULDER, false).x)
                if (frontOn && abs(peak.at(WRIST, true).y - peak.at(WRIST, false).y) > UNEVEN * width) found += Fault.UNEVEN
            }
        }
        return found
    }

    /**
     * A cue is said only for a fault seen on two reps running, at most once every few reps, and the same
     * one is not repeated straight away: a coach who corrects every rep is tuned out.
     */
    private fun cueFor(): Fault? {
        val since = count - lastCueRep
        if (lastCue != null && since < CUE_EVERY) return null
        val cue = Fault.entries.firstOrNull { (streak[it] ?: 0) >= 2 && (it != lastCue || since >= REPEAT_AFTER) } ?: return null
        lastCue = cue
        lastCueRep = count
        return cue
    }

    private companion object {
        const val MEDIAN = 5

        /** Per frame; at 20 frames a second the side seen three seconds ago counts for about a tenth. */
        const val SIDE_MEMORY = 0.97f
        const val SMOOTH_MS = 100f
        const val MAX_FRAMES = 1800

        /** The set ends this long after the last rep, or this long after the user leaves the picture. */
        const val REST_END_MS = 8_000L
        const val GONE_END_MS = 3_000L

        /** With no rep at all the set is given up after this long. */
        const val NO_REPS_END_MS = 60_000L
        const val READY_MS = 1_000L
        const val CANT_SEE_MS = 5_000L

        /** A form check runs only when the joints it measures are this visible. */
        const val CLEAR = 0.6f

        /** [Pose.facing] below this is a side view, above the other a front view; between is neither. */
        const val SIDE_ON = 0.35f
        const val FRONT_ON = 0.5f

        // Form limits. Set from 32 recorded sets (see the tests) and ordinary coaching rules; all are
        // tuning values to revisit against real sets.
        /**
         * A squat is called shallow when the hips drop less than this share of their standing height. Thighs
         * level with the floor measured about 0.5 from the side and 0.4 from the front in the recorded sets
         * (the hip landmark sits higher when seen from the front), so the front limit is the more lenient:
         * a wrong "sit deeper" is worse than a missed one.
         */
        const val SHALLOW_SIDE = 0.38f
        const val SHALLOW_FRONT = 0.30f
        const val MAX_SQUAT_LEAN = 55f
        const val KNEES_IN = 0.8f
        const val MAX_SWING = 15f
        const val MAX_ELBOW_DRIFT = 30f
        const val CURL_TOP = 90f
        const val CURL_BOTTOM = 35f
        const val FAST_DOWN_MS = 500L
        /** Straight arms read as about 160 to 170 degrees from the front; under this the press stopped short. */
        const val LOCKOUT = 140f
        const val UNEVEN = 0.3f

        const val CUE_EVERY = 3
        const val REPEAT_AFTER = 6
    }
}
