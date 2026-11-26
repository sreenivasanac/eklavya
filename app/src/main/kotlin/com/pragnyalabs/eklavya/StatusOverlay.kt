package com.pragnyalabs.eklavya

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.pragnyalabs.eklavya.voice.CoachService
import com.pragnyalabs.eklavya.voice.WakeWord

/**
 * A small pill at the top of the screen, over whatever app is in front, saying what the coach is doing.
 * It is readable at a glance from a few steps away and never takes a touch: taps pass through to the app
 * underneath. It belongs to the accessibility service, so it needs no "draw over other apps" permission.
 * Call everything on the main thread.
 */
class StatusOverlay(private val service: AccessibilityService) {
    private val windows = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var pill: TextView? = null
    private var state: CoachService.State? = null
    private var error: String? = null
    private val clearError = Runnable {
        error = null
        render()
    }

    /** Call when Eklavya's own screen comes to the front or leaves it: the pill stays away while it is there. */
    fun refresh() = show(state, null)

    /** Shows [state], or hides the pill when it is null. An [error] replaces the text for a few seconds. */
    fun show(state: CoachService.State?, error: String?) {
        this.state = state
        if (error != null) {
            this.error = error
            handler.removeCallbacks(clearError)
            handler.postDelayed(clearError, ERROR_MS)
        }
        if (state == null || MainActivity.inFront) {
            pill?.let(windows::removeView)
            pill = null
            return
        }
        render()
    }

    private fun render() {
        val state = state ?: return
        val error = error
        val view = pill ?: create().also { pill = it }
        val (label, dot) = when {
            error != null -> error to R.color.danger
            state == CoachService.State.WAITING -> service.getString(R.string.overlay_waiting, WakeWord.PHRASE) to R.color.muted
            state == CoachService.State.CONNECTING -> service.getString(R.string.overlay_connecting) to R.color.muted
            state == CoachService.State.WORKING -> service.getString(R.string.overlay_working) to R.color.accent
            state == CoachService.State.COUNTING -> service.getString(R.string.overlay_counting) to R.color.accent
            else -> service.getString(R.string.overlay_talking) to R.color.accent
        }
        // The dot's colour is the signal: grey while idle, amber while the coach is with you.
        view.text = SpannableString("●  $label").apply {
            setSpan(ForegroundColorSpan(service.getColor(dot)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun create(): TextView {
        val density = service.resources.displayMetrics.density
        val view = TextView(service).apply {
            setBackgroundResource(R.drawable.overlay_pill)
            setTextColor(service.getColor(R.color.text))
            textSize = 16f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            val h = (16 * density).toInt()
            val v = (10 * density).toInt()
            setPadding(h, v, h, v)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = service.resources.getDimensionPixelSize(R.dimen.overlay_top)
        }
        windows.addView(view, params)
        return view
    }

    private companion object {
        const val ERROR_MS = 6000L
    }
}
