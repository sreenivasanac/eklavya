package com.pragnyalabs.eklavya.voice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.pragnyalabs.eklavya.BuildConfig
import com.pragnyalabs.eklavya.EklavyaService
import com.pragnyalabs.eklavya.MainActivity
import com.pragnyalabs.eklavya.R
import com.pragnyalabs.eklavya.Settings
import com.pragnyalabs.eklavya.camera.SetWatcher
import com.pragnyalabs.eklavya.motion.Exercise
import com.pragnyalabs.eklavya.motion.SetReport
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * A workout session: listens on the phone for the wake phrase, then holds one spoken exchange with the coach
 * and goes back to listening. Runs as a foreground service so it keeps going while the gym's app is in front.
 *
 * Nothing is sent anywhere until the wake phrase is heard. An exchange ends after a few seconds without
 * anyone speaking, because the voice session is billed for every second it is open.
 */
class CoachService : Service() {
    enum class State { WAITING, CONNECTING, TALKING, WORKING, COUNTING }

    private val main = Handler(Looper.getMainLooper())
    private val tools = Executors.newSingleThreadExecutor()
    private val speech = LinkedBlockingQueue<ByteArray>()
    private val toolsRunning = AtomicInteger()

    @Volatile private var running = false
    @Volatile private var session: LiveSession? = null
    @Volatile private var lastActivity = 0L
    @Volatile private var lastCoachSound = 0L
    @Volatile private var exchangeStart = 0L
    @Volatile private var headphones = false
    @Volatile private var injected: InputStream? = null

    /** When set, the exchange closes at this time whatever is being said: a set is being counted. */
    @Volatile private var closeAt = 0L

    /** What the voice exchange is doing; [current] also knows about a set being counted. */
    @Volatile private var exchange = State.WAITING

    /** The phone's speech engine, for what is said during a set (counts and cues): no voice session is open then. */
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var watcher: SetWatcher? = null
    @Volatile private var cameraAllowed = false

    /** The last set the camera counted, for when the user asks the coach about it. */
    @Volatile var lastSet: SetReport? = null
        private set

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Android lets a background service use the camera only if it said so when it started, and it may
        // say so only once the permission is granted.
        cameraAllowed = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        startForeground(NOTIFICATION_ID, notification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or if (cameraAllowed) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0)
        if (!running) {
            running = true
            instance = this
            thread(name = "coach-listen") { listen() }
            thread(name = "coach-speak") { speak() }
            startSpeechEngine()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        instance = null
        session?.close()
        watcher?.stop()
        tts?.shutdown()
        tools.shutdownNow()
        publish(null, null)
        super.onDestroy()
    }

    /** Debug aid: plays a 16 kHz mono PCM16 stream into the session as if it came from the microphone. */
    fun hear(audio: InputStream) {
        injected = audio
    }

    // --- watching a set --------------------------------------------------------------------------------

