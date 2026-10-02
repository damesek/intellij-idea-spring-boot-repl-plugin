package hu.baader.repl.debug

import com.intellij.debugger.engine.DebugProcessImpl
import com.intellij.debugger.engine.JavaBreakpointHandler
import com.intellij.debugger.engine.JavaBreakpointHandlerFactory
import com.intellij.debugger.ui.breakpoints.LineBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpoint

/** The Java debugger matches breakpoint types exactly, including custom Java logpoints. */
class SnapshotBreakpointHandlerFactory : JavaBreakpointHandlerFactory {
    override fun createHandler(process: DebugProcessImpl): JavaBreakpointHandler = SnapshotBreakpointHandler(process)
}

internal class SnapshotBreakpointHandler(process: DebugProcessImpl) :
    JavaBreakpointHandler(SnapshotBreakpointType::class.java, process) {
    override fun createJavaBreakpoint(breakpoint: XBreakpoint<*>): LineBreakpoint<*>? =
        LineBreakpoint.create(myProcess.project, breakpoint)
}
