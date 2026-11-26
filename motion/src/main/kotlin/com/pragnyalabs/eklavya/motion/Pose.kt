package com.pragnyalabs.eklavya.motion

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot

/** A point in the picture. Both axes are in image heights, y grows downwards. */
class Pt(val x: Float, val y: Float)

/** The joints the coach measures; each has a left and a right landmark (MediaPipe's numbering). */
enum class Joint(val left: Int, val right: Int) {
    SHOULDER(11, 12), ELBOW(13, 14), WRIST(15, 16), HIP(23, 24), KNEE(25, 26), ANKLE(27, 28)
}

/**
 * One frame's body pose: MediaPipe's 33 landmarks as `x y visibility` triples. x must already be multiplied
 * by width/height, so that distances and angles are true to the picture whatever its shape.
 */
class Pose(val tMs: Long, private val values: FloatArray) {
    init {
        require(values.size == LANDMARKS * 3) { "expected ${LANDMARKS * 3} values, got ${values.size}" }
    }

    private fun vis(i: Int) = values[i * 3 + 2]
    private fun pt(i: Int) = Pt(values[i * 3], values[i * 3 + 1])

    /** The joint on one side of the body. */
    fun at(j: Joint, left: Boolean) = pt(if (left) j.left else j.right)

    fun visibility(j: Joint, left: Boolean) = vis(if (left) j.left else j.right)

    /** True when at least one side of the joint is in view. Side-on, the far side never is. */
    fun sees(j: Joint) = maxOf(vis(j.left), vis(j.right)) >= SEEN

    /** The middle of a left/right pair, leaning towards the side the camera sees better. */
    fun mid(j: Joint): Pt {
        val wl = vis(j.left) + 0.01f
        val wr = vis(j.right) + 0.01f
        val l = pt(j.left)
        val r = pt(j.right)
        return Pt((l.x * wl + r.x * wr) / (wl + wr), (l.y * wl + r.y * wr) / (wl + wr))
    }

    /** How visible the left arm and leg are, minus the right: positive when the left side faces the camera. */
    fun leftness() = Joint.entries.sumOf { (vis(it.left) - vis(it.right)).toDouble() }.toFloat()

    /** Shoulder width over torso length: about 0.1 side-on, 0.6 or more facing the camera. */
    fun facing(): Float = abs(pt(Joint.SHOULDER.left).x - pt(Joint.SHOULDER.right).x) / torso()

    fun torso() = dist(mid(Joint.SHOULDER), mid(Joint.HIP)).coerceAtLeast(0.01f)

    /**
     * False when the shoulders are below the hips. Nobody doing these exercises is, but pose tracking can
     * lock on upside down after the person bends towards the camera, and it stays that way until restarted.
     */
    fun upright() = mid(Joint.SHOULDER).y < mid(Joint.HIP).y

    companion object {
        const val LANDMARKS = 33

        /** MediaPipe's visibility score above which a joint is taken as in view. */
        const val SEEN = 0.5f

        fun dist(a: Pt, b: Pt) = hypot(a.x - b.x, a.y - b.y)

        /** The angle at `b` between `a` and `c`, in degrees: 180 is a straight limb. */
        fun angle(a: Pt, b: Pt, c: Pt): Float {
            val ux = a.x - b.x
            val uy = a.y - b.y
            val vx = c.x - b.x
            val vy = c.y - b.y
            val cos = (ux * vx + uy * vy) / (hypot(ux, uy) * hypot(vx, vy) + 1e-9f)
            return Math.toDegrees(acos(cos.coerceIn(-1f, 1f)).toDouble()).toFloat()
        }

        /** How far the line from `low` up to `high` leans from upright, in degrees, signed by direction. */
        fun lean(low: Pt, high: Pt) = Math.toDegrees(atan2(high.x - low.x, low.y - high.y).toDouble()).toFloat()
    }
}
