package com.github.projectstats

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.*
import javax.swing.*
import javax.swing.event.ChangeEvent
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableRowSorter

class ProjectStatsPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val groupByBox = ComboBox(GroupBy.entries.toTypedArray()).apply { selectedItem = GroupBy.LANGUAGE }
    private val metricBox = ComboBox(Metric.entries.toTypedArray()).apply { selectedItem = Metric.LOC }
    private val includeTests = JBCheckBox("Tests", true)
    private val includeGenerated = JBCheckBox("Generated", false)
    private val includeResources = JBCheckBox("Resources", true)
    private val includeOther = JBCheckBox("Other", true)
    private val refreshBtn = JButton(AllIcons.Actions.Refresh).apply {
        toolTipText = "Refresh stats"
        isFocusable = false
        isBorderPainted = false
        isContentAreaFilled = false
        margin = JBUI.emptyInsets()
        putClientProperty("JButton.buttonType", "toolBarButton")
    }
    private val footerStatus = JBLabel(" ")
    private val kpiFiles = kpiLabel()
    private val kpiLoc = kpiLabel()
    private val kpiSize = kpiLabel()
    private val kpiScan = kpiLabel()
    private val breadcrumbRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
        border = JBUI.Borders.emptyTop(2)
        isVisible = false
    }

    private val treemap = TreemapPanel()
    private val stackedBar = StackedBarPanel()
    private val tableModel = StatsTableModel()
    private val table = JBTable(tableModel)
    private val totalsModel = TotalsTableModel()
    private val totalsTable = object : JBTable(totalsModel) {
        // Overriding columnMarginChanged to skip revalidate() prevents a repaint feedback loop.
        // The shared columnModel fires this on every column resize from the main table's viewport
        // layout; the default resizeAndRepaint() calls revalidate() which propagates to the root
        // pane and re-triggers JBScrollPane layout, which may resize the viewport again → loop.
        override fun columnMarginChanged(e: ChangeEvent) {
            if (isEditing) cellEditor?.cancelCellEditing()
            repaint()
        }
    }

    private val treemapCard = JPanel(CardLayout())
    private val tableCard = JPanel(CardLayout())

    private var scanResult: ScanResult? = null
    private var rootGroups: List<StatGroup> = emptyList()
    private var currentColorFn: (StatGroup) -> Color = { JBColor.GRAY }
    private var scanning: Boolean = false
    private var lastScanResult: ScanResult? = null
    private var lastGroupBy: GroupBy? = null
    private var lastMetric: Metric? = null

    companion object {
        private const val CARD_EMPTY = "empty"
        private const val CARD_DATA = "data"
    }

    init {
        border = JBUI.Borders.empty(4)

        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2))
        toolbar.add(JLabel("Group by:"))
        toolbar.add(groupByBox)
        toolbar.add(JLabel("  Metric:"))
        toolbar.add(metricBox)
        toolbar.add(Box.createHorizontalStrut(8))
        toolbar.add(JLabel("Include:"))
        toolbar.add(includeTests)
        toolbar.add(includeGenerated)
        toolbar.add(includeResources)
        toolbar.add(includeOther)

        val header = JPanel(BorderLayout())
        header.add(toolbar, BorderLayout.NORTH)
        header.add(breadcrumbRow, BorderLayout.SOUTH)

        val barPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyTop(4)
            add(stackedBar, BorderLayout.CENTER)
            preferredSize = Dimension(400, 24)
        }

        configureTable()
        configureTotalsTable()

        val tableBlock = JPanel(BorderLayout()).apply {
            add(JBScrollPane(table), BorderLayout.CENTER)
            add(totalsTable, BorderLayout.SOUTH)
        }

        val centerTop = JPanel(BorderLayout()).apply {
            add(barPanel, BorderLayout.NORTH)
            add(treemap, BorderLayout.CENTER)
        }

        treemapCard.add(createEmptyPanel(), CARD_EMPTY)
        treemapCard.add(centerTop, CARD_DATA)

        tableCard.add(createEmptyPanel(), CARD_EMPTY)
        tableCard.add(tableBlock, CARD_DATA)

        val split = OnePixelSplitter(false, 0.6f).apply {
            firstComponent = treemapCard
            secondComponent = tableCard
        }

        val kpis = JPanel(FlowLayout(FlowLayout.LEFT, 16, 2)).apply {
            add(kpiBlock("Files", kpiFiles))
            add(kpiBlock("LOC", kpiLoc))
            add(kpiBlock("Size", kpiSize))
            add(kpiBlock("Scan", kpiScan))
        }
        val footer = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyTop(4)
            add(kpis, BorderLayout.CENTER)
            add(refreshBtn, BorderLayout.EAST)
            add(footerStatus, BorderLayout.SOUTH)
        }

        add(header, BorderLayout.NORTH)
        add(split, BorderLayout.CENTER)
        add(footer, BorderLayout.SOUTH)

        refreshBtn.addActionListener { runScan() }
        groupByBox.addActionListener { refreshViews() }
        metricBox.addActionListener { refreshViews() }
        includeTests.addActionListener { refreshViews() }
        includeGenerated.addActionListener { refreshViews() }
        includeResources.addActionListener { refreshViews() }
        includeOther.addActionListener { refreshViews() }

        showCard(treemapCard, CARD_EMPTY)
        showCard(tableCard, CARD_EMPTY)

        treemap.setOnDrillChanged { applyDrill(it) }
    }

    private fun createEmptyPanel(): JPanel {
        return JPanel(GridBagLayout()).apply {
            val gbc = GridBagConstraints().apply {
                gridx = 0
                gridy = GridBagConstraints.RELATIVE
                anchor = GridBagConstraints.CENTER
                insets = JBUI.insets(5, 0)
            }
            val titleLabel = JBLabel("No stats yet").apply {
                font = font.deriveFont(Font.BOLD, 16f)
            }
            val subLabel = JBLabel("Scan your project to see code statistics").apply {
                foreground = JBColor.GRAY
            }
            val scanBtn = JButton("Scan Project").apply {
                font = font.deriveFont(Font.PLAIN, font.size2D + 1f)
                addActionListener { runScan() }
            }
            add(titleLabel, gbc)
            add(subLabel, gbc)
            add(Box.createVerticalStrut(4), gbc)
            add(scanBtn, gbc)
        }
    }

    private fun showCard(panel: JPanel, card: String) {
        (panel.layout as CardLayout).show(panel, card)
    }

    private fun configureTable() {
        table.autoCreateRowSorter = false
        val sorter = TableRowSorter(tableModel)
        table.rowSorter = sorter
        sorter.sortKeys = listOf(RowSorter.SortKey(COL_LOC, SortOrder.DESCENDING))
        val renderer = statsCellRenderer(boldFont = null)
        table.setDefaultRenderer(Number::class.java, renderer)
        table.setDefaultRenderer(Any::class.java, renderer)
    }

    private fun configureTotalsTable() {
        totalsTable.tableHeader = null
        totalsTable.setShowGrid(false)
        totalsTable.intercellSpacing = Dimension(0, 0)
        totalsTable.isFocusable = false
        totalsTable.rowSelectionAllowed = false
        totalsTable.columnModel = table.columnModel
        totalsTable.rowHeight = table.rowHeight + 2
        totalsTable.border = BorderFactory.createMatteBorder(2, 0, 0, 0, JBColor.border())
        val renderer = statsCellRenderer(boldFont = totalsTable.font.deriveFont(Font.BOLD))
        totalsTable.setDefaultRenderer(Number::class.java, renderer)
        totalsTable.setDefaultRenderer(Any::class.java, renderer)
    }

    /** Shared renderer used by both the data table and the bold totals row. */
    private fun statsCellRenderer(boldFont: Font?): DefaultTableCellRenderer =
        object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
            ): Component {
                val selected = boldFont == null && isSelected
                val focused = boldFont == null && hasFocus
                val c = super.getTableCellRendererComponent(table, value, selected, focused, row, column)
                if (boldFont != null) font = boldFont
                horizontalAlignment = if (column == 0) LEFT else RIGHT
                text = formatStatsCell(table.convertColumnIndexToModel(column), value)
                return c
            }
        }

    private fun formatStatsCell(modelColumn: Int, value: Any?): String = when (modelColumn) {
        COL_FILES, COL_LOC, COL_NON_BLANK, COL_CODE_LOC, COL_COMPLEXITY, COL_COMMITS, COL_CHILDREN ->
            compactCount((value as? Number)?.toLong() ?: 0L)

        COL_SIZE -> humanBytes((value as? Number)?.toLong() ?: 0L)
        COL_PERCENT -> String.format(Locale.US, "%.1f%%", (value as? Number)?.toDouble() ?: 0.0)
        else -> value?.toString().orEmpty()
    }

    private fun kpiLabel(): JBLabel = JBLabel("–").apply {
        font = font.deriveFont(Font.BOLD, font.size2D + 3f)
    }

    private fun kpiBlock(caption: String, value: JBLabel): JPanel {
        val block = JPanel()
        block.layout = BorderLayout(0, 0)
        val cap = JBLabel(caption).apply {
            foreground = JBColor.GRAY
            font = font.deriveFont(font.size2D - 1f)
        }
        block.add(cap, BorderLayout.NORTH)
        block.add(value, BorderLayout.CENTER)
        return block
    }

    fun runScan() {
        setScanning(true)
        footerStatus.text = "Scanning…"
        object : Task.Backgroundable(project, "Computing project statistics", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                indicator.text = "Scanning project files"
                val result = ProjectScanner.scan(project, indicator)
                ApplicationManager.getApplication().invokeLater {
                    scanResult = result
                    setScanning(false)
                    footerStatus.text = result.coverageSource?.let { "Coverage: $it" } ?: " "
                    refreshViews()
                }
            }

            override fun onCancel() {
                ApplicationManager.getApplication().invokeLater {
                    setScanning(false)
                    footerStatus.text = "Scan cancelled."
                }
            }

            override fun onThrowable(error: Throwable) {
                ApplicationManager.getApplication().invokeLater {
                    setScanning(false)
                    footerStatus.text = "Scan failed: ${error.message}"
                }
            }
        }.queue()
    }

    private fun setScanning(running: Boolean) {
        scanning = running
        refreshBtn.icon = if (running) AnimatedIcon.Default.INSTANCE else AllIcons.Actions.Refresh
        refreshBtn.toolTipText = if (running) "Scanning…" else "Refresh stats"
        refreshBtn.isEnabled = !running
    }

    private fun refreshViews() {
        val result = scanResult ?: run {
            setKpis(0L, 0L, 0L, 0L)
            rootGroups = emptyList()
            currentColorFn = { JBColor.GRAY }
            treemap.setSingleClickDrill(false)
            treemap.setData(emptyList(), Metric.LOC, currentColorFn)
            stackedBar.setData(emptyList(), Metric.LOC, { JBColor.GRAY })
            tableModel.update(emptyList(), Metric.LOC)
            totalsModel.clear()
            showCard(treemapCard, CARD_EMPTY)
            showCard(tableCard, CARD_EMPTY)
            updateBreadcrumbs()
            lastScanResult = null
            lastGroupBy = null
            lastMetric = null
            return
        }
        val groupBy = groupByBox.selectedItem as GroupBy
        val metric = metricBox.selectedItem as Metric
        val groups = StatsAggregator.aggregate(
            result,
            groupBy,
            includeTests.isSelected,
            includeGenerated.isSelected,
            includeResources.isSelected,
            includeOther.isSelected,
        )
        currentColorFn = when (groupBy) {
            GroupBy.CATEGORY -> { g -> categoryColor(g.key) }
            else -> { g -> hashColor(g.key) }
        }
        rootGroups = groups
        val scanChanged = result !== lastScanResult
        val groupByChanged = groupBy != lastGroupBy
        val metricChanged = metric != lastMetric
        val preservePath = !scanChanged && !groupByChanged
        val animate = preservePath && metricChanged
        treemap.setSingleClickDrill(groupBy == GroupBy.DIRECTORY)
        treemap.setData(groups, metric, currentColorFn, preservePath, animate)
        showCard(treemapCard, CARD_DATA)
        showCard(tableCard, CARD_DATA)
        applyDrill(treemap.currentDrilledGroup(), animate)
        lastScanResult = result
        lastGroupBy = groupBy
        lastMetric = metric
    }

    private fun applyDrill(drilled: StatGroup?, animateBar: Boolean = false) {
        val result = scanResult ?: return
        val metric = metricBox.selectedItem as Metric
        val shown = drilled?.children ?: rootGroups
        stackedBar.setData(shown, metric, currentColorFn, animateBar)
        tableModel.update(shown, metric)
        // fireTableDataChanged() preserves the column model but doesn't repaint
        // the header — repaint explicitly so column 8 ("% of <Metric>") stays current.
        table.tableHeader?.repaint()

        setKpis(result.fileCount, result.totalLines, result.sizeBytes, result.scannedMillis)
        val scope = drilled ?: result.asStatGroup("Total")
        totalsModel.update(drilled?.key ?: "Total", scope)
        updateBreadcrumbs()
    }

    private fun setKpis(files: Long, loc: Long, size: Long, scanMs: Long) {
        kpiFiles.text = "%,d".format(files)
        kpiLoc.text = "%,d".format(loc)
        kpiSize.text = humanBytes(size)
        kpiScan.text = "$scanMs ms"
    }

    private fun updateBreadcrumbs() {
        breadcrumbRow.removeAll()
        val isDir = (groupByBox.selectedItem as? GroupBy) == GroupBy.DIRECTORY
        if (!isDir) {
            breadcrumbRow.isVisible = false
            breadcrumbRow.revalidate()
            breadcrumbRow.repaint()
            return
        }
        breadcrumbRow.isVisible = true
        breadcrumbRow.add(JLabel("Path:"))
        breadcrumbRow.add(crumbLabel("<project>", 0))
        val depth = treemap.drillDepth()
        for (i in 0 until depth) {
            breadcrumbRow.add(JLabel("/"))
            breadcrumbRow.add(crumbLabel(treemap.drillStepDisplay(i), i + 1))
        }
        breadcrumbRow.revalidate()
        breadcrumbRow.repaint()
    }

    private fun crumbLabel(text: String, targetDepth: Int): JLabel {
        val isCurrent = targetDepth == treemap.drillDepth()
        val label = JLabel(if (isCurrent) "<html><b>$text</b></html>" else "<html><a href=''>$text</a></html>")
        if (!isCurrent) {
            label.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            label.toolTipText = "Go to $text"
            label.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    treemap.popToDepth(targetDepth)
                }
            })
        }
        return label
    }

    private fun categoryColor(key: String): Color = when (key) {
        SourceCategory.SOURCE.display -> JBColor(Color(0x3F8EDC), Color(0x4A90E2))
        SourceCategory.TEST.display -> JBColor(Color(0x5CB85C), Color(0x4CAF50))
        SourceCategory.RESOURCES.display -> JBColor(Color(0xF0AD4E), Color(0xE08E0B))
        SourceCategory.TEST_RESOURCES.display -> JBColor(Color(0xD9A441), Color(0xB8860B))
        SourceCategory.GENERATED.display -> JBColor(Color(0xB577C9), Color(0x9C27B0))
        SourceCategory.OTHER.display -> JBColor(Color(0x999999), Color(0x777777))
        else -> hashColor(key)
    }
}

