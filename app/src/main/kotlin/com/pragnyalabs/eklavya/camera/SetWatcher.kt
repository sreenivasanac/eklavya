package com.pragnyalabs.eklavya.camera

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.pragnyalabs.eklavya.motion.Exercise
import com.pragnyalabs.eklavya.motion.Pose
import com.pragnyalabs.eklavya.motion.SetReport
import com.pragnyalabs.eklavya.motion.SetTracker
import java.io.File
import java.util.concurrent.Executors

/**
 * Watches one set through the front camera. Each frame goes through the pose model on the phone, the pose
 * goes to a [SetTracker], and what the tracker reports is said aloud through [say]: "Ready", the count,
 * a form cue, and one line when the set ends. No picture is stored and none leaves the phone.
 *
 * The camera needs a lifecycle to follow; a service has none, so this object is its own.
 */
class SetWatcher(
    private val context: Context,
    val exercise: Exercise,
    private val say: (String) -> Unit,
    private val onEnd: (SetReport) -> Unit,
) : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    private val main = Handler(Looper.getMainLooper())

    /** Everything that touches the pose model and the tracker runs here, one frame at a time. */
    private val worker = Executors.newSingleThreadExecutor()
    private val tracker = SetTracker(exercise)
    private var landmarker: PoseLandmarker? = null
    private var flipped = 0
    private var lastMs = 0L

    // For the log: how many frames a second the phone manages, and how long the pose model takes.
    private var frames = 0
    private var poseMs = 0L
    private var statsFrom = 0L

    @Volatile private var over = false

    /** Set by [stop] at once, so that a video being replayed stops between frames instead of running to its end. */
    @Volatile private var cancelled = false

    /** Opens the front camera and starts watching. Main thread. */
    fun startCamera() {
        registry.currentState = Lifecycle.State.RESUMED
        val providers = ProcessCameraProvider.getInstance(context)
        providers.addListener({
            if (over) return@addListener
            // The pose model works on a small picture; keep-latest drops frames rather than fall behind.
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(FRAME_SIZE, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(worker) { image ->
                try {
                    image.use { frame(it.toBitmap(), it.imageInfo.rotationDegrees, SystemClock.uptimeMillis()) }
                } catch (e: Exception) {
                    broke(e)
                }
            }
            try {
                providers.get().bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                Log.i(TAG, "camera: watching ${exercise.plural}")
            } catch (e: Exception) {
                Log.e(TAG, "camera: could not open", e)
                say("I can't open the camera.")
                closeQuietly()
            }
        }, context.mainExecutor)
    }

    /** Debug aid: runs a video file through the same pose model and tracker, as a set seen by the camera. */
    fun playFile(file: File) = worker.execute {
        val video = MediaMetadataRetriever()
        try {
            video.setDataSource(file.path)
            val durationMs = video.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
            var t = 0L
            while (!over && !cancelled && t < durationMs) {
                val picture = video.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST) ?: break
                frame(if (picture.config == Bitmap.Config.ARGB_8888) picture else picture.copy(Bitmap.Config.ARGB_8888, false), 0, t)
                t += FILE_STEP_MS
            }
            if (!over) finish(tracker.end(t).report, speak = !cancelled)
        } catch (e: Exception) {
            broke(e)
        } finally {
            video.release()
        }
    }

    /** Stops watching without a word (the session is ending, or another set is starting). */
    fun stop() {
        cancelled = true
        closeQuietly()
    }

    /** Ends the set without its spoken line, unless it has already ended. Safe from any thread. */
    private fun closeQuietly() {
        try {
            worker.execute { if (!over) finish(tracker.end(lastMs).report, speak = false) }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // the set had just ended by itself and taken the worker with it
        }
    }

    /** One picture: find the pose, hand it to the tracker, say what the tracker reports. Worker thread. */
    private fun frame(picture: Bitmap, rotation: Int, tMs: Long) {
        if (over || cancelled) return
        val began = SystemClock.uptimeMillis()
        val model = landmarker ?: create().also { landmarker = it }
        val turned = ImageProcessingOptions.builder().setRotationDegrees(rotation).build()
        val found = try {
            model.detectForVideo(BitmapImageBuilder(picture).build(), turned, tMs).landmarks().firstOrNull()
        } catch (e: Exception) {
            if (!useGpu) throw e
            // Some phones accept the graphics chip and then fail on the first picture: drop to the processor for good.
            Log.w(TAG, "camera: pose model failed on the graphics chip, using the processor", e)
            useGpu = false
            model.close()
            landmarker = null
            return
        }
        // Landmarks come as fractions of the upright picture's width and height; the tracker wants both in heights.
        val aspect = if (rotation % 180 == 0) picture.width.toFloat() / picture.height else picture.height.toFloat() / picture.width
        val pose = found?.let { points ->
            Pose(tMs, FloatArray(Pose.LANDMARKS * 3) { i ->
                val p = points[i / 3]
                when (i % 3) {
                    0 -> p.x() * aspect
                    1 -> p.y()
                    else -> p.visibility().orElse(0f)
                }
            })
        }
        // Tracking can lock on upside down after the person bends towards the camera (to pick up weights)
        // and never recovers by itself; a fresh model finds them again on the next frame.
        flipped = if (pose != null && !pose.upright()) flipped + 1 else 0
        if (flipped >= FLIP_FRAMES) {
            Log.i(TAG, "camera: pose tracking restarted")
            model.close()
            landmarker = null
            flipped = 0
        }
        lastMs = tMs
        frames++
        poseMs += SystemClock.uptimeMillis() - began
        if (began - statsFrom >= STATS_MS) {
            if (statsFrom != 0L) Log.i(TAG, "camera: %.1f frames/s, pose %d ms on the %s, person %s".format(
                frames * 1000f / (began - statsFrom), poseMs / frames, if (useGpu) "graphics chip" else "processor",
                if (pose == null) "not found" else "found"))
            frames = 0
            poseMs = 0
            statsFrom = began
        }

        when (val event = tracker.add(tMs, pose)) {
            SetTracker.Ready -> say("Ready.")
            SetTracker.CantSee -> say("I can't see all of you. Step back.")
            is SetTracker.Counted -> {
                say(event.count.toString())
                event.cue?.let { say(it.cue) }
            }
            is SetTracker.Ended -> finish(event.report, speak = true)
            null -> Unit
        }
    }

    /** The pose model on the graphics chip when the phone can run it there, otherwise on the processor. */
    private fun create(): PoseLandmarker {
        fun on(delegate: Delegate) = PoseLandmarker.createFromOptions(
            context,
            PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).setDelegate(delegate).build())
                .setRunningMode(RunningMode.VIDEO)
                .setNumPoses(1)
                .build(),
        )
        if (useGpu) try {
            return on(Delegate.GPU)
        } catch (e: Exception) {
            Log.w(TAG, "camera: pose model will not start on the graphics chip, using the processor", e)
            useGpu = false
        }
        return on(Delegate.CPU)
    }

    /** The pose model or a frame failed: say so and close the set, rather than take the whole session down. Worker thread. */
    private fun broke(e: Exception) {
        if (over) return
        Log.e(TAG, "camera: stopped by an error", e)
        say("The camera stopped working.")
        finish(tracker.end(lastMs).report, speak = false)
    }

    /** Worker thread. */
    private fun finish(report: SetReport, speak: Boolean) {
        over = true
        Log.i(TAG, "camera: set over: ${report.facts()}")
        if (speak) say(report.spoken())
        landmarker?.close()
        landmarker = null
        worker.shutdown()
        // Ending the lifecycle is what closes the camera.
        main.post { if (registry.currentState != Lifecycle.State.INITIALIZED) registry.currentState = Lifecycle.State.DESTROYED }
        onEnd(report)
    }

    private companion object {
        const val TAG = "Eklavya"

        /** Cleared for the rest of the app's life the first time the graphics chip fails. */
        @Volatile var useGpu = true

        /** MediaPipe's pose model, "full" size: on the phone it is accurate enough for joint angles and fast enough to count. */
        const val MODEL = "pose_landmarker_full.task"
        val FRAME_SIZE = Size(640, 480)
        const val FLIP_FRAMES = 5
        const val STATS_MS = 5_000L

        /** A video file is sampled at about the rate the camera path delivers. */
        const val FILE_STEP_MS = 66L
    }
}
