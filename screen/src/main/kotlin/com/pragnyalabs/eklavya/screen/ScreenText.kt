package com.pragnyalabs.eklavya.screen

import java.util.Collections
import java.util.IdentityHashMap

/** One numbered line of a [ScreenText]: an element a model can name and the driver can act on. */
class Element(
    val index: Int,
    val windowId: Int,
    val node: UiNode,
    val label: String,
) {
    val x: Int get() = node.bounds.centerX
    val y: Int get() = node.bounds.centerY
}

/**
 * A screen as compact text for a decision model, one numbered line per useful element:
 *
 *     app: io.a24go.android.dev | 1080x2400
 *     [1] EditText "Search 1,969 Workouts" clickable editable (473,194) #searchInput
 *
 * The same numbers index [elements], so "tap 1" can be carried out without the model seeing coordinates.
 */
class ScreenText(val text: String, val elements: List<Element>, val packageName: String?) {
    fun byId(viewId: String): Element? = elements.firstOrNull { it.node.viewId == viewId }

    fun byLabel(label: String): Element? = elements.firstOrNull { it.label.equals(label, ignoreCase = true) }

    companion object {
        /** Longest label shown for something to act on; a button's name is short. */
        private const val MAX_LABEL = 80

        /** Longest plain text shown; long enough for a paragraph of instructions. */
        private const val MAX_TEXT = 400

        /**
         * Lists what a person could see and touch on [screen]. Elements hidden under something drawn on
         * top of them are left out, because a tap there would land on the thing on top.
         */
        fun of(screen: Screen, maxElements: Int = 200): ScreenText {
            val found = ArrayList<Pair<Int, Pair<UiNode, String>>>()
            val absorbed: MutableSet<UiNode> = Collections.newSetFromMap(IdentityHashMap())
            val blockers = IdentityHashMap<UiNode, List<Bounds>>()
            screen.windows.forEachIndexed { i, window ->
                val root = window.root
                if (root == null || !window.readable) return@forEachIndexed
                // Every window above this one hides what is under it.
                val above = screen.windows.drop(i + 1).map { it.bounds }
                collect(root, window.id, above, screen, found, absorbed, blockers)
            }

            val shown = found.take(maxElements)
            val elements = shown.mapIndexed { i, (windowId, entry) -> Element(i + 1, windowId, entry.first, entry.second) }
            val sb = StringBuilder()
            sb.append("app: ").append(screen.packageName).append(" | ").append(screen.width).append('x').append(screen.height)
            if (screen.keyboardOpen) sb.append(" | keyboard open")
            for (e in elements) sb.append('\n').append(line(e))
            if (found.size > shown.size) sb.append("\n... (").append(found.size - shown.size).append(" more elements omitted)")
            return ScreenText(sb.toString(), elements, screen.packageName)
        }

        private fun line(e: Element): String {
            val n = e.node
            val sb = StringBuilder()
            sb.append('[').append(e.index).append("] ")
                .append(n.className?.substringAfterLast('.')?.ifEmpty { null } ?: "View")
                .append(" \"").append(truncate(e.label, if (n.actionable) MAX_LABEL else MAX_TEXT)).append('"')
            if (n.clickable) sb.append(" clickable")
            if (n.editable) sb.append(" editable")
            if (n.scrollable) sb.append(" scrollable")
            if (n.checkable) sb.append(if (n.checked) " checked" else " unchecked")
            if (!n.enabled) sb.append(" disabled")
            if (n.focused) sb.append(" focused")
            if (n.selected) sb.append(" selected")
            sb.append(" (").append(e.x).append(',').append(e.y).append(')')
            if (n.viewId != null) sb.append(" #").append(n.viewId)
            return sb.toString()
        }

        private fun collect(
            n: UiNode,
            windowId: Int,
            covers: List<Bounds>,
            screen: Screen,
            out: MutableList<Pair<Int, Pair<UiNode, String>>>,
            absorbed: MutableSet<UiNode>,
            blockers: IdentityHashMap<UiNode, List<Bounds>>,
        ) {
            if (visible(n, covers, screen) && n !in absorbed) {
                val own = ownText(n)
                if (n.actionable) {
                    var label = own ?: n.hint?.takeIf { it.isNotBlank() }
                    // A scroll container is not a button: its contents stay as their own lines.
                    if (label == null && (n.clickable || n.editable || n.checkable)) {
                        // A button made of an icon and separate text views: its label is the text inside it.
                        val parts = ArrayList<String>()
                        for (c in n.children) gather(c, covers, screen, parts, absorbed)
                        label = parts.joinToString(" ").ifEmpty { null }
                    }
                    // Icon buttons often have no text at all; the developer's id ("toolbarBackButton") still says what it is.
                    out.add(windowId to (n to normalize(label ?: n.viewId?.let(::idWords) ?: "")))
                } else if (own != null) {
                    out.add(windowId to (n to normalize(own)))
                }
            }
            for (c in n.children) {
                // A sibling drawn after this child takes the touches wherever it has something clickable.
                val onTop = n.children
                    .filter { it !== c && it.drawingOrder > c.drawingOrder }
                    .flatMap { touchBlockers(it, blockers) }
                collect(c, windowId, if (onTop.isEmpty()) covers else covers + onTop, screen, out, absorbed, blockers)
            }
        }

        /** The rectangles in which [n]'s subtree swallows touches: its own bounds if clickable, else its children's. */
        private fun touchBlockers(n: UiNode, memo: IdentityHashMap<UiNode, List<Bounds>>): List<Bounds> =
            memo.getOrPut(n) {
                when {
                    n.bounds.isEmpty -> emptyList()
                    n.clickable -> listOf(n.bounds)
                    else -> n.children.flatMap { touchBlockers(it, memo) }
                }
            }

        /** Collects the text of non-actionable descendants (not crossing actionable ones) and marks them used. */
        private fun gather(n: UiNode, covers: List<Bounds>, screen: Screen, parts: MutableList<String>, absorbed: MutableSet<UiNode>) {
            if (n.actionable) return
            if (visible(n, covers, screen)) {
                ownText(n)?.let {
                    parts.add(it)
                    absorbed.add(n)
                }
            }
            for (c in n.children) gather(c, covers, screen, parts, absorbed)
        }

        private fun ownText(n: UiNode): String? =
            n.text?.takeIf { it.isNotBlank() } ?: n.contentDescription?.takeIf { it.isNotBlank() }

        // ponytail: an element counts as hidden only when its centre is covered; one whose centre is free but
        // mostly covered is still listed. Compute the uncovered part if taps start landing on the wrong thing.
        private fun visible(n: UiNode, covers: List<Bounds>, screen: Screen): Boolean {
            val b = n.bounds
            if (b.isEmpty) return false
            if (b.left >= screen.width || b.right <= 0 || b.top >= screen.height || b.bottom <= 0) return false
            return covers.none { it.contains(b.centerX, b.centerY) }
        }

        /** "toolbarBackButton" / "search_item_layout" -> "toolbar back button" / "search item layout". */
        private fun idWords(id: String): String =
            id.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").replace('_', ' ').lowercase()

        private fun normalize(raw: String): String = raw.replace(Regex("\\s+"), " ").trim().replace('"', '\'')

        private fun truncate(t: String, max: Int): String {
            if (t.length <= max) return t
            var cut = max - 3
            if (t[cut - 1].isHighSurrogate()) cut--
            return t.take(cut) + "..."
        }
    }
}
