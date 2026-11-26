package com.pragnyalabs.eklavya.screen

import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Test helper: turns an `adb shell uiautomator dump` file into a [Screen], so real app screens can be
 * replayed in JVM tests. The dump holds one window and has no "editable" flag, so that is taken from the class.
 */
object UiAutomatorXml {
    private val BOUNDS = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")

    fun load(resource: String): Screen {
        val stream = requireNotNull(javaClass.getResourceAsStream(resource)) { "missing fixture $resource" }
        val doc = stream.use { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it) }
        val roots = children(doc.documentElement).mapIndexed { i, e -> node(e, listOf(i)) }
        val full = roots.first().bounds
        return Screen(
            packageName = children(doc.documentElement).first().getAttribute("package"),
            width = full.right,
            height = full.bottom,
            windows = roots.mapIndexed { i, r -> UiWindow(i, r.bounds, r) }.take(1),
        )
    }

    private fun children(e: Element): List<Element> =
        (0 until e.childNodes.length).map { e.childNodes.item(it) }.filterIsInstance<Element>()

    private fun node(e: Element, path: List<Int>): UiNode {
        val (l, t, r, b) = BOUNDS.matchEntire(e.getAttribute("bounds"))!!.destructured
        val cls = e.getAttribute("class")
        return UiNode(
            path = path,
            className = cls,
            text = e.getAttribute("text").ifEmpty { null },
            contentDescription = e.getAttribute("content-desc").ifEmpty { null },
            hint = e.getAttribute("hint").ifEmpty { null },
            viewId = e.getAttribute("resource-id").substringAfterLast('/').ifEmpty { null },
            bounds = Bounds(l.toInt(), t.toInt(), r.toInt(), b.toInt()),
            clickable = e.getAttribute("clickable") == "true",
            editable = cls.endsWith("EditText"),
            scrollable = e.getAttribute("scrollable") == "true",
            checkable = e.getAttribute("checkable") == "true",
            checked = e.getAttribute("checked") == "true",
            enabled = e.getAttribute("enabled") != "false",
            focused = e.getAttribute("focused") == "true",
            selected = e.getAttribute("selected") == "true",
            password = e.getAttribute("password") == "true",
            drawingOrder = e.getAttribute("drawing-order").toIntOrNull() ?: 0,
            children = children(e).mapIndexed { i, c -> node(c, path + i) },
        )
    }
}
