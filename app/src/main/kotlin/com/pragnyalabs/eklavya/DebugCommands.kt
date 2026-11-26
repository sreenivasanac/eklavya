package com.pragnyalabs.eklavya

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.util.Log
import com.pragnyalabs.eklavya.agent.AppNotes
import com.pragnyalabs.eklavya.agent.Goal
import com.pragnyalabs.eklavya.agent.Navigator
import com.pragnyalabs.eklavya.a11y.ScreenDriver
import com.pragnyalabs.eklavya.screen.ScreenText
import com.pragnyalabs.eklavya.voice.CoachService
import java.io.File
import java.util.concurrent.ExecutorService

/**
 * Lets a developer drive the service from a computer (debug builds only):
 *
 *     adb shell am broadcast -a com.pragnyalabs.eklavya.DEBUG -p com.pragnyalabs.eklavya --es cmd dump
 *     adb shell am broadcast ... --es cmd tap --ei n 3
 *     adb shell am broadcast ... --es cmd demo --es arg "squat"
 *     adb logcat -s Eklavya
 *
 * Commands: dump, tap n, text n arg, submit n, scroll n [arg=back], back, open arg, demo arg,
 * nav arg (an exercise name: the decision model finds and plays its demo), set arg (name=value, see Settings),
 * hear arg (a recording to play into the running voice session).
 */
object DebugCommands {
    const val ACTION = "com.pragnyalabs.eklavya.DEBUG"
    private const val TAG = "Eklavya"

    /** Only senders holding DUMP may use the receiver: that is adb's shell, not other apps on the phone. */
    private const val SENDER_PERMISSION = "android.permission.DUMP"

    fun register(service: AccessibilityService, driver: ScreenDriver, worker: ExecutorService): BroadcastReceiver {
        var last: ScreenText? = null // touched only on the worker thread
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val cmd = intent.getStringExtra("cmd") ?: return
                val n = intent.getIntExtra("n", -1)
                val arg = intent.getStringExtra("arg").orEmpty()
                worker.execute {
                    try {
                        // Numbers refer to the lines of the most recent dump.
                        val element = last?.elements?.getOrNull(n - 1)
                        val result: Any = when (cmd) {
                            "dump" -> driver.read().also { last = it }.text
                            "tap" -> element?.let(driver::tap) ?: "no element $n; run dump first"
                            "text" -> element?.let { driver.setText(it, arg) } ?: "no element $n; run dump first"
                            "submit" -> element?.let(driver::submit) ?: "no element $n; run dump first"
                            "scroll" -> element?.let { driver.scroll(it, forward = arg != "back") } ?: "no element $n; run dump first"
                            "back" -> driver.back()
                            "open" -> driver.openApp(arg)
                            "demo" -> TwentyFourGo(driver) { Log.i(TAG, "demo: $it") }.playDemo(arg)
                            "nav" -> navigate(service, driver, arg)
                            // Plays a recording into a running session as if spoken: arg is a 16 kHz mono PCM16 file
                            // in the app's files folder. This is how the voice path is tested without a person.
                            "hear" -> CoachService.instance?.let { it.hear(File(service.filesDir, arg).inputStream()); "playing $arg into the session" }
                                ?: "no session is running; start one in the app first"
                            // The value may be a key: say which setting changed, never what it was set to.
                            "set" -> {
                                Settings.set(service, arg.substringBefore('='), arg.substringAfter('=', ""))
                                "set ${arg.substringBefore('=')}"
                            }
                            else -> "unknown command"
                        }
                        result.toString().lines().forEach { Log.i(TAG, it) }
                        Log.i(TAG, "done $cmd")
                    } catch (e: Exception) {
                        Log.e(TAG, "$cmd failed", e)
                    }
                }
            }
        }
        service.registerReceiver(receiver, IntentFilter(ACTION), SENDER_PERMISSION, null, Context.RECEIVER_EXPORTED)
        return receiver
    }

    /** Asks the configured decision model to find and play the demo for [exercise] in 24GO. */
    private fun navigate(context: Context, driver: ScreenDriver, exercise: String): String {
        val decider = Settings.decider(context) ?: return "no decision model configured; send: set jev_key=..."
        val goal = Goal(GymApps.TWENTY_FOUR_GO, "Play the demo video for the exercise \"$exercise\".", exercise, AppNotes.TWENTY_FOUR_GO, AppNotes.TWENTY_FOUR_GO_HOME)
        val start = SystemClock.uptimeMillis()
        val outcome = Navigator(driver, decider) { Log.i(TAG, "nav: $it") }.run(goal)
        return "nav ${if (outcome.done) "done" else "stopped"} in ${SystemClock.uptimeMillis() - start} ms, " +
            "${outcome.steps.size} steps, ${outcome.inputTokens} tokens, $${"%.5f".format(outcome.costUsd)}"
    }
}
