package hu.baader.repl.mcp

import hu.baader.repl.protocol.*
import hu.baader.repl.trace.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class McpSqlTest {
    private fun sql(count: Int=5, dropped: Long=0) = SqlSnapshot(true,true,count.toLong(),count.toLong(),dropped,0,5,
        (1..count).map { SqlObservation(it.toLong(),1,1,1,1700000000000L+it,1_000_000,"SQL","executeQuery",
            "select value from entries where id=?", "pool", "example.Service", "run", "Service.java",12,"",0) })
    @Test fun sqlAndFindingsArePagedCorrelatedAndNeverUseTheEvaluator() {
        RecordingMcpHarness().use { h ->
            h.access.state=h.access.state.copy(recording=h.access.state.recording!!.copy(sql=sql()))
            val s=h.connect(); val id=BrowserFixture.id
            val status=h.ok(s,"repl_recording_status")
            assertEquals(5,status["sqlCount"].asInt)
            val first=h.ok(s,"repl_recording_sql","recording" to id,"limit" to 2)
            assertEquals(5,first["totalRows"].asInt);assertEquals(2,first["nextOffset"].asInt)
            assertEquals(1,first.getAsJsonArray("events")[0].asJsonObject["parent"].asInt)
            val detail=h.ok(s,"repl_recording_sql","recording" to id,"sql-id" to 1,"text-offset" to 7,"text-limit" to 5)
            assertEquals("value",detail.getAsJsonArray("events")[0].asJsonObject["sql"].asString)
            val findings=h.ok(s,"repl_recording_findings","recording" to id)
            val finding=findings.getAsJsonArray("findings").single().asJsonObject
            assertEquals("suspected-n-plus-one",finding["kind"].asString);assertEquals(5,finding["count"].asInt)
            assertEquals(12,finding["sourceLine"].asInt)
            val otherRoot=h.ok(s,"repl_recording_sql","recording" to id,"root" to 5)
            assertEquals(0,otherRoot.getAsJsonObject("statistics")["count"].asInt)
            assertEquals(0,otherRoot["totalRows"].asInt)
            h.access.state=h.access.state.copy(recording=h.access.state.recording!!.copy(sql=sql(6)))
            h.failed(s,"repl_recording_sql","recording" to id,"view" to first["view"].asString,"offset" to 2)
            assertTrue(h.runtimeOps.isEmpty());assertTrue(h.access.mutations.isEmpty())
        }
    }
    @Test fun pinnedSqlSurvivesNewRecordingAndPartialCaptureIsExplicit() {
        RecordingMcpHarness().use { h ->
            val before=h.access.state.recording!!.copy(sql=sql())
            h.access.state=RecordingAccessFixture.withRecord(before,true)
            val s=h.connect();val pin=h.ok(s,"repl_recording_pin","recording" to before.id,"call" to 1)
            val id=UUID.randomUUID().toString()
            val json=before.copy(sql=sql(1,1)).encode().replace(before.id,id)
            val after=CallRecording.decode(json)
            h.access.state=RecordingAccessFixture.withRecord(after,true)
            val diff=h.ok(s,"repl_recording_sql_compare","recording" to id,"reference" to pin["reference"].asString,"after" to 1)
            assertEquals(5,diff.getAsJsonObject("sqlBefore")["count"].asInt)
            assertEquals(1,diff.getAsJsonObject("sqlAfter")["count"].asInt)
            assertTrue(diff.getAsJsonObject("sqlDelta")["partial"].asBoolean)
            assertEquals(-4,diff.getAsJsonObject("sqlDelta")["count"].asInt)
            val other=h.connect();h.failed(other,"repl_recording_sql_compare","recording" to id,"reference" to pin["reference"].asString,"after" to 1)
        }
    }
    @Test fun sqlRecordingArchiveAndGroupedGraphPreserveActualParentage() {
        val record=CallRecording(BrowserFixture.id,BrowserFixture.calls(),sql=sql())
        record.validate();assertEquals(record,CallRecording.decode(record.encode()))
        val old=com.google.gson.JsonParser.parseString(record.encode()).asJsonObject.apply { addProperty("version",1);remove("sql") }
        assertFalse(CallRecording.decode(old.toString()).sql.enabled())
        val grouped=RecordingSql.nodes(record,true);assertEquals(1,grouped.size);assertEquals(1L,grouped.single().parent())
        val all=RecordingSql.nodes(record,false);assertEquals(5,all.size)
        assertEquals(1,CallGraphLayout.nodes(record.calls+grouped).single { it.call.id()==grouped.single().id() }.depth)
        assertTrue(RecordingSql.compare(record.sql,sql(1,1)).contains("Partial evidence"))
        val wrong=SqlSnapshot(true,true,1,1,0,0,5,listOf(SqlObservation(1,199,199,1,0,1,"SQL","executeQuery","select ?","pool","app.Service","run","Service.java",1,"",0)))
        assertThrows(IllegalArgumentException::class.java) { record.copy(sql=wrong).validate() }
    }
}
