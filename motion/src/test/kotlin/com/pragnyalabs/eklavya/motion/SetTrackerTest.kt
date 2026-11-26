package com.pragnyalabs.eklavya.motion

import java.io.File
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The recordings under `resources/poses` are real sets (about 30 seconds each, 15 frames a second) run
 * through the same pose model the phone uses. The rep counts were checked against an independent peak
 * count of the raw signal and, where the two differed, against the video frames.
 */
class SetTrackerTest {
    private fun recording(name: String) = PoseFile.run(File(javaClass.getResource("/poses/$name.txt.gz")!!.toURI()))

    /** Recording, reps in it, and the one fault the coach names after the set (null: none). */
    private val sets = listOf(
        Triple("001_squat_good_side", 18, null),
        Triple("005_squat_good_front", 13, null),
        Triple("020_squat_good_front", 15, null), // thighs level, seen from the front: must not be called shallow
        Triple("012_squat_bad_side", 15, Fault.SHALLOW),
        Triple("012_squat_bad_front", 15, Fault.SHALLOW),
        Triple("005_bicep_curl_good_side", 6, null),
        Triple("020_bicep_curl_good_side", 9, null),
        Triple("005_bicep_curl_bad_side", 6, Fault.ELBOW_DRIFT),
        Triple("012_bicep_curl_bad_side", 8, Fault.FAST_DOWN),
        Triple("005_shoulder_good_front", 13, null),
        Triple("020_shoulder_bad_front", 15, null),
        Triple("005_shoulder_bad_front", 13, Fault.LOCKOUT),
    )

    @Test
    fun countsRecordedSets() {
        for ((name, reps, _) in sets) assertEquals(reps, recording(name).first.reps, name)
    }

    @Test
    fun namesTheMainFaultAndNoOther() {
        for ((name, _, fault) in sets) assertEquals(fault, recording(name).first.main, name)
    }

    @Test
    fun saysReadyFirstAndCuesSparingly() {
        val (report, events) = recording("012_squat_bad_side")
        assertTrue(events.first() is SetTracker.Ready)
        val cues = events.filterIsInstance<SetTracker.Counted>().filter { it.cue != null }
        // Shallow on 13 of 15 reps, yet said only three times: on the second rep, then about every sixth.
        assertEquals(listOf(2, 9, 15), cues.map { it.count })
        assertEquals("15 reps. Next set, sit deeper.", report.spoken())
    }

    /**
     * This set starts with the person bending towards the camera to pick the dumbbells up, which first
     * flipped the pose tracking upside down. The recording was made with the restart the app does; the
     * pick-up must not swallow the set or add more than a rep to it (the video shows 13 or 14 presses).
     */
    @Test
    fun survivesPickingUpTheWeights() {
        assertTrue(recording("001_shoulder_bad_front").first.reps in 13..14)
    }

    @Test
    fun endsTheSetAfterAPause() {
        val tracker = SetTracker(Exercise.SQUAT)
        val frames = PoseFile.read(File(javaClass.getResource("/poses/001_squat_good_side.txt.gz")!!.toURI()))
        frames.forEach { (t, pose) -> tracker.add(t, pose) }
        // The person stands still after the recording: the last frame repeats until the set closes itself.
        val (last, still) = frames.last()
        val ended = (1..200).firstNotNullOfOrNull { tracker.add(last + it * 66L, still) as? SetTracker.Ended }
        assertEquals(18, ended?.report?.reps)
        assertNull(tracker.add(last + 60_000, still))
    }

    @Test
    fun counterFollowsTurningPoints() {
        // Ten clean reps of size 1, one a second.
        fun wave(counter: RepCounter, fromMs: Long, reps: Int, base: Float, size: Float) =
            (0 until reps * 30).count { counter.add(fromMs + it * 33L, base + size * (0.5f - 0.5f * kotlin.math.cos(it / 30.0 * 2 * Math.PI).toFloat())) != null }
        assertEquals(10, wave(RepCounter(0.3f, 1f), 0, 10, 0f, 1f))
        // A resting level that moves between reps (weights picked up, a step back) does not stop the count.
        val shifted = RepCounter(0.3f, 1f)
        assertEquals(3, wave(shifted, 0, 3, 0f, 1f))
        assertEquals(3, wave(shifted, 10_000, 3, 2f, 1f))
        // A one-frame spike after a long rest is a tracking glitch, however long the rest was.
        val rested = RepCounter(0.3f, 1f)
        assertEquals(0, listOf(0L to 0f, 5000L to 0f, 5033L to 1f, 5066L to 0f).count { (t, v) -> rested.add(t, v) != null })
        // Jitter smaller than the smallest rep counts for nothing.
        val noisy = RepCounter(0.3f, 1f)
        assertEquals(0, (0 until 600).count { noisy.add(it * 33L, 0.1f * sin(it * 0.9f)) != null })
    }

    @Test
    fun understandsExerciseNames() {
        assertEquals(Exercise.SQUAT, Exercise.named("goblet squats"))
        assertEquals(Exercise.CURL, Exercise.named("Dumbbell bicep curl"))
        assertEquals(Exercise.PRESS, Exercise.named("overhead press"))
        assertNull(Exercise.named("leg curl"))
        assertNull(Exercise.named("bench press"))
    }
}
