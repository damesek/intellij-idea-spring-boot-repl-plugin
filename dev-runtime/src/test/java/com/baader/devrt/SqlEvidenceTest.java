package com.baader.devrt;

import hu.baader.repl.protocol.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SqlEvidenceTest {
    SqlObservation event(long id,long root,String sql) { return new SqlObservation(id,root,root,1,0,20,"SQL","executeQuery",sql,"pool","app.Service","load","Service.java",12,"",0); }
    @Test void normalizesLiteralsWithoutConflatingIdentifiersOrLeakingComments() {
        assertEquals("select \"CamelCase\", item2 from entries where secret=? and id=?",SqlText.normalize("SELECT \"CamelCase\", item2 /* password=hunter2 */ FROM entries WHERE secret='never '' leak' AND id=123 -- token=secret"));
        assertEquals("select ?, ?, ?",SqlText.normalize("select $$secret$$, $tag$more secret$tag$, 'escaped\\' secret'"));
        assertEquals("select ?",SqlText.normalize("select 'unterminated token=secret"));
        assertEquals("select ?",SqlText.normalize("select 0xAABBCC"));
        assertEquals("select item123 from `Table` where x=?",SqlText.normalize("select item123 from `Table` where x=1.0"));
    }
    @Test void nPlusOneIsPerRootAndSelectOnlyWithRoundTripAndPartialEvidence() {
        List<SqlObservation> events=new ArrayList<>();
        for(int i=0;i<10;i++)events.add(event(i+1,i<5?1:2,"select value from entries where id=?"));
        var sql=new SqlSnapshot(true,true,20,10,0,0,5,events);
        assertEquals(2,sql.findings().size());assertEquals(5,sql.maxRepetitions());assertEquals(10,sql.count());
        assertEquals(sql,SqlSnapshot.decode(sql.encode()));assertFalse(sql.partial());
        assertTrue(new SqlSnapshot(true,true,20,10,1,0,5,events).partial());
        assertTrue(new SqlSnapshot(true,false,20,10,0,0,5,events).partial());
        assertTrue(new SqlSnapshot(true,true,20,10,0,1,5,events).partial());
        assertTrue(new SqlSnapshot(true,true,20,10,0,0,6,events).findings().isEmpty());
        assertTrue(new SqlSnapshot(true,true,20,10,0,0,5,events.stream().map(e->event(e.id(),e.root(),"update entries set value=?")).toList()).findings().isEmpty());
        assertThrows(IllegalArgumentException.class,()->SqlSnapshot.decode(sql.encode().replace("sql-v1\t1\t","sql-v1\t2\t")));
    }
    @Test void portableCounterDoesNotConsumeResultsCountsFailuresAndDeduplicatesWrappers() throws Exception {
        var ds=new org.h2.jdbcx.JdbcDataSource();ds.setURL("jdbc:h2:mem:portable");
        var wrapped=CaseSqlCounter.wrap(CaseSqlCounter.wrap(ds));
        try(var connection=wrapped.getConnection();var stmt=connection.prepareStatement("select ?")) {
            stmt.setInt(1,42);
            try(var measurement=new CaseSqlCounter.Measurement()) {
                for(int i=0;i<5;i++)try(var rows=stmt.executeQuery()) { assertTrue(rows.next());assertEquals(42,rows.getInt(1));assertFalse(rows.next()); }
                measurement.assertLimits(5,5);assertThrows(AssertionError.class,()->measurement.assertLimits(4,5));assertThrows(AssertionError.class,()->measurement.assertLimits(5,4));
                try(var plain=connection.createStatement()) { assertThrows(java.sql.SQLException.class,()->plain.execute("select missing_table from nope")); }
                measurement.assertLimits(6,5);
            }
        }
    }
    @Test void sqlAssertionsFailClosedWithoutTheAgentBeforeExecutingCode() {
        assertThrows(IllegalStateException.class,SqlRecorder::scope);
        var def=new HashMap<>(Map.of("input","in","expected","out","code","1","max-sql-count","-1"));
        assertThrows(IllegalArgumentException.class,()->SnapshotCases.definition(def));
        def.put("max-sql-count","0");assertEquals("0",SnapshotCases.definition(def).get("max-sql-count"));
    }
}
