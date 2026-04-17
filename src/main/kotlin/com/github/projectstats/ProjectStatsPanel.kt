package com.github.projectstats

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JTable
import javax.swing.RowSorter
import javax.swing.SortOrder
import javax.swing.SwingConstants
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableRowSorter

class ProjectStatsPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val groupByBox = ComboBox(GroupBy.values()).apply { selectedItem = GroupBy.LANGUAGE }
    private val metricBox = ComboBox(Metric.values()).apply { selectedItem = Metric.LOC }
    private val includeTests = JCheckBox("Tests", true)
    private val includeGenerated = JCheckBox("Generated", false)
    private val includeResources = JCheckBox("Resources", true)
    private val includeOther = JCheckBox("Other", true)
    private val refreshBtn = JButton(AllIcons.Actions.Execute).apply {
        toolTipText = "Scan project"
        isFocusable = false
        isBorderPainted = false
        isContentAreaFilled = false
        margin = JBUI.emptyInsets()
        putClientProperty("JButton.buttonType", "toolBarButton")
    }
    private val summary = JBLabel(" ")
    private val breadcrumbRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
        border = JBUI.Borders.emptyTop(2)
        isVisible = false
    }

    private val treemap = TreemapPanel()
    private val stackedBar = StackedBarPanel()
    private val tableModel = StatsTableModel()
    private val table = JBTable(tableModel)

    private var scanResult: ScanResult? = null
    private var rootGroups: List<StatGroup> = emptyList()
    private var currentColorFn: (StatGroup) -> Color = { JBColor.GRAY }
    private var scanning: Boolean = false

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

        val centerTop = JPanel(BorderLayout()).apply {
            add(barPanel, BorderLayout.NORTH)
            add(treemap, BorderLayout.CENTER)
        }

        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, centerTop, JBScrollPane(table)).apply {
            resizeWeight = 0.6
            dividerSize = 4
        }

        val footer = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyTop(4)
            add(summary, BorderLayout.CENTER)
            add(refreshBtn, BorderLayout.EAST)
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

        // Keep table, stacked bar, and summary in sync with the treemap's drill state.
        treemap.setOnDrillChanged { applyDrill(it) }
    }

    private fun configureTable() {
        table.autoCreateRowSorter = false
        val sorter = TableRowSorter(tableModel)
        table.rowSorter = sorter
        sorter.sortKeys = listOf(RowSorter.SortKey(2, SortOrder.DESCENDING))
        table.setDefaultRenderer(Any::class.java, object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
            ): Component {
                val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
                horizontalAlignment = if (column == 0) SwingConstants.LEFT else SwingConstants.RIGHT
                return c
            }
        })
    }

    fun runScan() {
        setScanning(true)
        summary.text = "Scanning…"
        object : Task.Backgroundable(project, "Computing project statistics", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                indicator.text = "Scanning project files"
                val result = ProjectScanner.scan(project, indicator)
                ApplicationManager.getApplication().invokeLater {
                    scanResult = result
                    setScanning(false)
                    refreshViews()
                }
            }

            override fun onCancel() {
                ApplicationManager.getApplication().invokeLater {
                    setScanning(false)
                    summary.text = "Scan cancelled."
                }
            }

            override fun onThrowable(error: Throwable) {
                ApplicationManager.getApplication().invokeLater {
                    setScanning(false)
                    summary.text = "Scan failed: ${error.message}"
                }
            }
        }.queue()
    }

    private fun setScanning(running: Boolean) {
        scanning = running
        refreshBtn.icon = if (running) AnimatedIcon.Default.INSTANCE else AllIcons.Actions.Execute
        refreshBtn.toolTipText = if (running) "Scanning…" else "Scan project"
        refreshBtn.isEnabled = !running
    }

    private fun refreshViews() {
        val result = scanResult ?: run {
            summary.text = "Click the play button to scan the project."
            rootGroups = emptyList()
            currentColorFn = { JBColor.GRAY }
            treemap.setSingleClickDrill(false)
            treemap.setData(emptyList(), Metric.LOC, currentColorFn)
            stackedBar.setData(emptyList(), Metric.LOC) { JBColor.GRAY }
            tableModel.update(emptyList(), Metric.LOC)
            updateBreadcrumbs()
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
        // Stable coloring for flat dimensions; directory gets per-name coloring too.
        currentColorFn = when (groupBy) {
            GroupBy.CATEGORY -> { g -> categoryColor(g.key) }
            else -> { g -> hashColor(g.key) }
        }
        rootGroups = groups
        treemap.setSingleClickDrill(groupBy == GroupBy.DIRECTORY)
        // setData clears drill internally; we then render the (now-root) level into the table/bar.
        treemap.setData(groups, metric, currentColorFn)
        applyDrill(null)
    }

    /**
     * Sync table, stacked bar, and summary with the treemap's current drill level.
     * `drilled == null` means root level (top-level aggregated groups).
     */
    private fun applyDrill(drilled: StatGroup?) {
        val result = scanResult ?: return
        val metric = metricBox.selectedItem as Metric
        val shown = drilled?.children ?: rootGroups
        stackedBar.setData(shown, metric, currentColorFn)
        tableModel.update(shown, metric)

        val scopeFiles = drilled?.fileCount ?: result.fileCount
        val scopeTotal = drilled?.totalLines ?: result.totalLines
        val scopeNonBlank = drilled?.nonBlankLines ?: result.nonBlankLines
        val scopeCode = drilled?.codeLines ?: result.codeLines
        val scopeCplx = drilled?.complexity ?: result.complexity
        val scopeSize = drilled?.sizeBytes ?: result.sizeBytes
        val scopeCommits = drilled?.commitCount ?: result.commitCount
        val totalMetric = shown.sumOf { it.value(metric) }
        summary.text = buildString {
            append("Files: %,d | ".format(scopeFiles))
            append("Total LOC: %,d | ".format(scopeTotal))
            append("Non-blank: %,d | ".format(scopeNonBlank))
            append("Code LOC: %,d | ".format(scopeCode))
            append("Complexity: %,d | ".format(scopeCplx))
            append("Commits: %,d | ".format(scopeCommits))
            append("Size: ${humanBytes(scopeSize)} | ")
            append("Scan: ${result.scannedMillis} ms | ")
            append("Shown (${metric.display}): ${format(metric, totalMetric)}")
        }
        updateBreadcrumbs()
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

private class StatsTableModel : AbstractTableModel() {
    private var rows: List<StatGroup> = emptyList()
    private var total: Long = 0
    private var metric: Metric = Metric.LOC

    fun update(groups: List<StatGroup>, metric: Metric) {
        this.rows = groups
        this.metric = metric
        this.total = groups.sumOf { it.value(metric) }
        fireTableStructureChanged()
    }

    override fun getRowCount(): Int = rows.size
    override fun getColumnCount(): Int = 10
    override fun getColumnName(column: Int): String = when (column) {
        0 -> "Name"
        1 -> "Files"
        2 -> "LOC"
        3 -> "Non-blank"
        4 -> "Code LOC"
        5 -> "Complexity"
        6 -> "Commits"
        7 -> "Size"
        8 -> "% of ${metric.display}"
        9 -> "Children"
        else -> ""
    }

    override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
        1, 2, 3, 4, 5, 6, 9 -> java.lang.Long::class.java
        7 -> java.lang.Long::class.java
        8 -> java.lang.Double::class.java
        else -> String::class.java
    }

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val r = rows[rowIndex]
        return when (columnIndex) {
            0 -> r.key
            1 -> r.fileCount
            2 -> r.totalLines
            3 -> r.nonBlankLines
            4 -> r.codeLines
            5 -> r.complexity
            6 -> r.commitCount
            7 -> r.sizeBytes
            8 -> if (total > 0) 100.0 * r.value(metric) / total else 0.0
            9 -> r.children.size.toLong()
            else -> ""
        }
    }
}
