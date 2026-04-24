package com.github.projectstats

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class ProjectStatsToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ProjectStatsPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.isCloseable = false
        toolWindow.contentManager.addContent(content)

        toolWindow.setTitleActions(
            listOf(
            object : AnAction("Manage Coverage Reports", null, AllIcons.General.Settings) {
                override fun actionPerformed(e: AnActionEvent) = panel.showCoverageReportsDialog()
                override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
            },
            object : AnAction("Refresh", null, AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) = panel.runScan()
                override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
                override fun update(e: AnActionEvent) {
                    e.presentation.icon = if (panel.isScanning) com.intellij.ui.AnimatedIcon.Default.INSTANCE
                    else AllIcons.Actions.Refresh
                    e.presentation.isEnabled = !panel.isScanning
                }
            }
        ))
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
