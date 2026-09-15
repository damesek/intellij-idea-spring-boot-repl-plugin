package hu.baader.repl.trace

import hu.baader.repl.protocol.RecordedCall
import hu.baader.repl.protocol.ValueTree
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

internal object BrowserFixture {
    val id=UUID.randomUUID().toString()
    fun number(label: String,value: Int)=ValueTree.leaf(label,"NUMBER","int",value.toString())
    fun obj(label: String,vararg children: ValueTree)=ValueTree(label,"OBJECT","example.Order", "",children.toList())
    fun call(n: Long=1,parent: Long=0,root: Long=if(parent==0L) n else 1,thread: Long=1,
             start: Long=n*10,duration: Long=8_000_000,status: String="SUCCESS",
             input: ValueTree=obj("input",number("amount",41)),output: ValueTree=number("result",42),
             name: String="run")=RecordedCall(id,n,parent,root,"example.Service",name,"(I)I","amount",thread,
        if(thread==1L) "http-worker" else "background-worker",1_700_000_000_000+start,duration,status,
        if(status=="ERROR") "IllegalArgumentException: Invalid amount" else "42",input.encode(),
        if(status in setOf("ERROR","RUNNING","INCOMPLETE")) "" else output.encode(),
        if(status=="ERROR") obj("exception",ValueTree.leaf("message","STRING","String","Invalid amount")).encode() else "",-1,n)
    fun calls()=listOf(call(),call(2,1),call(3,2,status="ERROR",input=obj("input",ValueTree.leaf("customer","STRING","String","Árvíz <script>"))),
        call(4,1,duration=150_000_000),call(5,thread=2),call(6,5,5,2))
}

