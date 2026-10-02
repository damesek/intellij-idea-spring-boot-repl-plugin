package hu.baader.repl.ui

import java.awt.Component
import javax.swing.JTabbedPane

/** Primary workflows stay visible; supporting panels share one Tools page. */
open class ReplTabs : JTabbedPane() {
    private val tools = JTabbedPane(LEFT)

    val activePanel: Component?
        get() = if (selectedComponent === tools) tools.selectedComponent else selectedComponent

    fun install(workbook: Component, mcp: Component) {
        addTab("Workbook", workbook)
        addTab("MCP", mcp)
        addTab("Tools", tools)
        setToolTipTextAt(0, "Write and run Java cells in your application")
        setToolTipTextAt(1, "Connect an AI client to the complete local MCP toolset")
        setToolTipTextAt(2, "Variables, snapshots, inspection, debugging and other supporting tools")
    }

    fun addTool(title: String, panel: Component) = tools.addTab(title, panel)

    fun showPanel(panel: Component) {
        if (indexOfComponent(panel) >= 0) {
            selectedComponent = panel
        } else {
            require(tools.indexOfComponent(panel) >= 0) { "Unknown REPL panel" }
            tools.selectedComponent = panel
            selectedComponent = tools
        }
    }

    fun showTool(title: String) {
        val index = tools.indexOfTab(title)
        require(index >= 0) { "Unknown REPL tool: $title" }
        showPanel(tools.getComponentAt(index))
    }

    fun onPanelSelected(action: () -> Unit) {
        addChangeListener { action() }
        tools.addChangeListener { if (selectedComponent === tools) action() }
    }
}
