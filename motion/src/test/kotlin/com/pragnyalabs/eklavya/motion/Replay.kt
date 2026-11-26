package com.pragnyalabs.eklavya.motion

import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Recorded poses: a header line, then one line per frame, `t_ms` followed by 33 `x y visibility` triples
 * (or `-` when nobody was found). x is already in image heights. Files may be gzipped.
 */
object PoseFile {
    fun read(file: File): List<Pair<Long, Pose?>> {
        val text = file.inputStream().use { if (file.name.endsWith(".gz")) GZIPInputStream(it).readBytes() else it.readBytes() }
        return text.decodeToString().lineSequence().drop(1).filter { it.isNotBlank() }.map { line ->
            val f = line.split(' ')
            val t = f[0].toLong()
            t to if (f[1] == "-") null else Pose(t, FloatArray(Pose.LANDMARKS * 3) { f[it + 1].toFloat() })
        }.toList()
    }

    /** What the tracker reports for a whole recording, ending the set at the last frame if it is still open. */
    fun run(file: File): Pair<SetReport, List<SetTracker.Event>> {
        val exercise = when {
            "squat" in file.name -> Exercise.SQUAT
            "curl" in file.name -> Exercise.CURL
            else -> Exercise.PRESS
        }
        val tracker = SetTracker(exercise)
        val frames = read(file)
        val events = frames.mapNotNull { (t, pose) -> tracker.add(t, pose) }.toMutableList()
        val ended = events.lastOrNull() as? SetTracker.Ended ?: tracker.end(frames.last().first).also { events += it }
        return ended.report to events
    }
}

/** Prints the count and the cues for every pose file in a folder: `./gradlew :motion:replay -Pposes=<folder>`. */
fun main(args: Array<String>) {
    val files = File(args[0]).listFiles { f -> f.name.endsWith(".txt") || f.name.endsWith(".txt.gz") }!!.sortedBy { it.name }
    for (file in files) {
        val (report, events) = PoseFile.run(file)
        val cues = events.filterIsInstance<SetTracker.Counted>().filter { it.cue != null }.joinToString(" ") { "${it.count}:${it.cue}" }
        val first = events.firstOrNull()?.let { it::class.simpleName }
        println("%-36s %2d reps  first=%s  said: %-40s cues: %s  faults: %s".format(
            file.name.removePrefix("subject_").substringBefore(".txt"), report.reps, first, report.spoken(), cues, report.faults,
        ))
    }
}
