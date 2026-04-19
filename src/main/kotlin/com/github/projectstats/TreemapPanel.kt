package com.github.projectstats

import com.intellij.ui.Gray
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.geom.Rectangle2D
import java.util.Locale
import javax.swing.JPanel
import kotlin.collections.ArrayDeque
import kotlin.collections.ArrayList
import kotlin.collections.List
import kotlin.collections.MutableList
import kotlin.collections.elementAt
import kotlin.collections.emptyList
import kotlin.collections.filter
import kotlin.collections.firstOrNull
import kotlin.collections.isNotEmpty
import kotlin.collections.joinToString
import kotlin.collections.last
import kotlin.collections.lastIndex
import kotlin.collections.plus
import kotlin.collections.plusAssign
import kotlin.collections.sortedByDescending
import kotlin.collections.sumOf

/**
 * Squarified treemap. Supports drill-down for groups with children (directory tree).
 */
class TreemapPanel : JPanel() {

    /** One user-level drill step. A step may represent a chain of single-child folders
     *  that were auto-traversed when drilling down — treated atomically for Up/pop. */
    private data class DrillStep(val chain: List<StatGroup>) {
        val target: StatGroup get() = chain.last()
        fun display(): String = chain.joinToString("/") { it.key }
    }

    private var groups: List<StatGroup> = emptyList()
    private var metric: Metric = Metric.LOC
    private var drillStack: ArrayDeque<DrillStep> = ArrayDeque()
    private var rects: List<Pair<Rectangle2D.Double, StatGroup>> = emptyList()
    private var colorFor: (StatGroup) -> Color = { hashColor(it.key) }
    private var onDrillChanged: ((StatGroup?) -> Unit)? = null
    private var singleClickDrill: Boolean = false

    // Layout cache — squarify is O(n²) and must not run on every repaint.
    private var layoutDirty: Boolean = true
    private var layoutW: Double = -1.0
    private var layoutH: Double = -1.0

    // Metric-change animation state.
    private var animTimer: javax.swing.Timer? = null
    private var animFromRects: Map<String, Rectangle2D.Double>? = null
    private var animT: Float = 1f
    private var animStartNanos: Long = 0L
    private val animDurationMs = 260

