package com.pragnyalabs.eklavya.screen

import org.w3c.dom.Element
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Turns an `adb shell uiautomator dump` file into a [Screen], so real app screens can be replayed in JVM
 * tests without a phone. The dump holds one window and has no "editable" flag, so that is taken from the class.
 */
object UiAutomatorXml {
    private val BOUNDS = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")

    /** Loads a dump from the classpath, e.g. "/24go/home.xml". */
    fun load(resource: String): Screen =
        requireNotNull(javaClass.getResourceAsStream(resource)) { "missing fixture $resource" }.use(::parse)

    fun parse(stream: InputStream): Screen {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stream)
        val roots = children(doc.documentElement).mapIndexed { i, e -> node(e, listOf(i)) }
        val full = roots.first().bounds
        return Screen(
            packageName = children(doc.documentElement).first().getAttribute("package"),
            width = full.right,
            height = full.bottom,
            windows = roots.mapIndexed { i, r -> UiWindow(i, r.bounds, r, packageName = children(doc.documentElement).first().getAttribute("package")) }.take(1),
        )
    }

    private fun children(e: Element): List<Element> =
        (0 until e.childNodes.length).map { e.childNodes.item(it) }.filterIsInstance<Element>()

    private fun node(e: Element, path: List<Int>): UiNode {
        val (l, t, r, b) = BOUNDS.matchEntire(e.getAttribute("bounds"))!!.destructured
        val cls = e.getAttribute("class")
        val hint = e.getAttribute("hint").ifEmpty { null }
        // An empty text field is dumped with its hint as its text.
        val text = e.getAttribute("text").ifEmpty { null }.takeIf { !cls.endsWith("EditText") || it != hint }
        return UiNode(
            path = path,
            className = cls,
            text = text,
            contentDescription = e.getAttribute("content-desc").ifEmpty { null },
            hint = hint,
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