// Stats-table model column indexes (0..9). Shared by StatsTableModel and TotalsTableModel
// so renderer/formatter code doesn't duplicate the layout knowledge.
private const val COL_NAME = 0
private const val COL_FILES = 1
private const val COL_LOC = 2
private const val COL_NON_BLANK = 3
private const val COL_CODE_LOC = 4
private const val COL_COMPLEXITY = 5
private const val COL_COMMITS = 6
private const val COL_SIZE = 7
private const val COL_PERCENT = 8
private const val COL_CHILDREN = 9
private const val STATS_COLUMN_COUNT = 10

private class StatsTableModel : AbstractTableModel() {
    private var rows: List<StatGroup> = emptyList()
    private var total: Long = 0
    private var metric: Metric = Metric.LOC

    fun update(groups: List<StatGroup>, metric: Metric) {
        this.rows = groups
        this.metric = metric
        this.total = groups.sumOf { it.value(metric) }
        // fireTableDataChanged — not fireTableStructureChanged — so the shared TableColumnModel
        // is preserved (structure never changes; always STATS_COLUMN_COUNT columns).
        fireTableDataChanged()
    }

    override fun getRowCount(): Int = rows.size
    override fun getColumnCount(): Int = STATS_COLUMN_COUNT
    override fun getColumnName(column: Int): String = when (column) {
        COL_NAME -> "Name"
        COL_FILES -> "Files"
        COL_LOC -> "LOC"
        COL_NON_BLANK -> "Non-blank"
        COL_CODE_LOC -> "Code LOC"
        COL_COMPLEXITY -> "Complexity"
        COL_COMMITS -> "Commits"
        COL_SIZE -> "Size"
        COL_PERCENT -> "% of ${metric.display}"
        COL_CHILDREN -> "Children"
        else -> ""
    }

