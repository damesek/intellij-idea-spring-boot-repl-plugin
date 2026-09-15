package hu.baader.repl.editor

import org.junit.Assert.*
import org.junit.Test

class AnalysisRequestTest {
    @Test fun diagnosticsBelongToOneDocumentAndSessionGeneration() {
        val request=AnalysisRequest("int value = missing;",12,3)
        assertTrue(request.matches(12,3)); assertFalse(request.matches(13,3)); assertFalse(request.matches(12,4))
        val issue=request.issues("ERROR\t12\t19\tcannot find symbol: missing").single()
        assertEquals("missing",request.source.substring(issue.start,issue.end))
    }
    @Test fun malformedAndOutOfRangeDiagnosticsCannotHighlightUnrelatedCode() {
        val request=AnalysisRequest("abc",1,1)
        assertTrue(request.issues("ERROR\t-1\t2\terror\nERROR\t1\t8\terror\nERROR\t2\t1\terror\nBAD\t0\t1\terror\nbroken").isEmpty())
        assertTrue(AnalysisRequest("",1,1).issues("ERROR\t0\t0\terror").isEmpty())
    }
    @Test fun endOfFileDiagnosticsStayVisibleAndMessagesAreBounded() {
        val issue=AnalysisRequest("if (true) {",1,1).issues("ERROR\t11\t11\t"+"x".repeat(3000)).single()
        assertEquals(10,issue.start); assertEquals(11,issue.end); assertEquals(1000,issue.message.length)
    }
}
