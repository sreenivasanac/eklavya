package com.pragnyalabs.eklavya.motion

/**
 * Counts repetitions in one number that rises and falls once per rep (how far the hips have dropped,
 * how far the elbow has bent). It follows the turning points rather than fixed levels, so it does not
 * need to know where "rest" is: a rep is a rise of at least [minRise] from the lowest point since the
 * last rep, followed by a fall of 60% of that rise, or of a usual rep when the rise was larger than one.
 * The second half matters: picking up the weights is one big rise, and the first rep after it comes back
 * only a normal rep's worth, not 60% of the way to the floor. Standing a little differently, or starting
 * half way through a rep, likewise does not confuse it for long.
 */
class RepCounter(private val minRise: Float, typicalRise: Float) {
    /** One counted rep: where it started, its far point and when it was counted, with the signal's low and high. */
    class Rep(val startMs: Long, val peakMs: Long, val endMs: Long, val low: Float, val high: Float) {
        val rise get() = high - low
    }

    private var low = Float.MAX_VALUE
    private var lowMs = 0L
    private var high = 0f
    private var highMs = 0L
    private var rising = false

    /** The last moment the signal was still at its low: a rep is timed from here, not from a rest that may be long. */
    private var restMs = 0L

    /** The size of recent reps, which sets how far back counts as "returned". Starts at the exercise's usual size. */
    private val rises = ArrayDeque(listOf(typicalRise))

    /** Feed the next value; returns the rep this value completes, if any. */
    fun add(tMs: Long, s: Float): Rep? {
        if (!rising) {
            if (s < low) {
                low = s
                lowMs = tMs
            }
            if (s - low <= minRise * AT_REST) restMs = tMs
            if (s - low >= minRise) {
                rising = true
                high = s
                highMs = tMs
            }
            return null
        }
        if (s > high) {
            high = s
            highMs = tMs
        }
        val rise = high - low
        if (high - s < RETURN * minOf(rise, rises.sorted()[rises.size / 2])) return null

        val rep = Rep(lowMs, highMs, tMs, low, high)
        rising = false
        low = s
        lowMs = tMs
        // Too quick to be a person moving a limb there and back: a tracking glitch.
        if (tMs - restMs < MIN_REP_MS) return null
        rises.addLast(rise)
        if (rises.size > REMEMBERED) rises.removeFirst()
        return rep
    }

    private companion object {
        /** A rep is counted once the signal has come this fraction of the way back from its far point. */
        const val RETURN = 0.6f
        /** From leaving the low to the count. */
        const val MIN_REP_MS = 500L

        /** Within this share of the smallest rep above the low, the signal is taken as still resting. */
        const val AT_REST = 0.25f
        const val REMEMBERED = 5
    }
}