    override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
        COL_FILES, COL_LOC, COL_NON_BLANK, COL_CODE_LOC,
        COL_COMPLEXITY, COL_COMMITS, COL_SIZE, COL_CHILDREN -> Long::class.javaObjectType

        COL_PERCENT -> Double::class.javaObjectType
        else -> String::class.java
    }

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val r = rows[rowIndex]
        return when (columnIndex) {
            COL_NAME -> r.key
            COL_FILES -> r.fileCount
            COL_LOC -> r.totalLines
            COL_NON_BLANK -> r.nonBlankLines
            COL_CODE_LOC -> r.codeLines
            COL_COMPLEXITY -> r.complexity
            COL_COMMITS -> r.commitCount
            COL_SIZE -> r.sizeBytes
            COL_PERCENT -> if (total > 0) 100.0 * r.value(metric) / total else 0.0
            COL_CHILDREN -> r.children.size.toLong()
            else -> ""
        }
    }
}

private class TotalsTableModel : AbstractTableModel() {
    private var label: String = "Total"
    private var totals: StatGroup? = null

    fun update(label: String, totals: StatGroup) {
        this.label = label
        this.totals = totals
        fireTableDataChanged()
    }

    fun clear() {
        totals = null
        fireTableDataChanged()
    }

    override fun getRowCount(): Int = if (totals != null) 1 else 0
    override fun getColumnCount(): Int = STATS_COLUMN_COUNT
    override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
        COL_FILES, COL_LOC, COL_NON_BLANK, COL_CODE_LOC,
        COL_COMPLEXITY, COL_COMMITS, COL_SIZE, COL_CHILDREN -> Long::class.javaObjectType

