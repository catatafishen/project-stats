package com.github.projectstats

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseEvent
import java.awt.geom.Rectangle2D
import javax.swing.JPanel
import javax.swing.Timer

/**
 * GitHub-style horizontal stacked bar showing percentage of a metric per group.
 */
class StackedBarPanel : JPanel() {
    private var groups: List<StatGroup> = emptyList()
    private var metric: Metric = Metric.LOC
    private var colorFor: (StatGroup) -> Color = { hashColor(it.key) }
    private var rects: List<Pair<Rectangle2D.Double, StatGroup>> = emptyList()

    private var animTimer: Timer? = null
    private var animFromFractions: Map<String, Double>? = null
    private var animT: Float = 1f
    private var animStartNanos: Long = 0L
    private val animDurationMs = 260

    init {
        preferredSize = JBUI.size(400, 18)
        minimumSize = JBUI.size(200, 14)
        toolTipText = ""
    }

    fun setData(groups: List<StatGroup>, metric: Metric, colorFn: (StatGroup) -> Color, animate: Boolean = false) {
        val fromFractions: Map<String, Double>? = if (animate && rects.isNotEmpty() && width > 0) {
            val w = width.toDouble()
            val m = HashMap<String, Double>(rects.size)
            for ((r, g) in rects) m[g.key] = r.width / w
            m
        } else null
        this.groups = groups
        this.metric = metric
        this.colorFor = colorFn
        if (fromFractions != null) startAnimation(fromFractions) else stopAnimation()
        repaint()
    }

    private fun startAnimation(from: Map<String, Double>) {
        stopAnimation()
        animFromFractions = from
        animT = 0f
        animStartNanos = System.nanoTime()
        val timer = Timer(16) {
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
        animFromFractions = null
        animT = 1f
    }

    override fun getToolTipText(event: MouseEvent): String? {
        val hit = rects.firstOrNull { it.first.contains(event.point.x.toDouble(), event.point.y.toDouble()) }?.second
            ?: return null
        val total = groups.sumOf { it.value(metric).toDouble() }.coerceAtLeast(1.0)
        val pct = 100.0 * hit.value(metric) / total
        return "<html><b>${hit.key}</b>: ${format(metric, hit.value(metric))} (%.1f%%)</html>".format(pct)
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val w = width.toDouble()
        val h = height.toDouble()
        val data = groups.filter { it.value(metric) > 0 }.sortedByDescending { it.value(metric) }
        val total = data.sumOf { it.value(metric).toDouble() }
        if (total <= 0 || w < 2) {
            rects = emptyList(); return
        }

        val animating = animTimer != null && animT < 1f
        val fromMap = if (animating) animFromFractions else null
        val t = if (animating) {
            val tv = animT.toDouble().coerceIn(0.0, 1.0)
            tv * tv * (3 - 2 * tv)
        } else 1.0

        val placed = ArrayList<Pair<Rectangle2D.Double, StatGroup>>()
        var x = 0.0
        for (grp in data) {
            val targetFrac = grp.value(metric) / total
            val fromFrac = fromMap?.get(grp.key) ?: targetFrac
            val frac = fromFrac + (targetFrac - fromFrac) * t
            val segW = (w * frac).coerceAtLeast(1.0)
            val rect = Rectangle2D.Double(x, 0.0, segW, h)
            placed += rect to grp
            g2.color = colorFor(grp)
            g2.fill(rect)
            x += segW
        }
        rects = placed
        g2.color = JBColor.border()
        g2.drawRect(0, 0, width - 1, height - 1)
    }
}
