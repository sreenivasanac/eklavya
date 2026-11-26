package com.pragnyalabs.eklavya.agent

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

/**
 * TypeSafe Jev, a decision model: it is sent the situation and a choice question and returns one option with
 * calibrated probabilities. It produces no free text, so it cannot invent an action that was not offered.
 */
class JevDecider(
    private val apiKey: String,
    private val model: String = MODEL,
    private val endpoint: String = ENDPOINT,
) : Decider {
    override fun decide(request: DecisionRequest): Decision {
        val state = JSONObject()
            .put("task", request.task)
            .put("notes_about_the_app", JSONArray(request.notes))
            .put("actions_already_taken", JSONArray(request.history))
            .put("current_screen", request.screen)
        val question = JSONObject()
            .put("type", "choice")
            .put("instructions", Decider.INSTRUCTIONS)
            .put("criteria", JSONObject(request.options))
        // Both questions are answered against the same state in one round trip.
        val commitment = JSONObject().put("type", "noul").put("instructions", Decider.COMMITMENT)
        val questions = JSONObject().put(QUESTION, question).put(COMMITMENT, commitment)
        val body = JSONObject().put("model", model).put("state", state).put("questions", questions)

        val reply = postJson(endpoint, apiKey, body)
        val answer = reply.getJSONObject("answers").getJSONObject(QUESTION)
        val tokens = reply.optJSONObject("usage")?.optInt("input_tokens") ?: 0
        // No answer to the safety question is treated as a yes: the navigator hands back rather than guess.
        val asks = reply.getJSONObject("answers").optJSONObject(COMMITMENT)?.optDouble("noul", 1.0) ?: 1.0
        return Decision(answer.getString("choice"), answer.optDouble("confidence", 1.0), tokens, tokens * USD_PER_INPUT_TOKEN, asks)
    }

    companion object {
        const val ENDPOINT = "https://api.typesafe.ai/v1/systemone"
        const val MODEL = "jev-latest"
        private const val QUESTION = "next"
        private const val COMMITMENT = "commitment"

        /** Jev bills input tokens only: $0.042 per million (docs.typesafe.ai/models, November 2026). */
        const val USD_PER_INPUT_TOKEN = 0.042 / 1_000_000
    }
}

/**
 * Any chat model behind an OpenAI-style `/chat/completions` endpoint: a cloud model, or one running on the
 * device or a laptop (llama.cpp, Ollama, LiteRT-LM servers all speak it). The answer is held to the list of
 * option ids with a JSON schema, so the model cannot reply with anything else.
 */
class ChatDecider(
    private val baseUrl: String,
    private val model: String,
    private val apiKey: String? = null,
    /** Dollars per million input and output tokens, for cost reporting; zero for a local model. */
    private val usdPerMInput: Double = 0.0,
    private val usdPerMOutput: Double = 0.0,
) : Decider {
    override fun decide(request: DecisionRequest): Decision {
        val user = buildString {
            append("Task: ").append(request.task).append("\n\nNotes about the app:\n")
            request.notes.forEach { append("- ").append(it).append('\n') }
            append("\nActions already taken:\n")
            if (request.history.isEmpty()) append("(none)\n") else request.history.forEachIndexed { i, h -> append(i + 1).append(". ").append(h).append('\n') }
            append("\nCurrent screen:\n").append(request.screen).append("\n\nActions you can choose:\n")
            request.options.forEach { (id, what) -> append(id).append(": ").append(what).append('\n') }
        }
        val schema = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject()
                .put("asks_for_commitment", JSONObject().put("type", "boolean").put("description", Decider.COMMITMENT))
                .put("action", JSONObject().put("type", "string").put("enum", JSONArray(request.options.keys))))
            .put("required", JSONArray(listOf("asks_for_commitment", "action")))
            .put("additionalProperties", false)
        val body = JSONObject()
            .put("model", model)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", Decider.INSTRUCTIONS + " Reply with JSON: {\"asks_for_commitment\": true or false, \"action\": \"<id>\"}."))
                .put(JSONObject().put("role", "user").put("content", user)))
            .put("response_format", JSONObject().put("type", "json_schema")
                .put("json_schema", JSONObject().put("name", "next_action").put("strict", true).put("schema", schema)))

        val reply = postJson(baseUrl.trimEnd('/') + "/chat/completions", apiKey, body)
        val content = reply.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        val usage = reply.optJSONObject("usage")
        val input = usage?.optInt("prompt_tokens") ?: 0
        val output = usage?.optInt("completion_tokens") ?: 0
        // Chat models give no calibrated probability; the schema guarantees a valid id, nothing more.
        val answer = JSONObject(content)
        val asks = if (answer.optBoolean("asks_for_commitment", true)) 1.0 else 0.0
        return Decision(answer.getString("action"), 1.0, input, (input * usdPerMInput + output * usdPerMOutput) / 1_000_000, asks)
    }
}

private const val TIMEOUT_MS = 20_000

/** POSTs [body] and returns the JSON reply; throws with the server's message on any non-200 answer. */
internal fun postJson(url: String, apiKey: String?, body: JSONObject): JSONObject {
    val http = URI(url).toURL().openConnection() as HttpURLConnection
    try {
        http.requestMethod = "POST"
        http.connectTimeout = TIMEOUT_MS
        http.readTimeout = TIMEOUT_MS
        http.doOutput = true
        http.setRequestProperty("Content-Type", "application/json")
        if (apiKey != null) http.setRequestProperty("Authorization", "Bearer $apiKey")
        http.outputStream.use { it.write(body.toString().toByteArray()) }
        val status = http.responseCode
        val text = (if (status in 200..299) http.inputStream else http.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        check(status in 200..299) { "HTTP $status from ${URI(url).host}: ${text.take(300)}" }
        return JSONObject(text)
    } finally {
        http.disconnect()
    }
}