        COL_PERCENT -> Double::class.javaObjectType
        else -> String::class.java
    }

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val t = totals ?: return ""
        return when (columnIndex) {
            COL_NAME -> "Σ  $label"
            COL_FILES -> t.fileCount
            COL_LOC -> t.totalLines
            COL_NON_BLANK -> t.nonBlankLines
            COL_CODE_LOC -> t.codeLines
            COL_COMPLEXITY -> t.complexity
            COL_COMMITS -> t.commitCount
            COL_SIZE -> t.sizeBytes
            COL_PERCENT -> 100.0
            else -> ""
        }
    }
}

/** Synthesize a root-level [StatGroup] from a [ScanResult] — used by the totals row at drill-depth 0. */
private fun ScanResult.asStatGroup(label: String): StatGroup =
    StatGroup(label, totalLines, nonBlankLines, codeLines, complexity, sizeBytes, fileCount, commitCount)

fun compactCount(value: Long): String {
    val abs = kotlin.math.abs(value.toDouble())
    if (abs < 1000.0) return value.toString()
    val units = arrayOf("K", "M", "B", "T")
    var scaled = abs / 1000.0
    var unitIndex = 0
    while (scaled >= 1000.0 && unitIndex < units.lastIndex) {
        scaled /= 1000.0
        unitIndex++
    }
    val prefix = if (value < 0) "-" else ""
    return prefix + String.format(Locale.US, "%.1f%s", scaled, units[unitIndex])
}
