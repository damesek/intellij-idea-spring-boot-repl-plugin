package hu.baader.repl.trace

import hu.baader.repl.protocol.RecordedCall
import hu.baader.repl.protocol.ValueTree
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class CallRecordingTest {
    private val id=UUID.randomUUID().toString()
    private fun call(number: Long, parent: Long=0, root: Long=number, thread: Long=1, status: String="SUCCESS") = RecordedCall(id,number,parent,root,"example.Service","run","(I)I","amount",thread,"request-thread",1700000000000,1000,status,"42",
        ValueTree.leaf("amount","NUMBER","int","41").encode(),ValueTree.leaf("result","NUMBER","int","42").encode(),"",17,number)
    @Test fun recordingRoundTripsFrozenValuesAndSourceWithoutApplicationCode() {
        val text="package example; class Service { int run(int amount) { return amount+1; } }"
        val source=CapturedSource("example.Service","Service.java",text,CapturedSource.hash(text),mapOf("run(I)I" to text.indexOf("int run")))
        val recording=CallRecording(id,listOf(call(1),call(2,1,1)),listOf(source),12)
        assertEquals(recording,CallRecording.decode(recording.encode()))
        assertFalse(recording.encode().contains("/Users/"))
    }
    @Test fun savedRunningCallIsExplicitlyIncomplete() {
        val restored=CallRecording.decode(CallRecording(id,listOf(call(1,status="RUNNING"))).encode())
        assertEquals("INCOMPLETE",restored.calls.single().status());assertEquals("",restored.calls.single().output())
        assertEquals(-1L,restored.calls.single().event())
    }
    @Test fun duplicateMissingOrCrossThreadParentsAreRejected() {
        for (calls in listOf(listOf(call(1),call(1)),listOf(call(3,2,1)),listOf(call(1),call(2,1,1,thread=2)),listOf(call(1),call(2,1,2))))
            assertThrows(IllegalArgumentException::class.java) { CallRecording(id,calls).validate() }
    }
    @Test fun malformedValuesSourcesAndDescriptorsCannotEnterAnArchive() {
        assertThrows(IllegalArgumentException::class.java) { CallRecording.decode("{}") }
        val source=CapturedSource("example.Service","../../escape.java","class Service {}","bad",emptyMap())
        assertThrows(IllegalArgumentException::class.java) { CallRecording(id,listOf(call(1)),listOf(source)).validate() }
        val good=CallRecording(id,listOf(call(1))).encode()
        assertThrows(IllegalArgumentException::class.java) { CallRecording.decode(good.replace("\"version\":1","\"version\":99")) }
        assertThrows(IllegalArgumentException::class.java) { ValueTree.decode("not a captured value") }
    }
    @Test fun jvmDescriptorsDistinguishOverloadsArraysAndNestedTypes() {
        assertEquals(listOf("int","java.lang.String[]","example.Outer.Inner[][]"),MethodDescriptor.parameters("(I[Ljava/lang/String;[[Lexample/Outer\$Inner;)V"))
        assertEquals(emptyList<String>(),MethodDescriptor.parameters("()I"))
        for (bad in listOf("","(V)V","([V)V","()Vgarbage","(Ljava/lang/String)V","(I","(I))V"))
            assertThrows(bad,IllegalArgumentException::class.java) { MethodDescriptor.parameters(bad) }
    }
    @Test fun graphKeepsRepeatedCallsDistinctAndOrdersEachTreeByInvocation() {
        val calls=listOf(call(1),call(2,1,1),call(3,2,1),call(4,1,1),call(5,thread=2))
        val nodes=CallGraphLayout.nodes(calls.reversed())
        assertEquals(listOf(1L,2L,3L,4L,5L),nodes.map { it.call.id() })
        assertEquals(listOf(0,1,2,1,0),nodes.map { it.depth })
        assertEquals(4,CallGraphLayout.nodes(calls,1).size)
        nodes.forEachIndexed { i,node -> nodes.drop(i+1).forEach { assertFalse(node.bounds.intersects(it.bounds)) } }
    }
}
