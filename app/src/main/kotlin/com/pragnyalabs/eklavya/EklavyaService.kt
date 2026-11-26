package com.pragnyalabs.eklavya

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.view.accessibility.AccessibilityEvent
import com.pragnyalabs.eklavya.a11y.ScreenDriver
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** The accessibility service through which Eklavya reads and operates other apps. */
class EklavyaService : AccessibilityService() {
    private lateinit var driver: ScreenDriver

    /** Screen work blocks while it waits for the screen to settle, so it runs here, one job at a time. */
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private var debugReceiver: BroadcastReceiver? = null

    override fun onServiceConnected() {
        driver = ScreenDriver(this)
        // Driving the service from adb is a development aid only; release builds have no such door.
        if (BuildConfig.DEBUG) debugReceiver = DebugCommands.register(this, driver, worker)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (::driver.isInitialized) driver.onEvent()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        debugReceiver?.let(::unregisterReceiver)
        worker.shutdownNow()
        super.onDestroy()
    }
}
