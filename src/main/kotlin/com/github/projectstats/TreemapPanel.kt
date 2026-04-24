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
import kotlin.collections.HashMap
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.MutableList
import kotlin.collections.elementAt
import kotlin.collections.emptyList
import kotlin.collections.filter
import kotlin.collections.first
import kotlin.collections.firstOrNull
import kotlin.collections.isNotEmpty
import kotlin.collections.joinToString
import kotlin.collections.last
import kotlin.collections.lastIndex
import kotlin.collections.map
import kotlin.collections.plus
import kotlin.collections.plusAssign
import kotlin.collections.set
import kotlin.collections.sortedByDescending
import kotlin.collections.sumOf
import kotlin.collections.toList

/**
 * Squarified treemap. Supports drill-down for groups with children (directory tree).
 */
class TreemapPanel : JPanel() {

    /** One user-level drill step. A step may represent a chain of single-child folders
     *  that were auto-traversed when drilling down — treated atomically for Up/pop.
     *  [srcRectAtPush] is the parent (clicked) cell's rect at the moment of drill-in,
     *  used to animate drill-out back into that position. */
    private data class DrillStep(
        val chain: List<StatGroup>,
        val srcRectAtPush: Rectangle2D.Double,
    ) {
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

    // Animation state. METRIC = cells morph between two metric values; DRILL_IN/OUT = zoom into/out
    // of a clicked cell. All three reuse the same Swing Timer + eased-t machinery.
    private enum class AnimKind { METRIC, DRILL_IN, DRILL_OUT }

    private var animKind: AnimKind = AnimKind.METRIC
    private var animTimer: javax.swing.Timer? = null
    private var animT: Float = 1f
    private var animStartNanos: Long = 0L
    private val animDurationMs = 260
    private val drillAnimDurationMs = 320

    // METRIC anim state.
    private var animFromRects: Map<String, Rectangle2D.Double>? = null

    // DRILL anim state.
    /** Per-child key -> source rect (where the child starts the animation). */
    private var drillFromRects: Map<String, Rectangle2D.Double>? = null

    /** Cells from the *outgoing* layout, painted with fading alpha during drill. */
    private var drillGhostCells: List<Pair<Rectangle2D.Double, StatGroup>> = emptyList()

    /** Single highlight ghost (the parent cell during drill-in, or the collapsing-target during drill-out). */
    private var drillGhostFocusRect: Rectangle2D.Double? = null
    private var drillGhostFocusGroup: StatGroup? = null

    // Hover preview state.
    private var hoverGroup: StatGroup? = null
    private var hoverChildRects: List<Pair<Rectangle2D.Double, StatGroup>> = emptyList()

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
                val hit = hitTest(e.point)
                // Cursor — only update when it actually needs to change to avoid native cursor churn.
                val newCursor = if (singleClickDrill && hit != null && hit.children.isNotEmpty())
                    Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                else
                    Cursor.getDefaultCursor()
                if (cursor !== newCursor) cursor = newCursor

                // Hover preview: lazily lay out children inside a drillable cell. Only repaint when the
                // hovered group actually changes — squarify is O(n²) so we cache aggressively.
                updateHoverPreview(hit)
            }

