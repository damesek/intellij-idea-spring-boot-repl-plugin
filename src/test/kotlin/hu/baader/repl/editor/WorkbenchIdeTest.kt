package hu.baader.repl.editor

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import hu.baader.repl.actions.WorkbenchCatalog
import hu.baader.repl.debug.SnapshotBreakpointType
import hu.baader.repl.debug.SnapshotPointSpec
import hu.baader.repl.nrepl.NreplService
import hu.baader.repl.workspace.WorkspaceStore

class WorkbenchIdeTest : BasePlatformTestCase() {
    fun testCompleteToolWindowLoadsWithCompactActionsAndCanBePainted() {
        val manager = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
        val window = manager.getToolWindow("Spring Boot REPL") ?: manager.registerToolWindow("Spring Boot REPL", true, com.intellij.openapi.wm.ToolWindowAnchor.BOTTOM)
        val before = window.contentManager.contents.toSet()
        com.intellij.openapi.util.IconLoader.activate()
        try {
            hu.baader.repl.ui.JavaReplToolWindowFactory().createToolWindowContent(project, window)
            val component = window.contentManager.contents.first { it !in before }.component
            val commands = hu.baader.repl.actions.WorkbenchActions.get(project)
            assertNull(commands.reason(WorkbenchCatalog.byKey("SaveWorkspace")!!))
            assertNotNull(commands.reason(WorkbenchCatalog.byKey("RunCell")!!))
            fun layout(root: java.awt.Container) { root.doLayout(); root.components.filterIsInstance<java.awt.Container>().forEach(::layout) }
            fun components(root: java.awt.Container): List<java.awt.Component> = root.components.flatMap {
                listOf(it) + if (it is java.awt.Container) components(it) else emptyList()
            }
            val toolbar = components(component).filterIsInstance<com.intellij.openapi.actionSystem.impl.ActionToolbarImpl>().first()
            toolbar.updateActionsAsync()
            com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertTrue("The workbench toolbar must display its actions", toolbar.hasVisibleActions())
            assertEquals(4, toolbar.components.filterIsInstance<com.intellij.openapi.actionSystem.impl.ActionButtonWithText>().size)
            toolbar.alphaContext.animator.setVisibleImmediately(true)
            for (width in listOf(1100, 700)) {
                component.setSize(width, 800); repeat(8) { layout(component) }
                com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
                repeat(3) { layout(component) }
                EditorFactory.getInstance().allEditors.filter { it.project == project }.forEach { editor ->
                    editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength).forEach { it.update() }
                }
                System.getProperty("sb.repl.uiRenderDir")?.let { directory ->
                    java.nio.file.Files.writeString(java.nio.file.Path.of(directory, "Workbench-$width-layout.txt"), components(component).joinToString("\n") { "${it.javaClass.name}: ${it.bounds}, visible=${it.isVisible}" })
                }
                val image = java.awt.image.BufferedImage(width, 800, java.awt.image.BufferedImage.TYPE_INT_RGB)
                val graphics = image.createGraphics()
                try { component.printAll(graphics) } finally { graphics.dispose() }
                System.getProperty("sb.repl.uiRenderDir")?.let { directory -> javax.imageio.ImageIO.write(image, "png", java.nio.file.Path.of(directory, "Workbench-$width.png").toFile()) }
            }
        } finally { manager.unregisterToolWindow("Spring Boot REPL"); com.intellij.openapi.util.IconLoader.deactivate() }
    }
    fun testActionsAndSnapshotBreakpointLoadFromPluginDescriptor() {
        for (command in WorkbenchCatalog.commands) assertNotNull("Missing Find Action entry: ${command.key}", ActionManager.getInstance().getAction(WorkbenchCatalog.id(command.key)))
        val type = XDebuggerUtil.getInstance().findBreakpointType(SnapshotBreakpointType::class.java)
        assertNotNull(type)
        val file = myFixture.addFileToProject("Example.java", "class Example {\n void run(String input) {\n  System.out.println(input);\n }\n}")
        assertTrue(type.canPutAt(file.virtualFile, 2, project))
        val manager = XDebuggerManager.getInstance(project).breakpointManager
        WriteCommandAction.runWriteCommandAction(project) {
            val point = manager.addLineBreakpoint(type, file.virtualFile.url, 2, type.createBreakpointProperties(file.virtualFile, 2))
            try {
                point.suspendPolicy = type.defaultSuspendPolicy
                val spec = SnapshotPointSpec("input", "input")
                val util = XDebuggerUtil.getInstance()
                point.logExpressionObject = util.createExpression(spec.logExpression(), null, null, com.intellij.xdebugger.evaluation.EvaluationMode.EXPRESSION)
                assertEquals(SuspendPolicy.NONE, point.suspendPolicy)
                assertEquals(spec, SnapshotPointSpec.read(point.logExpressionObject?.expression))
                assertTrue(type.getDisplayText(point).contains("Snapshot input"))
                assertEquals(1, type.getAdditionalPopupMenuActions(point, null).size)
            } finally { manager.removeBreakpoint(point) }
        }
    }
    fun testRealEditorCreatesAndDisposesCellInlaysWithoutExecutingAnything() {
        val editor = JavaReplEditorProvider.createEnhancedEditor(project)
        val owner = Disposer.newDisposable()
        try {
            val store = WorkspaceStore.getInstance(project)
            store.document.notebook.sync("// %% Input\nint amount = 1;\n// %% Result\namount + 1")
            val service = NreplService.getInstance(project)
            val controller = NotebookController(project, editor, service, store, {}, {})
            Disposer.register(owner, controller)
            val overlays = NotebookInlays(project, editor, controller, service, {}, {}, {}, {})
            Disposer.register(owner, overlays)
            val inlays = editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength)
            assertEquals(2, inlays.size)
            assertEquals(0L, controller.state.counter)
            assertTrue(inlays.all { it.renderer is InlineResultRenderer })
            assertTrue(editor.markupModel.allHighlighters.any { it.gutterIconRenderer != null })
            Disposer.dispose(owner)
            assertTrue(editor.inlayModel.getBlockElementsInRange(0, editor.document.textLength).isEmpty())
        } finally { if (!Disposer.isDisposed(owner)) Disposer.dispose(owner); EditorFactory.getInstance().releaseEditor(editor) }
    }
}
