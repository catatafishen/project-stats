package com.github.projectstats

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Rectangle2D
import javax.swing.JPanel

/**
 * Squarified treemap. Supports drill-down for groups with children (directory tree).
 */
class TreemapPanel : JPanel() {

    private var groups: List<StatGroup> = emptyList()
    private var metric: Metric = Metric.LOC
    private var drillStack: ArrayDeque<StatGroup> = ArrayDeque()
    private var rects: List<Pair<Rectangle2D.Double, StatGroup>> = emptyList()
    private var colorFor: (StatGroup) -> Color = { hashColor(it.key) }

    init {
        background = JBColor.background()
        preferredSize = JBUI.size(400, 300)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val hit = hitTest(e.point) ?: return
                if (e.clickCount == 2 && hit.children.isNotEmpty()) {
                    drillStack.addLast(hit)
                    repaint()
                } else if (e.button == MouseEvent.BUTTON3 || (e.clickCount == 2 && hit.children.isEmpty())) {
                    // right-click or double-click on a leaf goes back a level
                    if (drillStack.isNotEmpty()) { drillStack.removeLast(); repaint() }
                }
            }
        })
        toolTipText = "" // enable tooltips
    }

    fun setData(groups: List<StatGroup>, metric: Metric, colorFn: (StatGroup) -> Color) {
        this.groups = groups
        this.metric = metric
        this.colorFor = colorFn
        this.drillStack.clear()
        repaint()
    }

    fun currentPath(): String =
        if (drillStack.isEmpty()) "" else drillStack.joinToString(" / ") { it.key }

    fun popDrill(): Boolean {
        if (drillStack.isEmpty()) return false
        drillStack.removeLast()
        repaint()
        return true
    }

    private fun currentGroups(): List<StatGroup> =
        if (drillStack.isEmpty()) groups else drillStack.last().children

    private fun hitTest(p: Point): StatGroup? =
        rects.firstOrNull { it.first.contains(p.x.toDouble(), p.y.toDouble()) }?.second

    override fun getToolTipText(event: MouseEvent): String? {
        val hit = hitTest(event.point) ?: return null
        val v = hit.value(metric)
        val suffix = if (hit.children.isNotEmpty()) "  (double-click to drill in)" else ""
        return "<html><b>${escape(hit.key)}</b><br/>" +
                "${metric.display}: ${format(metric, v)}<br/>" +
                "Files: ${hit.fileCount}, Total LOC: ${hit.totalLines}, Size: ${humanBytes(hit.sizeBytes)}" +
                "$suffix</html>"
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val data = currentGroups().filter { it.value(metric) > 0 }.sortedByDescending { it.value(metric) }
        val w = width.toDouble()
        val h = height.toDouble()
        if (data.isEmpty() || w <= 2 || h <= 2) {
            g2.color = JBColor.foreground()
            g2.drawString("No data. Click Refresh to scan.", 12, 20)
            rects = emptyList()
            return
        }

        val total = data.sumOf { it.value(metric).toDouble() }
        val placed = ArrayList<Pair<Rectangle2D.Double, StatGroup>>(data.size)
        squarify(data, total, Rectangle2D.Double(0.0, 0.0, w, h), placed)
        rects = placed

        for ((rect, grp) in placed) {
            val color = colorFor(grp)
            g2.color = color
            g2.fill(rect)
            g2.color = JBColor.border()
            g2.draw(rect)
            // label
            if (rect.width > 40 && rect.height > 18) {
                g2.color = contrastingText(color)
                val fm = g2.fontMetrics
                val label = grp.key
                val maxChars = (rect.width / (fm.charWidth('M').coerceAtLeast(1))).toInt().coerceAtLeast(3)
                val trimmed = if (label.length > maxChars) label.substring(0, maxChars - 1) + "…" else label
                g2.drawString(trimmed, (rect.x + 4).toInt(), (rect.y + fm.ascent + 2).toInt())
                if (rect.height > 34) {
                    val sub = format(metric, grp.value(metric))
                    g2.drawString(sub, (rect.x + 4).toInt(), (rect.y + fm.ascent * 2 + 4).toInt())
                }
            }
        }

        // Breadcrumb
        if (drillStack.isNotEmpty()) {
            val crumb = "▲ ${currentPath()}  (right-click or double-click a leaf to go back)"
            g2.color = JBColor.foreground()
            g2.drawString(crumb, 6, height - 6)
        }
    }

    // ---------- squarified treemap ----------

    private fun squarify(
        items: List<StatGroup>,
        total: Double,
        bounds: Rectangle2D.Double,
        out: MutableList<Pair<Rectangle2D.Double, StatGroup>>,
    ) {
        var remaining = items
        var rect = bounds
        var remainingTotal = total
        while (remaining.isNotEmpty()) {
            val shorter = minOf(rect.width, rect.height)
            val row = ArrayList<StatGroup>()
            var rowSum = 0.0
            var bestRatio = Double.MAX_VALUE
            var idx = 0
            while (idx < remaining.size) {
                val candidate = remaining[idx]
                val cVal = candidate.value(metric).toDouble()
                if (cVal <= 0) { idx++; continue }
                val newSum = rowSum + cVal
                val ratio = worstRatio(row + candidate, newSum, shorter, rect, remainingTotal)
                if (row.isEmpty() || ratio <= bestRatio) {
                    row += candidate
                    rowSum = newSum
                    bestRatio = ratio
                    idx++
                } else {
                    break
                }
            }
            if (row.isEmpty()) break
            val used = layoutRow(row, rowSum, rect, remainingTotal, shorter == rect.width, out)
            rect = used.second
            remainingTotal -= rowSum
            remaining = remaining.subList(row.size.coerceAtMost(remaining.size), remaining.size)
            if (rect.width < 1 || rect.height < 1) break
        }
    }

    private fun worstRatio(row: List<StatGroup>, sum: Double, shorter: Double, rect: Rectangle2D.Double, total: Double): Double {
        if (sum <= 0.0 || total <= 0.0) return Double.MAX_VALUE
        val area = rect.width * rect.height * (sum / total)
        val side = area / shorter
        var worst = 0.0
        for (g in row) {
            val a = rect.width * rect.height * (g.value(metric) / total)
            val other = a / side
            val ratio = maxOf(side / other, other / side)
            if (ratio > worst) worst = ratio
        }
        return worst
    }

    private fun layoutRow(
        row: List<StatGroup>,
        rowSum: Double,
        rect: Rectangle2D.Double,
        total: Double,
        horizontal: Boolean,
        out: MutableList<Pair<Rectangle2D.Double, StatGroup>>,
    ): Pair<Boolean, Rectangle2D.Double> {
        val area = rect.width * rect.height * (rowSum / total)
        return if (horizontal) {
            val rowH = (area / rect.width).coerceAtMost(rect.height)
            var x = rect.x
            for (g in row) {
                val w = rect.width * (g.value(metric) / rowSum)
                out += Rectangle2D.Double(x, rect.y, w, rowH) to g
                x += w
            }
            true to Rectangle2D.Double(rect.x, rect.y + rowH, rect.width, rect.height - rowH)
        } else {
            val rowW = (area / rect.height).coerceAtMost(rect.width)
            var y = rect.y
            for (g in row) {
                val h = rect.height * (g.value(metric) / rowSum)
                out += Rectangle2D.Double(rect.x, y, rowW, h) to g
                y += h
            }
            false to Rectangle2D.Double(rect.x + rowW, rect.y, rect.width - rowW, rect.height)
        }
    }

    private fun escape(s: String) = s.replace("<", "&lt;").replace(">", "&gt;")
}

// ------- color / format helpers shared across UI -------

fun hashColor(key: String): Color {
    // Golden-ratio hue hash for visually distinct, stable colors.
    val h = (key.hashCode().toLong() and 0xFFFFFFFFL)
    val hue = ((h % 1000) / 1000f + 0.61803398875f) % 1f
    val sat = 0.55f
    val bri = if (JBColor.isBright()) 0.85f else 0.70f
    return Color.getHSBColor(hue, sat, bri)
}

fun contrastingText(bg: Color): Color {
    val luminance = (0.299 * bg.red + 0.587 * bg.green + 0.114 * bg.blue) / 255.0
    return if (luminance > 0.6) Color(30, 30, 30) else Color(240, 240, 240)
}

fun humanBytes(b: Long): String {
    if (b < 1024) return "$b B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = b.toDouble() / 1024
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return "%.1f %s".format(v, units[i])
}

fun format(metric: Metric, value: Long): String = when (metric) {
    Metric.SIZE -> humanBytes(value)
    Metric.LOC, Metric.NON_BLANK_LOC, Metric.CODE_LOC -> "%,d lines".format(value)
    Metric.FILE_COUNT -> "%,d files".format(value)
}