    init {
        background = JBColor.background()
        preferredSize = JBUI.size(400, 300)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val hit = hitTest(e.point) ?: return
                val hasChildren = hit.children.isNotEmpty()
                val drillClick = (singleClickDrill && e.clickCount == 1 && e.button == MouseEvent.BUTTON1)
                        || e.clickCount == 2
                if (drillClick && hasChildren) {
                    drillInto(hit)
                } else if (e.button == MouseEvent.BUTTON3 || (e.clickCount == 2 && !hasChildren)) {
                    // right-click or double-click on a leaf goes back a level
                    popDrill()
                }
            }
        })
        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                // Avoid calling setCursor() — and the native updateCursorImmediately()
                // it triggers — unless the cursor actually needs to change.
                if (!singleClickDrill) return
                val hit = hitTest(e.point)
                val newCursor = if (hit != null && hit.children.isNotEmpty())
                    Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                else
                    Cursor.getDefaultCursor()
                if (cursor !== newCursor) cursor = newCursor
            }
        })
        toolTipText = "" // enable tooltips
    }

    /** Enable single-click drill-down (with hand cursor) for drillable cells. */
    fun setSingleClickDrill(enabled: Boolean) {
        singleClickDrill = enabled
        if (!enabled) cursor = Cursor.getDefaultCursor()
    }

    fun setData(
        groups: List<StatGroup>,
        metric: Metric,
        colorFn: (StatGroup) -> Color,
        preservePath: Boolean = false,
        animate: Boolean = false,
    ) {
        val preservedKeys = if (preservePath) drillPathKeys() else emptyList()
        val animateFrom: Map<String, Rectangle2D.Double>? =
            if (animate && rects.isNotEmpty()) {
                val m = HashMap<String, Rectangle2D.Double>(rects.size)
                for ((r, g) in rects) {
                    m[g.key] = Rectangle2D.Double(r.x, r.y, r.width, r.height)
                }
                m
            } else null
        this.groups = groups
        this.metric = metric
        this.colorFor = colorFn
        this.drillStack.clear()
        if (preservedKeys.isNotEmpty()) restoreDrillPath(preservedKeys)
        layoutDirty = true
        if (animateFrom != null) startAnimation(animateFrom) else stopAnimation()
        repaint()
    }

    /** Snapshot of the drill path — one key-chain per drill step. */
    fun drillPathKeys(): List<List<String>> = drillStack.map { step -> step.chain.map { it.key } }

    /** Replay a key-chain path against [groups]. Path steps that can no longer be resolved are dropped. */
    private fun restoreDrillPath(pathKeys: List<List<String>>) {
        var level: List<StatGroup> = groups
        for (step in pathKeys) {
            val chain = ArrayList<StatGroup>(step.size)
            var scan = level
            for (key in step) {
                val match = scan.firstOrNull { it.key == key } ?: break
                chain.add(match)
                scan = match.children
            }
            if (chain.size != step.size) break
            drillStack.addLast(DrillStep(chain))
            level = chain.last().children
        }
    }


    private fun startAnimation(from: Map<String, Rectangle2D.Double>) {
        stopAnimation()
        animFromRects = from
        animT = 0f
        animStartNanos = System.nanoTime()
        val timer = javax.swing.Timer(16) {
            val elapsedMs = (System.nanoTime() - animStartNanos) / 1_000_000.0
            animT = (elapsedMs / animDurationMs).toFloat().coerceIn(0f, 1f)
            repaint()
            if (animT >= 1f) stopAnimation()
        }
        timer.isRepeats = true
        animTimer = timer
        timer.start()
    }

    private fun stopAnimation() {
        animTimer?.stop()
        animTimer = null
        animFromRects = null
        animT = 1f
    }

    private fun easedT(): Double {
        val t = animT.toDouble().coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    /** Callback fired whenever the drill state changes (user drill-in or drill-out).
     *  Argument is the currently drilled-into group (last in chain), or null at root. */
    fun setOnDrillChanged(listener: (StatGroup?) -> Unit) {
        onDrillChanged = listener
    }

    /** Currently drilled-into group, or null when showing the root level. */
    fun currentDrilledGroup(): StatGroup? = drillStack.lastOrNull()?.target

    fun currentPath(): String =
        if (drillStack.isEmpty()) "" else drillStack.joinToString(" / ") { it.display() }

    /** Number of drill steps currently on the stack. */
    fun drillDepth(): Int = drillStack.size

    /** Display string of the drill step at [index] (0-based). */
    fun drillStepDisplay(index: Int): String = drillStack.elementAt(index).display()

    /** Pop drill steps until only [keep] remain (0 = full reset to root). */
    fun popToDepth(keep: Int) {
        if (keep < 0 || keep >= drillStack.size) return
        while (drillStack.size > keep) drillStack.removeLast()
        stopAnimation()
        layoutDirty = true
        repaint()
        onDrillChanged?.invoke(currentDrilledGroup())
    }

    fun popDrill(): Boolean {
        if (drillStack.isEmpty()) return false
        drillStack.removeLast()
        stopAnimation()
        layoutDirty = true
        repaint()
        onDrillChanged?.invoke(currentDrilledGroup())
        return true
    }

    private fun drillInto(hit: StatGroup) {
        // Auto-traverse single-child folder chains (e.g. src/main/java/com/example/...) so the
        // user lands directly on the first node with real branching.
        val chain = ArrayList<StatGroup>()
        chain.add(hit)
        var current = hit
        while (current.children.size == 1 && current.children[0].children.isNotEmpty()) {
            current = current.children[0]
            chain.add(current)
        }
        drillStack.addLast(DrillStep(chain))
        stopAnimation()
        layoutDirty = true
        repaint()
        onDrillChanged?.invoke(current)
    }

    private fun currentGroups(): List<StatGroup> =
        if (drillStack.isEmpty()) groups else drillStack.last().target.children

    private fun hitTest(p: Point): StatGroup? =
        rects.firstOrNull { it.first.contains(p.x.toDouble(), p.y.toDouble()) }?.second

    override fun getToolTipText(event: MouseEvent): String? {
        val hit = hitTest(event.point) ?: return null
        val v = hit.value(metric)
        val suffix = if (hit.children.isNotEmpty()) {
            if (singleClickDrill) "  (click to drill in)" else "  (double-click to drill in)"
        } else ""
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

        // Recompute the squarified layout only when data or size has changed.
        if (layoutDirty || w != layoutW || h != layoutH) {
            val total = data.sumOf { it.value(metric).toDouble() }
            val placed = ArrayList<Pair<Rectangle2D.Double, StatGroup>>(data.size)
            squarify(data, total, Rectangle2D.Double(0.0, 0.0, w, h), placed)
            rects = placed
            layoutW = w
            layoutH = h
            layoutDirty = false
        }

        val animating = animTimer != null && animT < 1f
        val fromMap = if (animating) animFromRects else null
        val t = if (animating) easedT() else 1.0
        for ((rect, grp) in rects) {
            val displayRect = interpolateRect(fromMap?.get(grp.key), rect, t)
            val color = colorFor(grp)
            g2.color = color
            g2.fill(displayRect)
            g2.color = JBColor.border()
            g2.draw(displayRect)
            drawCellLabel(g2, displayRect, grp, color)
        }

        // In-treemap breadcrumb (double-click modes only — directory mode shows a toolbar breadcrumb).
        if (drillStack.isNotEmpty() && !singleClickDrill) {
            val crumb = "▲ ${currentPath()}  (right-click or double-click a leaf to go back)"
            g2.color = JBColor.foreground()
            g2.drawString(crumb, 6, height - 6)
        }
    }

    private fun interpolateRect(from: Rectangle2D.Double?, to: Rectangle2D.Double, t: Double): Rectangle2D.Double {
        if (from == null || t >= 1.0) return to
        return Rectangle2D.Double(
            from.x + (to.x - from.x) * t,
            from.y + (to.y - from.y) * t,
            from.width + (to.width - from.width) * t,
            from.height + (to.height - from.height) * t,
        )
    }

    private fun drawCellLabel(g2: Graphics2D, rect: Rectangle2D.Double, grp: StatGroup, fill: Color) {
        if (rect.width <= 40 || rect.height <= 18) return
        g2.color = contrastingText(fill)
        val fm = g2.fontMetrics
        val maxChars = (rect.width / (fm.charWidth('M').coerceAtLeast(1))).toInt().coerceAtLeast(3)
        val trimmed = if (grp.key.length > maxChars) grp.key.substring(0, maxChars - 1) + "…" else grp.key
        g2.drawString(trimmed, (rect.x + 4).toInt(), (rect.y + fm.ascent + 2).toInt())
        if (rect.height > 34) {
            g2.drawString(format(metric, grp.value(metric)), (rect.x + 4).toInt(), (rect.y + fm.ascent * 2 + 4).toInt())
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
            val row = buildNextRow(remaining, shorter, rect, remainingTotal)
            if (row.isEmpty()) break
            val rowSum = row.sumOf { it.value(metric).toDouble() }
            val used = layoutRow(row, rowSum, rect, remainingTotal, shorter == rect.width, out)
            rect = used.second
            remainingTotal -= rowSum
            remaining = remaining.subList(row.size.coerceAtMost(remaining.size), remaining.size)
            if (rect.width < 1 || rect.height < 1) break
        }
    }

    /** Greedily accumulate items into a squarified row while the worst aspect ratio keeps improving. */
    private fun buildNextRow(
        remaining: List<StatGroup>,
        shorter: Double,
        rect: Rectangle2D.Double,
        remainingTotal: Double,
    ): List<StatGroup> {
        val row = ArrayList<StatGroup>()
        var rowSum = 0.0
        var bestRatio = Double.MAX_VALUE
        for (candidate in remaining) {
            val cVal = candidate.value(metric).toDouble()
            if (cVal <= 0) continue
            val newSum = rowSum + cVal
            val ratio = worstRatio(row + candidate, newSum, shorter, rect, remainingTotal)
            if (row.isNotEmpty() && ratio > bestRatio) break
            row += candidate
            rowSum = newSum
            bestRatio = ratio
        }
        return row
    }

    private fun worstRatio(
        row: List<StatGroup>,
        sum: Double,
        shorter: Double,
        rect: Rectangle2D.Double,
        total: Double
    ): Double {
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
    val hue = ((h % 1000) / 1000f + 0.618034f) % 1f
    val sat = 0.55f
    val bri = if (JBColor.isBright()) 0.85f else 0.70f
    return Color.getHSBColor(hue, sat, bri)
}

fun contrastingText(bg: Color): Color {
    val luminance = (0.299 * bg.red + 0.587 * bg.green + 0.114 * bg.blue) / 255.0
    return if (luminance > 0.6) Gray._30 else Gray._240
}

fun humanBytes(b: Long): String {
    if (b < 1024) return "$b B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = b.toDouble() / 1024
    var i = 0
    while (v >= 1024 && i < units.lastIndex) {
        v /= 1024; i++
    }
    return String.format(Locale.US, "%.1f %s", v, units[i])
}

fun format(metric: Metric, value: Long): String = when (metric) {
    Metric.SIZE -> humanBytes(value)
    Metric.LOC, Metric.NON_BLANK_LOC, Metric.CODE_LOC -> "%,d lines".format(value)
    Metric.COMPLEXITY -> "%,d".format(value)
    Metric.FILE_COUNT -> "%,d files".format(value)
    Metric.COMMIT_COUNT -> "%,d commits".format(value)
}
