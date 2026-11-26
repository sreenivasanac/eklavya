package com.pragnyalabs.eklavya

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.pragnyalabs.eklavya.a11y.ScreenDriver
import com.pragnyalabs.eklavya.screen.ScreenText
import java.util.concurrent.ExecutorService

/**
 * Lets a developer drive the service from a computer (debug builds only):
 *
 *     adb shell am broadcast -a com.pragnyalabs.eklavya.DEBUG -p com.pragnyalabs.eklavya --es cmd dump
 *     adb shell am broadcast ... --es cmd tap --ei n 3
 *     adb shell am broadcast ... --es cmd demo --es arg "squat"
 *     adb logcat -s Eklavya
 *
 * Commands: dump, tap n, text n arg, submit n, scroll n [arg=back], back, open arg, demo arg.
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
}
