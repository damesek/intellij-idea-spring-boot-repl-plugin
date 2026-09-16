package hu.baader.repl.mcp

import hu.baader.repl.protocol.*
import hu.baader.repl.trace.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class McpHibernateTest {
    private fun record(count:Int=5,dropped:Long=0):CallRecording {
        val orm=(1L..count.toLong()).map { HibernateObservation(it,0,2,1,1,1700000000000L+it,1000000,"session-1","LAZY_ENTITY","example.Customer","example.Order.customer","","example.Service","run","Service.java",3,"",it==1L) }
        val sql=orm.map { SqlObservation(it.id(),2,1,1,it.startedAt(),500000,"SQL","executeQuery","select name from customer where id=?","pool","example.Service","run","Service.java",3,"",0,it.id()) }
        return CallRecording(BrowserFixture.id,BrowserFixture.calls(),sql=SqlSnapshot(true,true,10,count.toLong(),0,0,5,sql),hibernate=HibernateSnapshot(true,true,"6.6.29.Final",10,dropped,0,orm))
    }
    @Test fun pagedMetadataAndFindingsCorrelateWithoutExecutingApplicationCode() {
        RecordingMcpHarness().use { h->
            h.access.state=RecordingAccessFixture.withRecord(record(),true);val session=h.connect()
            val page=h.ok(session,"repl_recording_hibernate","recording" to BrowserFixture.id,"limit" to 2)
            assertEquals(5,page["totalRows"].asInt);assertEquals(2,page["nextOffset"].asInt)
            assertEquals(1,page.getAsJsonArray("events")[0].asJsonObject.getAsJsonArray("sqlIds").single().asInt)
            assertEquals(5,page.getAsJsonObject("statistics")["lazyLoads"].asInt)
            val findings=h.ok(session,"repl_recording_hibernate_findings","recording" to BrowserFixture.id).getAsJsonArray("findings")
            assertEquals(setOf("suspected-n-plus-one","lazy-during-response"),findings.map{it.asJsonObject["kind"].asString}.toSet())
            assertEquals(0,h.ok(session,"repl_recording_hibernate","recording" to BrowserFixture.id,"root" to 5)["totalRows"].asInt)
            assertTrue(h.call(session,"repl_recording_hibernate","recording" to BrowserFixture.id,"kind" to "INVENTED").body!!.has("error"))
            h.failed(session,"repl_recording_hibernate","recording" to BrowserFixture.id,"root" to 5,"event-id" to 1)
            h.access.state=RecordingAccessFixture.withRecord(record(4,1),true)
            h.failed(session,"repl_recording_hibernate","recording" to BrowserFixture.id,"view" to page["view"].asString,"offset" to 2)
            assertTrue(h.runtimeOps.isEmpty());assertTrue(h.access.mutations.isEmpty())
        }
    }
    @Test fun pinnedOrmComparisonSurvivesRecordingReplacementAndIsSessionPrivate() {
        RecordingMcpHarness().use { h->
            h.access.state=RecordingAccessFixture.withRecord(record(),true);val session=h.connect()
            val pin=h.ok(session,"repl_recording_pin","recording" to BrowserFixture.id,"call" to 1)
            val id=UUID.randomUUID().toString();h.access.state=RecordingAccessFixture.withRecord(CallRecording.decode(record(1,1).encode().replace(BrowserFixture.id,id)),true)
            val diff=h.ok(session,"repl_recording_hibernate_compare","recording" to id,"reference" to pin["reference"].asString,"after" to 1)
            assertEquals(-4,diff.getAsJsonObject("hibernateDelta")["lazyLoads"].asInt)
            assertTrue(diff["hibernateComparisonPartial"].asBoolean)
            h.failed(h.connect(),"repl_recording_hibernate_compare","recording" to id,"reference" to pin["reference"].asString,"after" to 1)
        }
    }
    @Test fun archiveGraphAndLegacyVersionsPreserveJavaOrmSqlParentage() {
        val record=record();record.validate();assertEquals(record,CallRecording.decode(record.encode()))
        val orm=RecordingHibernate.nodes(record);val sql=RecordingSql.nodes(record,false)
        val layout=CallGraphLayout.nodes(record.calls+orm+sql)
        assertEquals(2,layout.single{it.call.id()==RecordingHibernate.BASE+1}.depth)
        assertEquals(3,layout.single{it.call.id()==RecordingSql.BASE+1}.depth)
        assertEquals(RecordingHibernate.BASE+1,sql.first().parent())
        val old=com.google.gson.JsonParser.parseString(record.encode()).asJsonObject.apply{addProperty("version",2);remove("hibernate");remove("sql")}
        assertFalse(CallRecording.decode(old.toString()).hibernate.enabled())
        val bad=record.hibernate.events().first().let{HibernateObservation(it.id(),0,199,1,1,it.startedAt(),1,it.session(),it.kind(),it.entity(),it.role(),"","","","",0,"",false)}
        assertThrows(IllegalArgumentException::class.java){record.copy(hibernate=HibernateSnapshot(true,true,"6.6",1,0,0,listOf(bad))).validate()}
    }
}
