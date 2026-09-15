package hu.baader.repl.debug

import java.util.UUID
import javax.lang.model.SourceVersion

/** The debugger evaluates the capture; nREPL claims it only after the target resumes. */
data class DebugTransfer(val session: String, val pid: Long, val variable: String, val ticket: String = UUID.randomUUID().toString()) {
    init {
        require(session.matches(Regex("[a-f0-9-]{36}")) && ticket.matches(Regex("[a-f0-9-]{36}")) && pid > 0) { "Invalid debugger target" }
        require(SourceVersion.isIdentifier(variable) && !SourceVersion.isKeyword(variable) && variable !in setOf("ctx", "last1", "last2", "last3", "lastError")) { "Choose a valid new REPL variable name" }
    }
    fun expression(source: String): String {
        require(source.isNotBlank() && source.length <= 100_000) { "Enter a Java expression (up to 100000 characters)" }
        return "(com.baader.devrt.DebugBridge.checkTarget(\"$session\", ${pid}L) ? com.baader.devrt.DebugBridge.capture(\"$session\", \"$ticket\", ${pid}L, ($source)) : \"\")"
    }
    fun matches(target: Pair<String, Long>?) = target?.first == session && target.second == pid
}
