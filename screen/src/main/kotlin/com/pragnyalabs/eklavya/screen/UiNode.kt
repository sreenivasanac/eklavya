package com.pragnyalabs.eklavya.screen

/** A rectangle in screen pixels. */
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val isEmpty: Boolean get() = right <= left || bottom <= top

    fun contains(x: Int, y: Int): Boolean = x in left until right && y in top until bottom
}

/**
 * One element of a screen, copied out of the platform's accessibility tree so the rest of the code
 * (and its tests) needs no Android classes.
 */
data class UiNode(
    /** Child indexes from the window's root down to this node; used to find the live node again before acting. */
    val path: List<Int>,
    val className: String?,
    val text: String?,
    val contentDescription: String?,
    val hint: String?,
    /** The view id without its package, e.g. "searchInput"; null when the app gives none. */
    val viewId: String?,
    val bounds: Bounds,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val enabled: Boolean = true,
    val focused: Boolean = false,
    val selected: Boolean = false,
    val password: Boolean = false,
    /** Position in the parent's drawing order; a sibling with a higher value is drawn on top. */
    val drawingOrder: Int = 0,
    val children: List<UiNode> = emptyList(),
) {
    val actionable: Boolean get() = clickable || editable || scrollable || checkable
}

/** One window on the display. */
data class UiWindow(
    val id: Int,
    val bounds: Bounds,
    /** Null when the window's content could not be read. */
    val root: UiNode?,
    /** False for windows that only hide what is under them (the keyboard): never listed, never tapped. */
    val readable: Boolean = true,
    /** The app the window belongs to, when known. */
    val packageName: String? = null,
)

/** Everything on the display at one moment. [windows] run from the bottom-most to the top-most. */
data class Screen(
    val packageName: String?,
    val width: Int,
    val height: Int,
    val windows: List<UiWindow>,
    val keyboardOpen: Boolean = false,
)
