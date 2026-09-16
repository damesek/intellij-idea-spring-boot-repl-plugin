package com.baader.devrt;

import hu.baader.repl.protocol.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HibernateEvidenceTest {
    private HibernateObservation orm(long id,long parent,long root,boolean response) {
        return new HibernateObservation(id,parent,root,root,1,0,10,"session","LAZY_ENTITY","app.Customer","app.Order.customer","","app.Service","load","Service.java",12,"",response);
    }
    private SqlObservation sql(long id,long parent,long root) {
        return new SqlObservation(id,root,root,1,0,10,"SQL","executeQuery","select name from customer where id=?","pool","app.Service","load","Service.java",12,"",0,parent);
    }
    @Test void ancestryAndWireFormatsRetainCorrelationAndRejectInvalidParents() {
        var events=List.of(orm(1,0,1,false),orm(2,1,1,true));
        var evidence=new HibernateSnapshot(true,true,"6.6.29.Final",4,0,0,events);
        assertEquals(evidence,HibernateSnapshot.decode(evidence.encode()));
        var sql=new SqlSnapshot(true,true,1,1,0,0,5,List.of(sql(1,2,1)));
        assertEquals(1,evidence.sqlFor(1,sql).size());assertEquals(1,evidence.sqlFor(2,sql).size());
        assertEquals(sql,SqlSnapshot.decode(sql.encode()));assertTrue(sql.encode().contains("sql-v2"));
        var legacy=sql(1,0,1);assertTrue(legacy.encode().startsWith("sql-v1"));assertEquals(legacy,SqlObservation.decode(legacy.encode()));
        assertThrows(IllegalArgumentException.class,()->orm(1,1,1,false));
        assertThrows(IllegalArgumentException.class,()->new HibernateSnapshot(true,true,"6.6",1,0,0,List.of(events.get(0),orm(2,1,2,false))));
        assertThrows(IllegalArgumentException.class,()->new HibernateSnapshot(true,true,"6.6\nbad",1,0,0,events));
    }
    @Test void findingsNeedRepeatedSelectsInTheSameRootAndIgnoreCacheOnlyLoads() {
        List<HibernateObservation> events=new ArrayList<>();List<SqlObservation> queries=new ArrayList<>();
        for(int i=1;i<=12;i++){events.add(orm(i,0,i<=6?1:2,i==1));if(i%6!=0)queries.add(sql(i,i,i<=6?1:2));}
        var orm=new HibernateSnapshot(true,true,"6.6.29.Final",24,0,0,events);
        var sql=new SqlSnapshot(true,true,10,10,0,0,5,queries);
        var findings=orm.findings(sql,5);
        assertEquals(2,findings.stream().filter(f->f.kind().equals("suspected-n-plus-one")).count());
        assertTrue(findings.stream().filter(f->f.kind().equals("suspected-n-plus-one")).allMatch(f->f.events().size()==5&&f.sqlIds().size()==5));
        assertEquals(1,findings.stream().filter(f->f.kind().equals("lazy-during-response")).count());
        assertEquals(0,orm.findings(sql,6).stream().filter(f->f.kind().equals("suspected-n-plus-one")).count());
        assertTrue(orm.subtree(Set.of(1L)).events().stream().allMatch(e->e.root()==1));
        assertTrue(new HibernateSnapshot(true,true,"6.6",1,1,0,events).partial());
        assertTrue(new HibernateSnapshot(true,true,"6.6",1,0,1,events).partial());
        assertTrue(new HibernateSnapshot(true,false,"7.0",1,0,0,events).partial());
    }
    @Test void hibernateAssertionsRequireTheAgentAndValidateEveryBound() {
        assertThrows(IllegalStateException.class,CaseHibernateProbe::new);
        for(String field:SnapshotCases.HIBERNATE_FIELDS) {
            var definition=new HashMap<>(Map.of("input","in","expected","out","code","1",field,"-1"));
            assertThrows(IllegalArgumentException.class,()->SnapshotCases.definition(definition));
            definition.put(field,"0");assertTrue(SnapshotCases.hibernateAssertions(SnapshotCases.definition(definition)));
        }
    }
}
