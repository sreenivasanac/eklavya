package com.pragnyalabs.eklavya.voice

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One spoken exchange with OpenAI's GPT-Live over a WebSocket.
 *
 * GPT-Live does the talking and listening; a second, text model (the "backend") decides which tool to call.
 * Audio in both directions is raw 16-bit mono at [SAMPLE_RATE], sent as base64 inside JSON events. The session
 * is billed for every second it is open, so it is opened after the wake word and closed when the talk ends.
 * Protocol: developers.openai.com/api/docs/guides/voice-websockets and /live-delegation.
 */
class LiveSession(
    private val apiKey: String,
    private val model: String,
    private val backendModel: String,
    private val voice: String,
    private val tools: JSONArray,
    private val listener: Listener,
) {
    interface Listener {
        fun onStarted()
        /** Speech from the coach to play, in order. The stream runs on through silences. */
        fun onAudio(pcm: ByteArray)
        fun onUserText(text: String)
        fun onCoachText(text: String)
        /** The backend wants a tool run; answer with [sendToolResult] using the same [callId]. */
        fun onToolCall(callId: String, name: String, arguments: JSONObject)
        /** The session is over; [seconds] is what was billed, when the server said. */
        fun onClosed(seconds: Int?)
        fun onError(message: String)
    }

    private var socket: WebSocket? = null
    private val backlog = ArrayDeque<String>()
    private val ended = AtomicBoolean(false)

    @Volatile
    private var open = false

    fun start() {
        val request = Request.Builder().url(URL).header("Authorization", "Bearer $apiKey").build()
        socket = CLIENT.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(startEvent().toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handle(JSONObject(text))

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // A failure after the session has ended is just the socket going away.
                if (!ended.get()) listener.onError(response?.let { "HTTP ${it.code}" } ?: (t.message ?: "connection lost"))
                end(null)
            }

            // The server hung up first: acknowledge, so the connection is released.
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
                end(null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = end(null)
        })
    }

    /** Reports the end exactly once, however many ways the connection winds down. */
    private fun end(seconds: Int?) {
        open = false
        if (ended.compareAndSet(false, true)) listener.onClosed(seconds)
    }

    /**
     * Sends microphone audio. People keep talking after the wake phrase while the session is still connecting,
     * so audio given before it has started is held (up to [BACKLOG_FRAMES]) and sent first once it has.
     */
    fun sendAudio(pcm: ByteArray, length: Int) {
        val event = JSONObject().put("type", "session.input_audio.append")
            .put("audio", Base64.encodeToString(pcm, 0, length, Base64.NO_WRAP)).toString()
        synchronized(backlog) {
            if (!open) {
                if (backlog.size == BACKLOG_FRAMES) backlog.removeFirst()
                backlog.addLast(event)
                return
            }
        }
        socket?.send(event)
    }

    fun sendToolResult(callId: String, output: JSONObject) {
        val item = JSONObject().put("type", "function_call_output").put("call_id", callId).put("output", output.toString())
        socket?.send(JSONObject().put("type", "response.item.create").put("item", item).toString())
        // Adding the result does not resume the backend; this does.
        socket?.send(JSONObject().put("type", "response.create").toString())
    }

    /** Asks the server to end the session; [Listener.onClosed] follows with the billed time. */
    fun close() {
        if (!open) {
            socket?.cancel()
            return
        }
        socket?.send(JSONObject().put("type", "session.close").toString())
    }

    private fun handle(event: JSONObject) {
        when (event.optString("type")) {
            "session.started" -> {
                synchronized(backlog) {
                    backlog.forEach { socket?.send(it) }
                    backlog.clear()
                    open = true
                }
                listener.onStarted()
            }
            "session.output_audio.delta" -> listener.onAudio(Base64.decode(event.getString("delta"), Base64.DEFAULT))
            "session.input_transcript.delta" -> listener.onUserText(event.optString("delta"))
            "session.output_transcript.delta" -> listener.onCoachText(event.optString("delta"))
            "response.event" -> {
                // Backend events arrive wrapped; a finished function call is the only one acted on.
                val inner = event.optJSONObject("event") ?: return
                val item = inner.optJSONObject("item") ?: return
                if (inner.optString("type") == "response.output_item.done" && item.optString("type") == "function_call") {
                    val arguments = runCatching { JSONObject(item.optString("arguments", "{}")) }.getOrDefault(JSONObject())
                    listener.onToolCall(item.getString("call_id"), item.getString("name"), arguments)
                }
            }
            "session.closed" -> {
                val seconds = event.optJSONObject("usage")?.optInt("seconds", -1)?.takeIf { it >= 0 }
                end(seconds)
                socket?.close(1000, null)
            }
            "error" -> listener.onError(event.optJSONObject("error")?.optString("message") ?: event.toString().take(200))
        }
    }

    private fun startEvent(): JSONObject {
        val backend = JSONObject()
            .put("model", backendModel)
            .put("instructions", BACKEND_INSTRUCTIONS)
            .put("tools", tools)
            .put("tool_choice", "auto")
        val session = JSONObject()
            .put("model", model)
            .put("instructions", COACH_INSTRUCTIONS)
            .put("audio", JSONObject()
                .put("format", JSONObject().put("type", "audio/pcm").put("rate", SAMPLE_RATE))
                .put("output", JSONObject().put("voice", voice)))
            .put("delegation", JSONObject().put("type", "responses").put("responses", backend))
        return JSONObject().put("type", "session.start").put("session", session)
    }

    companion object {
        const val SAMPLE_RATE = 16000

        /** Ten seconds of 100 ms frames: more than a connection ever takes. */
        private const val BACKLOG_FRAMES = 100
        private const val URL = "wss://api.openai.com/v1/live/sessions"

        /**
         * How the coach talks. The owner's brief: direct and to the point, like a coach standing beside you.
         * "Sure" is a fine reply; "I am opening the video" and "the video has opened" are not, because the phone
         * already shows it. Encouragement is welcome where it fits and never as a reflex.
         */
        private const val COACH_INSTRUCTIONS =
            "You are Drona, the user's gym coach, in their earphones while they train. Talk like a good coach standing " +
                "next to them: direct, few words, plain. Never describe what you are doing or what the phone is doing. " +
                "While the backend works on something, say at most one word, such as \"Sure.\"; never say that you are " +
                "checking, looking, opening or getting anything.\n" +
                "- When they ask to see or pull up an exercise: say one word, such as \"Sure.\", and hand it to the backend, " +
                "which controls their phone. If the backend answers \"playing\", say nothing at all: the video is the answer. " +
                "If it could not be done, say why in one short sentence.\n" +
                "- When they ask you to count their reps or watch their form: hand it to the backend. If it answers " +
                "\"counting\", say only \"Go ahead.\" and then nothing more: never count reps or comment during the set " +
                "yourself, because the phone counts aloud and you cannot see the user. If it could not start, " +
                "say why in one short sentence.\n" +
                "- When they ask how their last set went: ask the backend for the set report and answer from its facts " +
                "in one or two short sentences. Do not add faults it does not list.\n" +
                "- A question about technique or training: answer in one or two short sentences, the most useful cue first.\n" +
                "- When they tell you a set was hard, that they finished, or that they are tired: one short, sincere line " +
                "that fits what they said, then stop. Encourage like a person would, not on every turn, and do not repeat " +
                "a phrase you have already used.\n" +
                "- No lists, no offers of more help, no questions unless a detail you need is missing."

        private const val BACKEND_INSTRUCTIONS =
            "You carry out the gym coach's actions on the user's phone with the tools. Call the tool the request needs, " +
                "passing the exercise name as the user said it. If the tool result's status is \"playing\", reply with the " +
                "single word: playing. If it is \"counting\", reply with the single word: counting. If the result has a report, " +
                "reply with the report as given. Otherwise reply with the result's reason in one short sentence, as given; do not " +
                "guess at other causes or suggest steps of your own. Never claim something was done unless the result says so."

        private val CLIENT = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
    }
}