            override fun mouseDragged(e: MouseEvent) {
                clearHoverPreview()
            }
        })
        addMouseListener(object : MouseAdapter() {
            override fun mouseExited(e: MouseEvent) {
                clearHoverPreview()
            }
        })
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
        clearHoverPreview()
        if (animateFrom != null) startMetricAnimation(animateFrom) else stopAnimation()
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
            // Replay loses original click-rects; use a zero rect as a sentinel — the user can't
            // actually animate a drill-out we never animated into.
            drillStack.addLast(DrillStep(chain, Rectangle2D.Double(0.0, 0.0, 0.0, 0.0)))
            level = chain.last().children
        }
    }


    private fun startMetricAnimation(from: Map<String, Rectangle2D.Double>) {
        stopAnimation()
        animKind = AnimKind.METRIC
        animFromRects = from
        startTimer(animDurationMs)
    }

    private fun startDrillInAnimation(
        fromRects: Map<String, Rectangle2D.Double>,
        ghostCells: List<Pair<Rectangle2D.Double, StatGroup>>,
        ghostFocusRect: Rectangle2D.Double,
        ghostFocusGroup: StatGroup,
    ) {
        stopAnimation()
        animKind = AnimKind.DRILL_IN
        drillFromRects = fromRects
        drillGhostCells = ghostCells
        drillGhostFocusRect = ghostFocusRect
        drillGhostFocusGroup = ghostFocusGroup
        startTimer(drillAnimDurationMs)
    }

    private fun startDrillOutAnimation(
        fromRects: Map<String, Rectangle2D.Double>,
        ghostCells: List<Pair<Rectangle2D.Double, StatGroup>>,
        collapseTargetRect: Rectangle2D.Double,
        collapseTargetGroup: StatGroup,
    ) {
        stopAnimation()
        animKind = AnimKind.DRILL_OUT
        drillFromRects = fromRects
        drillGhostCells = ghostCells
        drillGhostFocusRect = collapseTargetRect
        drillGhostFocusGroup = collapseTargetGroup
        startTimer(drillAnimDurationMs)
    }

    private fun startTimer(durationMs: Int) {
        animT = 0f
        animStartNanos = System.nanoTime()
        val timer = javax.swing.Timer(16) {
            val elapsedMs = (System.nanoTime() - animStartNanos) / 1_000_000.0
            animT = (elapsedMs / durationMs).toFloat().coerceIn(0f, 1f)
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
        drillFromRects = null
        drillGhostCells = emptyList()
        drillGhostFocusRect = null
        drillGhostFocusGroup = null
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
        // Capture the rect of the drill step we'll collapse INTO. The first step we pop is the one
        // whose chain[0] becomes a regular cell at depth `keep` after popping.
        val firstPopped = drillStack.elementAt(keep)
        // Snapshot the children currently being shown — they'll fade out as they collapse.
        val oldRects = rects.toList()
        while (drillStack.size > keep) drillStack.removeLast()
        animateDrillOut(firstPopped, oldRects)
        onDrillChanged?.invoke(currentDrilledGroup())
    }

    fun popDrill(): Boolean {
        if (drillStack.isEmpty()) return false
        val popped = drillStack.removeLast()
        val oldRects = rects.toList()
        animateDrillOut(popped, oldRects)
        onDrillChanged?.invoke(currentDrilledGroup())
        return true
    }

    private fun drillInto(hit: StatGroup) {
        val hitRect = rects.firstOrNull { it.second === hit }?.first
        // Snapshot the outgoing layout so we can fade it out under the expanding children.
        val oldRects = rects.toList()
        // Auto-traverse single-child folder chains (e.g. src/main/java/com/example/...) so the
        // user lands directly on the first node with real branching.
        val chain = ArrayList<StatGroup>()
        chain.add(hit)
        var current = hit
        while (current.children.size == 1 && current.children[0].children.isNotEmpty()) {
            current = current.children[0]
            chain.add(current)
        }
        val srcRect = hitRect?.let { Rectangle2D.Double(it.x, it.y, it.width, it.height) }
            ?: Rectangle2D.Double(0.0, 0.0, width.toDouble(), height.toDouble())
        drillStack.addLast(DrillStep(chain, srcRect))
        clearHoverPreview()
        animateDrillIn(srcRect, hit, oldRects)
        onDrillChanged?.invoke(current)
    }

    /** Build the new full-panel layout for the current drill level and start a drill-in animation
     *  that interpolates each child from its srcRect-scoped layout to its full-panel layout. */
    private fun animateDrillIn(
        srcRect: Rectangle2D.Double,
        ghostFocusGroup: StatGroup,
        oldRects: List<Pair<Rectangle2D.Double, StatGroup>>,
    ) {
        val w = width.toDouble()
        val h = height.toDouble()
        val data = currentGroups().filter { it.value(metric) > 0 }.sortedByDescending { it.value(metric) }
        // Always recompute layout for the new level.
        layoutDirty = true
        if (data.isEmpty() || w <= 2 || h <= 2 || srcRect.width < 2 || srcRect.height < 2) {
            stopAnimation()
            repaint()
            return
        }
        val total = data.sumOf { it.value(metric).toDouble() }
        val targetRects = ArrayList<Pair<Rectangle2D.Double, StatGroup>>(data.size)
        squarify(data, total, Rectangle2D.Double(0.0, 0.0, w, h), targetRects)
        rects = targetRects
        layoutW = w
        layoutH = h
        layoutDirty = false

        val sourceRects = ArrayList<Pair<Rectangle2D.Double, StatGroup>>(data.size)
        squarify(data, total, srcRect, sourceRects)
        val fromMap = HashMap<String, Rectangle2D.Double>(sourceRects.size)
        for ((r, g) in sourceRects) fromMap[g.key] = r

        // Filter ghost cells to omit the parent itself (we render it as the focus ghost beneath the
        // expanding children) and any cell that would draw on top of where the children appear.
        val ghosts = oldRects.filter { it.second.key != ghostFocusGroup.key }
        startDrillInAnimation(fromMap, ghosts, srcRect, ghostFocusGroup)
    }

    /** Build the new full-panel layout (one level shallower) and start a drill-out animation that
     *  collapses the previously-shown children into the parent's NEW position in the new layout. */
    private fun animateDrillOut(popped: DrillStep, oldChildren: List<Pair<Rectangle2D.Double, StatGroup>>) {
        val w = width.toDouble()
        val h = height.toDouble()
        val data = currentGroups().filter { it.value(metric) > 0 }.sortedByDescending { it.value(metric) }
        layoutDirty = true
        if (data.isEmpty() || w <= 2 || h <= 2 || oldChildren.isEmpty()) {
            stopAnimation()
            repaint()
            return
        }
        val total = data.sumOf { it.value(metric).toDouble() }
        val targetRects = ArrayList<Pair<Rectangle2D.Double, StatGroup>>(data.size)
        squarify(data, total, Rectangle2D.Double(0.0, 0.0, w, h), targetRects)
        rects = targetRects
        layoutW = w
        layoutH = h
        layoutDirty = false

        // Where does the parent (chain[0]) live in the NEW layout? That's the collapse target.
        val parentKey = popped.chain.first().key
        val collapseTargetRect = targetRects.firstOrNull { it.second.key == parentKey }?.first
            ?: popped.srcRectAtPush
        val collapseTargetGroup = targetRects.firstOrNull { it.second.key == parentKey }?.second
            ?: popped.chain.first()

        // The "ghost" cells are the children we were just looking at. They start at their old
        // full-panel positions and shrink toward the parent's new rect.
        val fromMap = HashMap<String, Rectangle2D.Double>(oldChildren.size)
        for ((r, g) in oldChildren) fromMap[g.key] = r
        startDrillOutAnimation(fromMap, oldChildren, collapseTargetRect, collapseTargetGroup)
    }

    private fun currentGroups(): List<StatGroup> =
        if (drillStack.isEmpty()) groups else drillStack.last().target.children

    private fun hitTest(p: Point): StatGroup? =
        rects.firstOrNull { it.first.contains(p.x.toDouble(), p.y.toDouble()) }?.second



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
        val t = if (animating) easedT() else 1.0

        when {
            animating && animKind == AnimKind.DRILL_IN -> paintDrillIn(g2, t)
            animating && animKind == AnimKind.DRILL_OUT -> paintDrillOut(g2, t)
            else -> {
                // METRIC anim or no anim. Each cell tweens between its old and new position.
                val fromMap = if (animating && animKind == AnimKind.METRIC) animFromRects else null
                for ((rect, grp) in rects) {
                    val displayRect = interpolateRect(fromMap?.get(grp.key), rect, t)
                    paintCell(g2, displayRect, grp, fillAlpha = 1f, drawLabel = false)
                }
            }
        }

        // Hover preview — only when not in the middle of an animation.
        if (!animating) paintHoverPreview(g2)

        // Paint labels on top of everything (including hover preview) so they're always visible.
        if (!animating) {
            val fromMap = if (animating && animKind == AnimKind.METRIC) animFromRects else null
            for ((rect, grp) in rects) {
                val displayRect = interpolateRect(fromMap?.get(grp.key), rect, t)
                drawCellLabel(g2, displayRect, grp, colorFor(grp))
            }
        }

        // In-treemap breadcrumb (double-click modes only — directory mode shows a toolbar breadcrumb).
        if (drillStack.isNotEmpty() && !singleClickDrill) {
            val crumb = "▲ ${currentPath()}  (right-click or double-click a leaf to go back)"
            g2.color = JBColor.foreground()
            g2.drawString(crumb, 6, height - 6)
        }
    }

    private fun paintDrillIn(g2: Graphics2D, t: Double) {
        // Outgoing layer: parent + siblings fade out at their old positions.
        val ghostAlpha = (1.0 - t).toFloat().coerceIn(0f, 1f)
        if (ghostAlpha > 0.01f) {
            for ((rect, grp) in drillGhostCells) {
                paintCell(g2, rect, grp, fillAlpha = ghostAlpha, drawLabel = false)
            }
            // Focus parent — painted under children with stronger alpha so children look like
            // they're emerging from inside it.
            val focusRect = drillGhostFocusRect
            val focusGroup = drillGhostFocusGroup
            if (focusRect != null && focusGroup != null) {
                paintCell(g2, focusRect, focusGroup, fillAlpha = ghostAlpha, drawLabel = false)
            }
        }
        // Incoming layer: children expand from src→target.
        val from = drillFromRects
        val childAlpha = (0.3f + 0.7f * t.toFloat()).coerceIn(0f, 1f)
        for ((rect, grp) in rects) {
            val displayRect = interpolateRect(from?.get(grp.key), rect, t)
            paintCell(g2, displayRect, grp, fillAlpha = childAlpha, drawLabel = t > 0.5)
        }
    }

    private fun paintDrillOut(g2: Graphics2D, t: Double) {
        // Incoming layer: new (shallower) layout fades in.
        val incomingAlpha = t.toFloat().coerceIn(0f, 1f)
        for ((rect, grp) in rects) {
            paintCell(g2, rect, grp, fillAlpha = incomingAlpha, drawLabel = t > 0.6)
        }
        // Outgoing layer: previously-shown children shrink toward the collapse target rect.
        val from = drillFromRects ?: return
        val target = drillGhostFocusRect ?: return
        val ghostAlpha = (1.0 - t).toFloat().coerceIn(0f, 1f)
        if (ghostAlpha < 0.01f) return
        for ((_, grp) in drillGhostCells) {
            val src = from[grp.key] ?: continue
            val displayRect = interpolateRect(src, target, t)
            paintCell(g2, displayRect, grp, fillAlpha = ghostAlpha, drawLabel = false)
        }
    }

    private fun paintCell(
        g2: Graphics2D,
        rect: Rectangle2D.Double,
        grp: StatGroup,
        fillAlpha: Float,
        drawLabel: Boolean,
    ) {
        if (rect.width < 0.5 || rect.height < 0.5) return
        val color = colorFor(grp)
        val prev = g2.composite
        if (fillAlpha < 0.999f) {
            g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, fillAlpha)
        }
        g2.color = color
        g2.fill(rect)
        g2.color = JBColor.border()
        g2.draw(rect)
        if (drawLabel) drawCellLabel(g2, rect, grp, color)
        g2.composite = prev
    }

    /** Render a hover preview by painting the hovered cell's children scaled to fit inside it. */
    private fun paintHoverPreview(g2: Graphics2D) {
        if (hoverChildRects.isEmpty()) return
        val prev = g2.composite
        g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f)
        for ((rect, grp) in hoverChildRects) {
            if (rect.width < 1 || rect.height < 1) continue
            g2.color = colorFor(grp)
            g2.fill(rect)
            g2.color = JBColor.border()
            g2.draw(rect)
        }
        g2.composite = prev
    }

    private fun updateHoverPreview(hit: StatGroup?) {
        // Don't show previews while an animation is running — too visually noisy.
        if (animTimer != null && animT < 1f) {
            clearHoverPreview()
            return
        }
        if (hit == null || hit.children.size < 3) {
            clearHoverPreview()
            return
        }
        if (hit === hoverGroup) return
        val rect = rects.firstOrNull { it.second === hit }?.first ?: run {
            clearHoverPreview(); return
        }
        // Need enough space for the preview to be readable.
        if (rect.width < 80 || rect.height < 60) {
            clearHoverPreview()
            return
        }
        // Hover preview fills the entire parent cell with no margins.
        val inner = Rectangle2D.Double(
            rect.x, rect.y,
            rect.width,
            rect.height,
        )
        val data = hit.children.filter { it.value(metric) > 0 }.sortedByDescending { it.value(metric) }
        val total = data.sumOf { it.value(metric).toDouble() }
        val out = ArrayList<Pair<Rectangle2D.Double, StatGroup>>(data.size)
        if (total > 0 && inner.width > 4 && inner.height > 4) {
            squarify(data, total, inner, out)
        }
        hoverGroup = hit
        hoverChildRects = out
        repaint()
    }

    private fun clearHoverPreview() {
        if (hoverGroup == null && hoverChildRects.isEmpty()) return
        hoverGroup = null
        hoverChildRects = emptyList()
        repaint()
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
    Metric.LOC, Metric.NON_BLANK_LOC, Metric.CODE_LOC,
    Metric.COVERED_LOC, Metric.UNCOVERED_LOC -> "%,d lines".format(value)

    Metric.COMPLEXITY -> "%,d".format(value)
    Metric.FILE_COUNT -> "%,d files".format(value)
    Metric.COMMIT_COUNT -> "%,d commits".format(value)
}
