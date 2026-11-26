package com.pragnyalabs.eklavya

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.view.accessibility.AccessibilityEvent
import com.pragnyalabs.eklavya.a11y.ScreenDriver
import com.pragnyalabs.eklavya.voice.CoachService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** The accessibility service through which Eklavya reads and operates other apps, and shows its status pill. */
class EklavyaService : AccessibilityService() {
    lateinit var driver: ScreenDriver
        private set

    /** The status pill drawn over other apps; main thread only. */
    lateinit var overlay: StatusOverlay
        private set

    /** Screen work blocks while it waits for the screen to settle, so it runs here, one job at a time. */
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private var debugReceiver: BroadcastReceiver? = null

    override fun onServiceConnected() {
        driver = ScreenDriver(this)
        overlay = StatusOverlay(this)
        instance = this
        // A session may already be running if this service was switched off and on again.
        if (CoachService.instance != null) overlay.show(CoachService.current, null)
        // Eklavya's screen may be open, waiting for this switch to be turned on.
        CoachService.onChange?.invoke()
        // Driving the service from adb is a development aid only; release builds have no such door.
        if (BuildConfig.DEBUG) debugReceiver = DebugCommands.register(this, driver, worker)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (::driver.isInitialized) driver.onEvent()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        if (::overlay.isInitialized) overlay.show(null, null)
        debugReceiver?.let(::unregisterReceiver)
        worker.shutdownNow()
        super.onDestroy()
    }

    companion object {
        /** The running service, or null while it is switched off in Android's accessibility settings. */
        @Volatile
        var instance: EklavyaService? = null
            private set
    }
}
