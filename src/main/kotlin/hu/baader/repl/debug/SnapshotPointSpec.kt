package hu.baader.repl.debug

import com.google.gson.Gson
import hu.baader.repl.ui.SnapshotDestination
import java.util.Base64
import java.util.UUID

/** Persisted inside the native breakpoint's log expression; it survives IDE restarts and line moves. */
data class SnapshotPointSpec(val name: String, val expression: String, val type: String = "", val count: Int = 1, val id: String = UUID.randomUUID().toString()) {
    fun validate() {
        require(SnapshotDestination.error(name, type) == null) { SnapshotDestination.error(name, type).orEmpty() }
        require(expression.isNotBlank() && expression.length <= 4096) { "Enter a Java value expression (up to 4096 characters)." }
        require(count in 1..100) { "Capture count must be 1–100." }
        require(id.matches(Regex("[a-f0-9-]{36}"))) { "Invalid snapshot point identity." }
    }
    fun condition(): String {
        validate()
        return "((java.lang.Boolean)java.lang.Class.forName(\"com.baader.devrt.SnapshotBreakpoint\").getMethod(\"ready\", java.lang.String.class, java.lang.Integer.TYPE).invoke(null, new java.lang.Object[]{${literal(id)}, java.lang.Integer.valueOf($count)})).booleanValue()"
    }
    fun logExpression(): String {
        validate()
        val metadata = Base64.getUrlEncoder().withoutPadding().encodeToString(Gson().toJson(this).toByteArray(Charsets.UTF_8))
        // Reflection uses only JDK types known to IDEA; the app need not declare an agent dependency in its build.
        return "$PREFIX$metadata*/ java.lang.Class.forName(\"com.baader.devrt.SnapshotBreakpoint\").getMethod(\"capture\", java.lang.String.class, java.lang.String.class, java.lang.String.class, java.lang.Integer.TYPE, java.lang.Object.class).invoke(null, new java.lang.Object[]{${literal(id)}, ${literal(name)}, ${literal(type)}, java.lang.Integer.valueOf($count), ($expression)})"
    }
    companion object {
        const val PREFIX = "/*sb-repl-snapshot-v1:"
        private fun literal(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
        fun read(log: String?): SnapshotPointSpec? = runCatching {
            require(log != null && log.length <= 20000 && log.startsWith(PREFIX))
            val end = log.indexOf("*/", PREFIX.length); require(end > PREFIX.length)
            val json = String(Base64.getUrlDecoder().decode(log.substring(PREFIX.length, end)), Charsets.UTF_8)
            Gson().fromJson(json, SnapshotPointSpec::class.java).also { it.validate(); require(it.logExpression() == log) }
        }.getOrNull()
    }
}
