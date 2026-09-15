package hu.baader.repl.workflow

import org.junit.Assert.*
import org.junit.Test

class CaseWorkflowTest {
    private data class Request(val op: String, val fields: Map<String,String>, val ok: (Map<String,String>) -> Unit, val error: (String) -> Unit)
    private class Scenario {
        val requests = mutableListOf<Request>()
        val results = mutableListOf<String>()
        var status = ""; var finished = 0
        val workflow = CaseWorkflow(CaseWorkflow.Transport { op, fields, ok, error -> requests += Request(op, fields, ok, error) },
            { status = it }, { name, _ -> results += name }, { finished++ })
    }
    @Test fun reloadMustSucceedBeforeCasesRunSequentially() {
        val s = Scenario(); s.workflow.start(listOf("first", "second"), "class Example {}")
        assertEquals(listOf("class-reload"), s.requests.map { it.op })
        s.requests[0].ok(mapOf("value" to "Reloaded"))
        assertEquals("first", s.requests[1].fields["name"])
        s.requests[1].ok(mapOf("outcome" to "PASSED"))
        assertEquals("second", s.requests[2].fields["name"])
        s.requests[2].ok(mapOf("outcome" to "FAILED"))
        assertEquals(listOf("first", "second"), s.results); assertFalse(s.workflow.busy); assertEquals(1, s.finished)
    }
    @Test fun failedReloadNeverRunsTestsOrPretendsTheyPassed() {
        val s = Scenario(); s.workflow.start(listOf("first"), "broken code")
        s.requests[0].error("Compiler error")
        assertEquals(1, s.requests.size); assertTrue(s.results.isEmpty()); assertEquals("Compiler error", s.status); assertFalse(s.workflow.busy)
    }
    @Test fun cancellationNeverStartsTheNextCase() {
        val s = Scenario(); s.workflow.start(listOf("first", "second")); s.workflow.cancel()
        assertEquals("interrupt", s.requests[1].op)
        s.requests[0].ok(mapOf("outcome" to "CANCELLED"))
        assertEquals(2, s.requests.size); assertFalse(s.workflow.busy)
    }
    @Test fun staleRepliesAfterDisconnectCannotReplayOrFinishANewerRun() {
        val s = Scenario(); s.workflow.start(listOf("old"), "class Old {}"); val old = s.requests[0]
        s.workflow.invalidate(); s.workflow.start(listOf("new")); old.ok(emptyMap())
        assertEquals(2, s.requests.size); assertTrue(s.results.isEmpty()); assertTrue(s.workflow.busy)
        s.requests[1].ok(mapOf("outcome" to "PASSED")); assertEquals(listOf("new"), s.results)
    }
    @Test fun timeoutStopsTheWorkflowWithoutRetryingAnUncertainCall() {
        val s = Scenario(); s.workflow.start(listOf("first", "second")); s.requests[0].error("Timed out")
        assertEquals(1, s.requests.size); assertEquals("Timed out", s.status); assertFalse(s.workflow.busy)
    }
}
