package com.pragnyalabs.eklavya.screen

/**
 * Reading a screen and acting on it. The Android implementation is `ScreenDriver`; tests replay recorded
 * screens through a fake. Every action returns false when it could not be carried out (the element is gone,
 * the list is at its end, the app is not installed).
 */
interface ScreenControl {
    /** The screen as text. With [onlyPackage], windows of other apps are left out. */
    fun read(onlyPackage: String? = null): ScreenText
    fun tap(e: Element): Boolean
    fun setText(e: Element, text: String): Boolean
    fun submit(e: Element): Boolean
    fun scroll(e: Element, forward: Boolean): Boolean
    fun back(): Boolean
    fun openApp(packageName: String): Boolean
}