class CallBrowserTest {
    @Test fun searchFindsDecodedValuesAndRetainsAncestorsInsideCollapsedBranches() {
        val calls=BrowserFixture.calls()
        val nodes=CallGraphLayout.nodes(calls,collapsed=setOf(1,2),filter=CallFilter(query="ÁRVÍZ"))
        assertEquals(listOf(1L,2L,3L),nodes.map { it.call.id() })
        assertEquals(listOf(true,true,false),nodes.map { it.contextOnly })
        assertFalse(nodes.any { it.collapsed })
        assertTrue(CallGraphLayout.nodes(calls,filter=CallFilter(query="no such value")).isEmpty())
        assertTrue(CallPresentation(calls[2]).tooltip.contains("&lt;script&gt;"))
        assertFalse(CallPresentation(calls[2]).tooltip.contains("<script>"))
    }
    @Test fun foldedBranchesCountAllHiddenCallsAndErrorsWithoutMergingInvocations() {
        val nodes=CallGraphLayout.nodes(BrowserFixture.calls(),collapsed=setOf(1))
        assertEquals(listOf(1L,5L,6L),nodes.map { it.call.id() })
        assertEquals(3,nodes.first().hiddenCalls);assertEquals(1,nodes.first().hiddenErrors)
        assertEquals(6,CallGraphLayout.nodes(BrowserFixture.calls()).size)
    }
    @Test fun filtersComposeAndKeepOnlyRealCallerPaths() {
        val calls=BrowserFixture.calls()
        assertEquals(listOf(1L,4L),CallGraphLayout.nodes(calls,filter=CallFilter(minDurationMs=100.0)).map { it.call.id() })
        assertEquals(listOf(5L,6L),CallGraphLayout.nodes(calls,filter=CallFilter(thread=2)).map { it.call.id() })
        assertEquals(listOf(1L,2L,3L),CallGraphLayout.nodes(calls,filter=CallFilter(focus=2)).map { it.call.id() })
        assertEquals(listOf(true,false,false),CallGraphLayout.nodes(calls,filter=CallFilter(focus=2)).map { it.contextOnly })
        assertTrue(CallGraphLayout.nodes(calls,root=5,filter=CallFilter(errorsOnly=true)).isEmpty())
        val time=CallFilter(range=CallTimeRange(20.0,21.0))
        assertEquals(listOf(1L,2L,3L),CallGraphLayout.nodes(calls,filter=time).map { it.call.id() })
    }
    @Test fun timelineSeparatesThreadsRecursionAndOverlapsAndPreservesSubmillisecondDuration() {
        val calls=listOf(BrowserFixture.call(start=0,duration=1_000_000),
            BrowserFixture.call(2,1,start=0,duration=250_000),
            BrowserFixture.call(3,1,start=0,duration=100_000),
            BrowserFixture.call(4,thread=2,start=0,duration=500_000))
        val layout=CallTimelineLayout.layout(calls,1000)
        assertEquals(listOf(1L,2L),layout.lanes.map { it.thread })
        assertEquals(0.25,layout.bars[1].endMs,0.000001)
        layout.bars.forEachIndexed { i,a -> layout.bars.drop(i+1).forEach { b -> assertFalse(a.bounds.intersects(b.bounds)) } }
        assertEquals(0.5,layout.timeAt(layout.xAt(0.5)),0.002)
        assertEquals(0.0,layout.timeAt(-500),0.0)
        assertEquals(layout.endMs,layout.timeAt(99999),0.0)
    }
    @Test fun comparisonUsesFieldPathsPreservesDuplicateNamesAndReportsUnavailablePreviews() {
        val left=BrowserFixture.call(input=BrowserFixture.obj("input",BrowserFixture.number("amount",1),BrowserFixture.number("amount",2)))
        val right=BrowserFixture.call(2,input=BrowserFixture.obj("input",BrowserFixture.number("amount",1),BrowserFixture.number("amount",3)))
        val diff=CallComparison.compare(left,right)
        assertEquals(1,diff.changes.size);assertEquals("input['amount']#2",diff.changes.single().path)
        assertEquals("CHANGED",diff.changes.single().kind);assertFalse(diff.partial)
        val limited=BrowserFixture.call(3,input=ValueTree.leaf("input","LIMIT","","preview budget reached"))
        val partial=CallComparison.compare(limited,left)
        assertTrue(partial.partial);assertTrue(partial.changes.any { it.kind=="UNKNOWN" })
        assertTrue(CallComparison.compare(left,left).changes.isEmpty())
        val absent=left.header()
        val missing=CallComparison.compare(absent,absent)
        assertTrue(missing.partial);assertTrue(missing.changes.any { it.path=="input" && it.kind=="UNKNOWN" })
    }
    @Test fun partialArraysDoNotMisreportUncapturedElementsAsAddedOrRemoved() {
        val limited=ValueTree("result","ARRAY","int[]","",listOf(BrowserFixture.number("[0]",1),ValueTree.leaf("…","LIMIT","","more")))
        val full=ValueTree("result","ARRAY","int[]","",listOf(BrowserFixture.number("[0]",1),BrowserFixture.number("[1]",2),BrowserFixture.number("[2]",3)))
        val result=CallComparison.compare(BrowserFixture.call(output=limited),BrowserFixture.call(2,output=full))
        assertTrue(result.partial)
        assertFalse(result.changes.any { it.kind in setOf("ADDED","REMOVED") })
    }
    @Test fun presentationCacheRefreshesSameRevisionHeaderWhenValuesArrive() {
        val cache=CallPresentationCache();val call=BrowserFixture.call()
        val header=cache.update(listOf(call.header())).getValue(1)
        assertTrue(header.unavailable)
        val loaded=cache.update(listOf(call)).getValue(1)
        assertNotSame(header,loaded);assertFalse(loaded.unavailable)
        assertSame(loaded,cache.update(listOf(call)).getValue(1))
        assertTrue(cache.update(emptyList()).isEmpty())
    }
    @Test fun navigationUsesInvocationOrderAndStopsAtRecordingBoundaries() {
        val calls=BrowserFixture.calls()
        assertEquals(3L,CallNavigation.adjacent(calls,1,1,true)?.id())
        assertNull(CallNavigation.adjacent(calls,3,1,true))
        assertEquals(4L,CallNavigation.adjacent(calls,3,1)?.id())
        assertEquals(2L,CallNavigation.adjacent(calls,3,-1)?.id())
        assertNull(CallNavigation.adjacent(calls,1,-1))
        assertEquals(listOf(1L,2L,3L),CallNavigation.path(calls,3).map { it.id() })
        val outOfClockOrder=listOf(BrowserFixture.call(start=100),BrowserFixture.call(2,start=0))
        assertEquals(2L,CallNavigation.adjacent(outOfClockOrder,1,1)?.id())
    }
    @Test fun jsonStringResultsAreComparedStructurallyAndPartialBadgesRemainExplicit() {
        fun result(json: String)=ValueTree.leaf("result","STRING","String",json)
        val diff=CallComparison.compare(BrowserFixture.call(output=result("""{"customer":{"id":1}}""")),
            BrowserFixture.call(2,output=result("""{"customer":{"id":2}}""")))
        assertTrue(diff.changes.any { it.path=="result['customer']['id']" })
        assertEquals("INCOMPLETE",CallPresentation(BrowserFixture.call(status="INCOMPLETE")).badge)
        assertEquals("PARTIAL PREVIEW",CallPresentation(BrowserFixture.call(output=ValueTree.leaf("result","LIMIT","","more"))).badge)
    }
}
