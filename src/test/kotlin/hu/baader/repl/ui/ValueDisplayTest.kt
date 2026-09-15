package hu.baader.repl.ui

import hu.baader.repl.protocol.ValueTree
import org.junit.Assert.*
import org.junit.Test

class ValueDisplayTest {
    private fun message(text: String, limited: Boolean = false) = mapOf("view-data" to ValueTree.leaf("result", "STRING", "java.lang.String", text).encode(), "view-limited" to limited.toString())
    @Test fun validJsonIsIndentedAndStructuredWithoutLosingUnicodeNullsOrNumberPrecision() {
        val source="{\"id\":123456789012345678901234567890,\"name\":\"Árvíz 東京\",\"values\":[null,true,1.200e-30]}"
        val display=ValueDisplay.fromMessage(message(source))
        assertTrue(display.json); assertEquals(source,display.raw)
        assertTrue(display.formatted.contains("\n  \"name\": \"Árvíz 東京\"")); assertTrue(display.formatted.contains("1.200e-30"))
        assertEquals("123456789012345678901234567890",display.root.children()[0].text())
        assertEquals("NULL",display.root.children()[2].children()[0].kind())
        assertEquals(display.root,ValueDisplay.parseJson(display.formatted))
    }
    @Test fun duplicateKeysAreVisibleInsteadOfSilentlyOverwritingValues() {
        val display=ValueDisplay.fromMessage(message("{\"same\":1,\"same\":2}"))
        assertEquals(listOf("1","2"),display.root.children().map { it.text() })
        assertEquals(2,Regex("\"same\"").findAll(display.formatted).count())
    }
    @Test fun invalidOrNonJsonStringsAreNotRewrittenOrAcceptedLeniently() {
        for (source in listOf("hello\nworld", "{'name':1}", "{name:1}", "{\"a\":1,}", "[1,]", "[NaN]", "{} trailing", "{/*comment*/\"a\":1}")) {
            val display=ValueDisplay.fromMessage(message(source))
            assertFalse(source,display.json); assertEquals(source,display.formatted); assertEquals(source,display.raw)
        }
    }
    @Test fun partialOversizedAndDeepJsonDoNotCreateUnboundedTrees() {
        assertFalse(ValueDisplay.fromMessage(message("{\"a\":1}",true)).json)
        val deep="[".repeat(40)+"0"+"]".repeat(40)
        assertFalse(ValueDisplay.fromMessage(message(deep)).json)
        val wide="["+List(5100) { "0" }.joinToString(",")+"]"
        assertFalse(ValueDisplay.fromMessage(message(wide)).json)
        assertFalse(ValueDisplay.fromMessage(message("[\""+"x".repeat(140000)+"\"]")).json)
    }
    @Test fun javaObjectsShowReadableFieldsReferencesAndAccessErrors() {
        val node=ValueTree("result","OBJECT","example.Order","",listOf(
            ValueTree.leaf("id","NUMBER","int","42"),ValueTree.leaf("self","REFERENCE","example.Order","result"),
            ValueTree.leaf("hidden","ERROR","String","Field is not open to the agent")))
        val display=ValueDisplay.fromMessage(mapOf("view-data" to node.encode()),"example.Order@1234")
        assertFalse(display.json); assertEquals("example.Order@1234",display.raw)
        assertTrue(display.formatted.contains("\"id\": 42"));assertTrue(display.formatted.contains("↩ result"))
        assertTrue(display.formatted.contains("Field is not open"))
    }
    @Test fun malformedTransportKeepsTheTextResultVisible() {
        val display=ValueDisplay.fromMessage(mapOf("view-data" to "broken"),"useful output")
        assertEquals("useful output",display.formatted)
    }
}
