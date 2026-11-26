package com.pragnyalabs.eklavya

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.pragnyalabs.eklavya.voice.CoachService
import com.pragnyalabs.eklavya.voice.WakeWord

/**
 * The app's one screen: start or end a workout session, see the three things a session needs (screen
 * control, the microphone, keys), and enter the keys. A session can only be started from here, because
 * Android lets an app begin using the microphone only while it is on screen.
 */
class MainActivity : Activity() {
    private val status by lazy { findViewById<TextView>(R.id.status) }
    private val session by lazy { findViewById<Button>(R.id.session) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        session.setOnClickListener {
            val service = Intent(this, CoachService::class.java)
            if (CoachService.instance == null) startForegroundService(service) else stopService(service)
        }
        findViewById<Button>(R.id.screen_action).setOnClickListener {
            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.mic_action).setOnClickListener {
            // Notifications are asked for in the same breath: the session shows one while it listens.
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        findViewById<Button>(R.id.save_keys).setOnClickListener {
            save(R.id.openai_key, "openai_key")
            save(R.id.jev_key, "jev_key")
            refresh()
        }
    }

    override fun onStart() {
        super.onStart()
        CoachService.onChange = ::refresh
        EklavyaService.instance?.overlay?.hidden = true
        refresh()
    }

    override fun onStop() {
        CoachService.onChange = null
        EklavyaService.instance?.overlay?.hidden = false
        super.onStop()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) = refresh()

    /** Stores a key if one was typed, then empties the field: a saved key is never shown again. */
    private fun save(field: Int, name: String) {
        val input = findViewById<EditText>(field)
        val value = input.text.toString().trim()
        if (value.isNotEmpty()) Settings.set(this, name, value)
        input.text.clear()
    }

    private fun refresh() {
        val screenOn = EklavyaService.instance != null
        val micOn = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val voiceKey = Settings.get(this, "openai_key") != null
        // Whichever decision model is selected must be fully set up, not just any key saved.
        val decisionKey = Settings.decider(this) != null
        val running = CoachService.instance != null

        row(R.id.screen_state, R.id.screen_action, screenOn)
        row(R.id.mic_state, R.id.mic_action, micOn)
        findViewById<TextView>(R.id.keys_state).setText(if (voiceKey && decisionKey) R.string.state_saved else R.string.state_missing_keys)
        hint(R.id.openai_key, voiceKey)
        hint(R.id.jev_key, decisionKey)

        val ready = screenOn && micOn && voiceKey && decisionKey
        session.isEnabled = ready || running
        session.setText(if (running) R.string.end_session else R.string.start_session)
        status.text = when {
            running && CoachService.current == CoachService.State.WAITING -> getString(R.string.status_waiting, WakeWord.PHRASE)
            running -> getString(R.string.status_talking)
            !ready -> getString(R.string.status_blocked)
            else -> getString(R.string.status_off)
        }
    }

    /** A setup row shows its state when it is done and its action when it is not, never both. */
    private fun row(state: Int, action: Int, done: Boolean) {
        findViewById<View>(state).visibility = if (done) View.VISIBLE else View.GONE
        findViewById<View>(action).visibility = if (done) View.GONE else View.VISIBLE
    }

    private fun hint(field: Int, saved: Boolean) {
        val input = findViewById<EditText>(field)
        input.setHint(if (saved) R.string.key_hint_saved else R.string.key_hint_empty)
        // A password field switches itself to a monospace face; keep the screen in one typeface.
        input.typeface = Typeface.DEFAULT
    }
}