    /**
     * Starts counting a set of [exercise] through the front camera (or, for a test, from a video [file]).
     * Returns null once it is watching, or the reason it cannot.
     */
    fun watch(exercise: Exercise, file: File? = null): String? {
        if (file == null && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            return "the camera is not allowed yet: allow it in the Eklavya app, then start the session again"
        if (file == null && !cameraAllowed)
            return "the camera was allowed after this session started: open the Eklavya app once, or end the session and start it again"
        watcher?.stop()
        lateinit var mine: SetWatcher
        mine = SetWatcher(this, exercise, ::say) { report ->
            // A watch that saw no reps (nobody in view, or stopped at once) does not replace the last real set.
            if (report.reps > 0) lastSet = report
            if (watcher === mine) watcher = null
            publish(null, null)
        }
        watcher = mine
        if (file != null) mine.playFile(file) else main.post { mine.startCamera() }
        publish(null, null)
        return null
    }

    private fun startSpeechEngine() {
        lateinit var engine: TextToSpeech
        engine = TextToSpeech(this) { status ->
            if (status != TextToSpeech.SUCCESS) {
                Log.e(TAG, "speech engine failed to start: $status")
                return@TextToSpeech
            }
            engine.language = Locale.US
            engine.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            // On the phone's speaker the microphone hears the counting; keep it out of an open voice exchange.
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { lastCoachSound = SystemClock.uptimeMillis() }
                override fun onDone(id: String?) { lastCoachSound = SystemClock.uptimeMillis() }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) = Unit
            })
            tts = engine
        }
    }

    /** Says a short line with the phone's own voice. */
    private fun say(text: String) {
        Log.i(TAG, "camera: says: $text")
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, text) ?: Log.e(TAG, "speech engine not ready")
    }

    // --- listening -------------------------------------------------------------------------------------

    private fun listen() {
        val wake = WakeWord(assets)
        val minBuffer = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        @Suppress("MissingPermission") // checked in onStartCommand
        val mic = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, FRAME * 8))
        val frame = ShortArray(FRAME)
        val bytes = ByteArray(FRAME * 2)
        val silence = ByteArray(FRAME * 2)
        val recent = ArrayDeque<ByteArray>()
        try {
            mic.startRecording()
            publish(State.WAITING, null)
            while (running) {
                val count = read(mic, frame)
                if (count < 0) {
                    // The recorder has failed (another app took the microphone, or it died): stop cleanly.
                    Log.e(TAG, "microphone read failed: $count")
                    session?.close()
                    main.post { stopSelf() }
                    break
                }
                if (count == 0) continue
                val live = session
                if (live == null) {
                    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(frame, 0, count)
                    recent.addLast(bytes.copyOf(count * 2))
                    if (recent.size > PRE_ROLL_FRAMES) recent.removeFirst()
                    if (wake.heard(frame, count)) {
                        begin()
                        // The wake phrase is recognised a moment after it is said, and people do not pause after
                        // it: without the last few seconds, "Hey Drona, count my squats" arrives as "my squats".
                        session?.let { started -> recent.forEach { started.sendAudio(it, it.size) } }
                        recent.clear()
                    }
                } else {
                    // On the phone's own speaker the microphone hears the coach; do not send the coach back to itself.
                    val echo = !headphones && SystemClock.uptimeMillis() - lastCoachSound < ECHO_GUARD_MS
                    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(frame, 0, count)
                    live.sendAudio(if (echo) silence else bytes, count * 2)
                    endIfQuiet(live)
                    if (session == null) wake.reset()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "listening stopped", e)
        } finally {
            mic.release()
            wake.release()
        }
    }

    /** One frame from the microphone, or from the injected test audio at the pace a microphone would give it. */
    private fun read(mic: AudioRecord, frame: ShortArray): Int {
        val test = injected ?: return mic.read(frame, 0, frame.size)
        val raw = ByteArray(frame.size * 2)
        val got = test.read(raw)
        if (got <= 0) {
            test.close()
            injected = null
            return 0
        }
        ByteBuffer.wrap(raw, 0, got).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(frame, 0, got / 2)
        SystemClock.sleep(FRAME * 1000L / RATE)
        return got / 2
    }

    // --- one exchange ----------------------------------------------------------------------------------

    private fun begin() {
        val key = Settings.get(this, "openai_key")
        if (key == null) {
            publish(State.WAITING, getString(R.string.error_no_voice_key))
            return
        }
        Log.i(TAG, "voice: wake phrase heard")
        ToneGenerator(AudioManager.STREAM_MUSIC, 70).startTone(ToneGenerator.TONE_PROP_ACK, 150)
        headphones = getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADPHONE_TYPES }
        exchangeStart = SystemClock.uptimeMillis()
        closeAt = 0
        lastActivity = exchangeStart
        publish(State.CONNECTING, null)
        // The listener needs the session it belongs to: a slow tool may finish after a newer exchange has begun.
        lateinit var live: LiveSession
        live = LiveSession(
            key, Settings.get(this, "voice_model") ?: DEFAULT_VOICE_MODEL, Settings.get(this, "voice_backend_model") ?: DEFAULT_BACKEND_MODEL,
            Settings.get(this, "voice") ?: DEFAULT_VOICE, CoachTools.DECLARATIONS, object : LiveSession.Listener {
                override fun onStarted() {
                    lastActivity = SystemClock.uptimeMillis()
                    publish(State.TALKING, null)
                }

                override fun onAudio(pcm: ByteArray) {
                    if (loud(pcm)) lastCoachSound = SystemClock.uptimeMillis()
                    speech.offer(pcm)
                }

                override fun onUserText(text: String) {
                    lastActivity = SystemClock.uptimeMillis()
                    if (BuildConfig.DEBUG) Log.i(TAG, "voice: user: $text")
                }

                override fun onCoachText(text: String) {
                    lastActivity = SystemClock.uptimeMillis()
                    if (BuildConfig.DEBUG) Log.i(TAG, "voice: coach: $text")
                }

                override fun onToolCall(callId: String, name: String, arguments: JSONObject) {
                    toolsRunning.incrementAndGet()
                    publish(State.WORKING, null)
                    Log.i(TAG, "voice: tool $name $arguments")
                    tools.execute {
                        val result = CoachTools.run(this@CoachService, name, arguments)
                        Log.i(TAG, "voice: tool result $result")
                        val current = session === live
                        if (current) live.sendToolResult(callId, result)
                        // Once a set is being counted the phone does the talking: leave the coach time for
                        // "Go ahead" and close, so the exchange is not billed, or chatted over, for the whole set.
                        if (current && result.optString("status") == "counting") closeAt = SystemClock.uptimeMillis() + GO_AHEAD_MS
                        if (current) lastActivity = SystemClock.uptimeMillis()
                        if (toolsRunning.decrementAndGet() == 0 && current) publish(State.TALKING, null)
                    }
                }

                override fun onClosed(seconds: Int?) {
                    Log.i(TAG, "voice: session closed" + (seconds?.let { ", $it s billed" } ?: ""))
                    if (session !== live) return
                    session = null
                    speech.clear()
                    publish(State.WAITING, null)
                }

                override fun onError(message: String) {
                    Log.e(TAG, "voice: $message")
                    publish(null, message)
                }
            },
        )
        session = live
        live.start()
    }

    /** Closes the exchange once nobody has spoken for a while and no tool is running, or when it has run too long. */
    private fun endIfQuiet(live: LiveSession) {
        val now = SystemClock.uptimeMillis()
        val quiet = toolsRunning.get() == 0 && now - lastActivity > QUIET_MS && now - lastCoachSound > QUIET_MS / 2
        if (quiet || now - exchangeStart > MAX_EXCHANGE_MS || (closeAt != 0L && now > closeAt)) {
            closeAt = 0
            lastActivity = now + CLOSE_GRACE_MS // do not ask twice while the server finishes up
            live.close()
        }
    }

    private fun loud(pcm: ByteArray): Boolean {
        var i = 0
        while (i + 1 < pcm.size) {
            if (abs((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)) > LOUD) return true
            i += 64
        }
        return false
    }

    // --- speaking --------------------------------------------------------------------------------------

    private fun speak() {
        val format = AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(format)
            .setBufferSizeInBytes(AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            track.play()
            while (running) {
                val pcm = speech.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                track.write(pcm, 0, pcm.size)
            }
        } catch (e: InterruptedException) {
            // service is stopping
        } finally {
            track.release()
        }
    }

    // --- status ----------------------------------------------------------------------------------------

    /** Tells the overlay and the app's screen what is going on. A null [state] keeps the current one. */
    private fun publish(state: State?, error: String?) {
        if (state != null) exchange = state
        // Between exchanges, a set being counted is the thing to show.
        current = if (watcher != null && exchange == State.WAITING) State.COUNTING else exchange
        val shown = if (running) current else null
        main.post {
            EklavyaService.instance?.overlay?.show(shown, error)
            onChange?.invoke()
        }
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.channel_session), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_coach)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text, WakeWord.PHRASE))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "Eklavya"
        private const val CHANNEL = "session"
        private const val NOTIFICATION_ID = 1
        private const val RATE = LiveSession.SAMPLE_RATE

        /** 100 ms of audio per frame. */
        private const val FRAME = RATE / 10

        /** How much audio from before the wake phrase was recognised goes to the coach: 2.5 seconds. */
        private const val PRE_ROLL_FRAMES = 25

        /** An exchange ends after this long without speech from either side. */
        private const val QUIET_MS = 7_000L
        private const val CLOSE_GRACE_MS = 20_000L
        private const val GO_AHEAD_MS = 4_000L

        /** No single exchange runs longer than this, whatever happens: the meter is running. */
        private const val MAX_EXCHANGE_MS = 180_000L
        // ponytail: timed from when the coach's audio arrives, not from when it leaves the speaker; the margin
        // covers the playback buffer. Track the playback position if the coach starts answering itself.
        private const val ECHO_GUARD_MS = 800L
        private const val LOUD = 600

        const val DEFAULT_VOICE_MODEL = "gpt-live-1.1"
        const val DEFAULT_BACKEND_MODEL = "gpt-5.6-luna"
        const val DEFAULT_VOICE = "marin"

        private val HEADPHONE_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
        )

        @Volatile var instance: CoachService? = null
            private set

        /** What the running session is doing; meaningful only while [instance] is set. */
        @Volatile var current: State = State.WAITING
            private set

        /** Called on the main thread whenever the session starts, stops or changes state. */
        var onChange: (() -> Unit)? = null
    }
}
