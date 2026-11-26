package com.pragnyalabs.eklavya.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.pragnyalabs.eklavya.screen.Element
import com.pragnyalabs.eklavya.screen.ScreenControl
import com.pragnyalabs.eklavya.screen.ScreenText

/**
 * Reads the screen as [ScreenText] and acts on its numbered elements.
 *
 * Every action first finds the live element again and returns false when the screen has moved on, and then
 * waits for the screen to settle. All methods block, so call them from a worker thread, never the main thread.
 * The owning service must forward its accessibility events to [onEvent].
 */
class ScreenDriver(private val service: AccessibilityService) : ScreenControl {
    private val reader = ScreenReader(service)

    @Volatile
    private var lastEvent = 0L

    /** Call from AccessibilityService.onAccessibilityEvent: tells the driver the screen is still changing. */
    fun onEvent() {
        lastEvent = SystemClock.uptimeMillis()
    }

    override fun read(onlyPackage: String?): ScreenText = ScreenText.of(reader.read(), onlyPackage)

    /**
     * Reads the screen until [pick] finds what it is looking for, or gives up after [timeoutMs]. Use it after an
     * action that opens a new page: the page can arrive (or finish loading) after the screen first goes quiet.
     */
    fun <T : Any> waitFor(timeoutMs: Long = WAIT_FOR_MS, pick: (ScreenText) -> T?): T? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            pick(read())?.let { return it }
            if (SystemClock.uptimeMillis() >= deadline) return null
            SystemClock.sleep(RETRY_MS)
        }
    }

    /** Taps [e], or the clickable row or card it sits in. */
    override fun tap(e: Element): Boolean = act {
        var target: AccessibilityNodeInfo? = reader.live(e) ?: return@act false
        while (target != null && !target.isClickable) target = target.parent
        // Apps that draw their own widgets may ignore the click action; a real touch at the spot still works.
        target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true || touch(e.x, e.y)
    }

    /** Replaces the text of an editable element. */
    override fun setText(e: Element, text: String): Boolean = act {
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        reader.live(e)?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) == true
    }

    /** Presses the keyboard's enter / search / go key for an editable element. */
    override fun submit(e: Element): Boolean = act {
        reader.live(e)?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) == true
    }

    /** Scrolls a scrollable element one page; false when it is already at that end. */
    override fun scroll(e: Element, forward: Boolean): Boolean = act {
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        reader.live(e)?.performAction(action) == true
    }

    override fun back(): Boolean = act { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }

    /** Brings an installed app to the front; false when it is not installed or not visible to this app. */
    override fun openApp(packageName: String): Boolean = act {
        val intent = service.packageManager.getLaunchIntentForPackage(packageName) ?: return@act false
        service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }

    /**
     * Waits until no accessibility event has arrived for [QUIET_MS], or [MAX_WAIT_MS] at most (a video or an
     * animation never goes quiet).
     */
    fun settle() {
        val start = SystemClock.uptimeMillis()
        while (true) {
            val now = SystemClock.uptimeMillis()
            if (now - maxOf(lastEvent, start) >= QUIET_MS || now - start >= MAX_WAIT_MS) return
            SystemClock.sleep(POLL_MS)
        }
    }

    private inline fun act(action: () -> Boolean): Boolean {
        val done = action()
        if (done) settle()
        return done
    }

    private fun touch(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_MS))
            .build()
        return service.dispatchGesture(gesture, null, null)
    }

    private companion object {
        const val QUIET_MS = 400L
        const val MAX_WAIT_MS = 3000L
        const val POLL_MS = 50L
        const val WAIT_FOR_MS = 8000L
        const val RETRY_MS = 250L
        const val TAP_MS = 50L
    }
}
