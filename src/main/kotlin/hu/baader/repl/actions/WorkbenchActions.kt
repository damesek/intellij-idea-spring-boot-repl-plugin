package hu.baader.repl.actions

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import javax.swing.JComponent

/** One command set for the toolbar, menus, Find Action and user-assigned shortcuts. */
object WorkbenchCatalog {
    data class Command(val key: String, val label: String, val group: String, val connected: Boolean = false, val executes: Boolean = false)
    val commands = listOf(
        Command("Connect", "Connect", "Session"), Command("OpenEndpoint", "Open endpoint", "Session"),
        Command("Disconnect", "Disconnect", "Session", true), Command("Bind", "Bind", "Session", true),
        Command("RunCell", "Run cell / selection", "Run", true, true), Command("RunNext", "Run + next", "Run", true, true),
        Command("RunAll", "Run all", "Run", true, true), Command("Interrupt", "Interrupt", "Run", true),
        Command("Reset", "Reset", "Session", true), Command("Complete", "Complete", "Tools", true),
        Command("LiveCheck", "Toggle live check", "Tools"), Command("Check", "Check code", "Tools", true),
        Command("Help", "Help (PDF)", "Tools"), Command("MCP", "MCP", "Tools"),
        Command("RunAbove", "Run above", "Run", true, true), Command("RunFrom", "Run from here", "Run", true, true),
        Command("RunAffected", "Run affected", "Run", true, true), Command("RestartAll", "Restart + run all", "Run", true, true),
        Command("Analyze", "Analyze dependencies", "Run", true), Command("SaveWorkspace", "Save workspace", "Workspace"),
        Command("OpenWorkspace", "Open workspace", "Workspace"), Command("WorkspaceInfo", "Workspace info", "Workspace"),
        Command("RestoreData", "Restore DATA binding", "Workspace", true), Command("SavedImports", "Insert saved imports", "Workspace"),
        Command("InsertCell", "Insert cell", "Workspace"), Command("OpenWorkbook", "Open workbook", "Workspace"),
        Command("SaveWorkbook", "Save workbook", "Workspace"), Command("Recipe", "Save RECIPE", "Workspace", true),
        Command("History", "History", "Workspace"), Command("ClearHistory", "Clear history", "Workspace"),
        Command("Bean", "Insert bean", "Tools", true), Command("Imports", "Apply configured imports", "Tools", true),
        Command("CreateReproduction", "Create reproduction", "Tools", true), Command("ExportReproduction", "Export reproduction", "Tools", true),
        Command("ImportReproduction", "Import reproduction", "Tools", true),
        Command("ExecutionSettings", "Execution settings", "Session", true), Command("RefreshPolicy", "Refresh execution settings", "Session", true),
        Command("SideEffects", "Side-effect hints", "Tools", true), Command("Audit", "Audit events", "Tools", true),
        Command("InspectResult", "Inspect result", "Result", true), Command("PinResult", "Pin LIVE", "Result", true),
        Command("SaveResult", "Freeze DATA", "Result", true), Command("WatchResult", "Watch result", "Result", true),
        Command("BeanExplorer", "Bean explorer", "Tools", true)
    )
    fun byKey(key: String) = commands.firstOrNull { it.key == key }
    fun id(key: String) = "hu.baader.repl.workbench.$key"
}

@Service(Service.Level.PROJECT)
class WorkbenchActions {
    private var inspector: ((Map<String, String>) -> Unit)? = null
    private var handlers: Map<String, () -> Unit> = emptyMap()
    private var unavailable: (WorkbenchCatalog.Command) -> String? = { "Open the Spring Boot REPL tool window" }
    fun bind(actions: Map<String, () -> Unit>, reason: (WorkbenchCatalog.Command) -> String?, parent: Disposable, inspect: (Map<String,String>) -> Unit) {
        val bound = actions.toMap(); handlers = bound; unavailable = reason
        inspector = inspect
        Disposer.register(parent, Disposable { if (handlers === bound) { handlers = emptyMap(); inspector = null; unavailable = { "Open the Spring Boot REPL tool window" } } })
    }
    fun inspect(reference: Map<String,String>) { inspector?.invoke(reference) }
    fun reason(command: WorkbenchCatalog.Command): String? = if (command.label !in handlers) "Open the Spring Boot REPL tool window" else unavailable(command)
    fun perform(key: String) { WorkbenchCatalog.byKey(key)?.takeIf { reason(it) == null }?.let { handlers[it.label]?.invoke() } }
    companion object {
        fun get(project: Project): WorkbenchActions = project.service()
        fun toolbar(target: JComponent): ActionToolbar {
            val manager = ActionManager.getInstance()
            fun action(key: String) = requireNotNull(manager.getAction(WorkbenchCatalog.id(key))) { "Missing REPL action: $key" }
            val group = DefaultActionGroup()
            listOf("Connect", "RunCell", "Interrupt", "SaveWorkspace").forEach { group.add(action(it)) }
            group.addSeparator()
            for (name in listOf("Run", "Workspace", "Session", "Tools")) {
                val menu = object : DefaultActionGroup(name, true) {
                    override fun displayTextInToolbar() = true
                }
                WorkbenchCatalog.commands.filter { it.group == name }.forEach { menu.add(action(it.key)) }
                group.add(menu)
            }
            return manager.createActionToolbar("SpringRepl.Workbench", group, true).apply { targetComponent = target }
        }
    }
}

class WorkbenchCommandAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    private fun key() = ActionManager.getInstance().getId(this)?.substringAfterLast('.').orEmpty()
    override fun update(e: AnActionEvent) {
        val command = WorkbenchCatalog.byKey(key()) ?: return
        val project = e.project
        val reason = if (project == null) "Open a project" else WorkbenchActions.get(project).reason(command)
        e.presentation.isEnabled = reason == null
        e.presentation.text = if (e.place.startsWith("SpringRepl")) command.label else "REPL: ${command.label}"
        e.presentation.description = reason ?: command.label
    }
    override fun actionPerformed(e: AnActionEvent) { e.project?.let { WorkbenchActions.get(it).perform(key()) } }
}
