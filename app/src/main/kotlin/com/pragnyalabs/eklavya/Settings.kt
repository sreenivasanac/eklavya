package com.pragnyalabs.eklavya

import android.content.Context
import com.pragnyalabs.eklavya.agent.ChatDecider
import com.pragnyalabs.eklavya.agent.Decider
import com.pragnyalabs.eklavya.agent.JevDecider

/**
 * What the user has chosen, kept in the app's private storage.
 *
 *   decider    "jev" (default: TypeSafe Jev, cloud) or "chat" (an OpenAI-style endpoint, cloud or on-device)
 *   jev_key    TypeSafe API key
 *   chat_url   base URL of the chat endpoint: https://, or http://127.0.0.1:PORT/v1 for a model served on the
 *              phone (plain http to any other host is refused)
 *   chat_model model name at that endpoint
 *   chat_key   key for that endpoint; empty for a local one
 *
 * ponytail: keys sit in plain SharedPreferences (private to the app, excluded from backup). Wrap them with
 * an Android Keystore key before this is installed on anyone else's phone.
 */
object Settings {
    private const val FILE = "settings"
    private val NAMES = setOf("decider", "jev_key", "chat_url", "chat_model", "chat_key")
    private val LOCAL = Regex("""^http://(127\.0\.0\.1|localhost)(:\d+)?(/|$)""")

    fun set(context: Context, name: String, value: String) {
        require(name in NAMES) { "unknown setting \"$name\"; known: $NAMES" }
        // Screen text and a key travel to this address: plain http is accepted only for a model on this phone
        // (res/xml/network_security_config.xml enforces the same rule at the network layer).
        require(name != "chat_url" || value.isEmpty() || value.startsWith("https://") || LOCAL.containsMatchIn(value)) {
            "chat_url must be https://, or http://127.0.0.1 / http://localhost for a model on this phone"
        }
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(name, value).apply()
    }

    /** The decision model the user chose, or null when it is not fully configured. */
    fun decider(context: Context): Decider? {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        fun get(name: String) = p.getString(name, null)?.takeIf { it.isNotBlank() }
        return when (get("decider") ?: "jev") {
            "chat" -> ChatDecider(get("chat_url") ?: return null, get("chat_model") ?: return null, get("chat_key"))
            else -> JevDecider(get("jev_key") ?: return null)
        }
    }
}
