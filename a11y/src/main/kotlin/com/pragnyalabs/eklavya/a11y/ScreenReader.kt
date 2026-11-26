package com.pragnyalabs.eklavya.a11y

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.pragnyalabs.eklavya.screen.Bounds
import com.pragnyalabs.eklavya.screen.Element
import com.pragnyalabs.eklavya.screen.Screen
import com.pragnyalabs.eklavya.screen.UiNode
import com.pragnyalabs.eklavya.screen.UiWindow

/**
 * Copies what the accessibility service can see into a [Screen].
 *
 * The service needs `flagRetrieveInteractiveWindows` (to see every window), `flagReportViewIds` (view ids)
 * and `flagIncludeNotImportantViews` (Compose and custom views often mark useful nodes "not important").
 */
class ScreenReader(private val service: AccessibilityService) {

    fun read(): Screen {
        val display = service.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        val windows = ArrayList<UiWindow>()
        var keyboard = false
        var front: String? = null
        // Bottom-most first, so each window is hidden by the ones after it.
        for (w in service.windows.sortedBy { it.layer }) {
            val rect = Rect().also(w::getBoundsInScreen)
            val bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom)
            when (w.type) {
                AccessibilityWindowInfo.TYPE_APPLICATION -> {
                    val root = w.root ?: continue
                    // Our own windows are not part of the screen being driven.
                    if (root.packageName == service.packageName) continue
                    val budget = intArrayOf(MAX_NODES)
                    windows.add(UiWindow(w.id, bounds, convert(root, emptyList(), budget), packageName = root.packageName?.toString()))
                    front = root.packageName?.toString()
                }
                AccessibilityWindowInfo.TYPE_INPUT_METHOD -> {
                    keyboard = true
                    windows.add(UiWindow(w.id, bounds, root = null, readable = false))
                }
                // ponytail: system windows (status bar, navigation bar, notification shade) are neither listed
                // nor treated as covers. When the shade is pulled down the app under it is still reported;
                // add a "system window in front" flag if routes start acting through the shade.
                else -> Unit
            }
        }
        return Screen(front, display.width(), display.height(), windows, keyboard)
    }

    /**
     * The live node behind [e], or null when the screen has changed since it was read (the node is gone
     * or something else now sits at that place). Callers read the screen again instead of acting on a guess.
     */
    fun live(e: Element): AccessibilityNodeInfo? {
        var cur = service.windows.firstOrNull { it.id == e.windowId }?.root ?: return null
        for (i in e.node.path) cur = cur.getChild(i) ?: return null
        // Rows of a list share a class and an id, so the words on the element are compared too: after a
        // scroll the same place in the tree can hold a different row.
        // ponytail: in a window with more than MAX_NODES nodes the first read may have cut this element's subtree
        // short, and it will then never match here (the action is refused, nothing wrong is tapped). Record
        // where a read was cut if such screens turn up.
        val now = convert(cur, e.node.path, intArrayOf(MAX_NODES), STALE_CHECK_DEPTH) ?: return null
        val same = now.className == e.node.className && now.viewId == e.node.viewId &&
            words(now, STALE_CHECK_DEPTH) == words(e.node, STALE_CHECK_DEPTH)
        return if (same) cur else null
    }

    /** The text, descriptions and hints of [n] and its descendants down to [depth] levels, in tree order. */
    private fun words(n: UiNode, depth: Int): List<String?> =
        listOf(n.text, n.contentDescription, n.hint) + if (depth > 0) n.children.flatMap { words(it, depth - 1) } else emptyList()

    private fun convert(n: AccessibilityNodeInfo, path: List<Int>, budget: IntArray, depth: Int = Int.MAX_VALUE): UiNode? {
        if (budget[0]-- <= 0 || !n.isVisibleToUser) return null
        val r = Rect().also(n::getBoundsInScreen)
        val children = ArrayList<UiNode>()
        if (depth > 0) {
            for (i in 0 until n.childCount) {
                // Each getChild is a call into another process; stop asking once the budget is spent.
                if (budget[0] <= 0) break
                val child = n.getChild(i) ?: continue
                // The path keeps the real child index even when earlier children are skipped.
                convert(child, path + i, budget, depth - 1)?.let(children::add)
            }
        }
        @Suppress("DEPRECATION") // isChecked: the three-state replacement needs API 36.
        return UiNode(
            path = path,
            className = n.className?.toString(),
            // Never copy what is typed into a password field, whatever the system reports for it.
            // A field showing its hint reports the hint as its text; that is not something typed.
            text = if (n.isPassword || n.isShowingHintText) null else n.text?.toString(),
            contentDescription = n.contentDescription?.toString(),
            hint = n.hintText?.toString(),
            viewId = simpleId(n),
            bounds = Bounds(r.left, r.top, r.right, r.bottom),
            clickable = n.isClickable,
            editable = n.isEditable,
            scrollable = n.isScrollable,
            checkable = n.isCheckable,
            checked = n.isChecked,
            enabled = n.isEnabled,
            focused = n.isFocused,
            selected = n.isSelected,
            password = n.isPassword,
            drawingOrder = n.drawingOrder,
            children = children,
        )
    }

    private fun simpleId(n: AccessibilityNodeInfo): String? =
        n.viewIdResourceName?.substringAfterLast('/')?.ifEmpty { null }

    private companion object {
        /** Upper bound on nodes copied per window, so a huge or cyclic tree cannot stall a read. */
        const val MAX_NODES = 4000

        /** How far below an element its words are compared to tell whether it is still the same element. */
        const val STALE_CHECK_DEPTH = 3
    }
}
