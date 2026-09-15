package com.baader.devrt;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CaseAssertionsTest {
    CaseAssertions.Report compare(String expected,String actual,String options){return CaseAssertions.compare(CaseJson.parse(expected),CaseJson.parse(actual),CaseJson.object(options));}
    @Test void ignoresVolatileFieldsAndComparesOnlySelectedSubtrees() {
        assertEquals("PASSED",compare("{\"id\":1,\"data\":{\"total\":2}}","{\"id\":2,\"data\":{\"total\":2.0}}","{\"include\":[\"/data\"]}").outcome());
        assertEquals("PASSED",compare("{\"id\":1,\"data\":2}","{\"id\":2,\"data\":2}","{\"ignore\":[\"/id\"]}").outcome());
        assertEquals("FAILED",compare("{}","{}","{\"include\":[\"/missing\"]}").outcome());
        assertEquals("FAILED",compare("{}","{\"other\":1}","{}").outcome());
    }
    @Test void wildcardAndEscapedPointerPathsDistinguishNullFromMissing() {
        assertEquals("PASSED",compare("{\"a/b\":{\"~\":1},\"items\":[{\"id\":1}]}","{\"a/b\":{\"~\":2},\"items\":[{\"id\":2}]}","{\"ignore\":[\"/a~1b/~0\",\"/items/*/id\"]}").outcome());
        assertEquals("FAILED",compare("{\"x\":null}","{}","{}").outcome());
        assertEquals("FAILED",compare("null","{}","{\"compareSnapshot\":false,\"checks\":[{\"path\":\"/x\",\"op\":\"isNull\"}]}").outcome());
    }
    @Test void unorderedToleranceUsesOneToOneMatchingRatherThanGreedyPairing() {
        assertEquals("PASSED",compare("[0.1,0.2]","[0.2,0.0]","{\"unordered\":[\"\"],\"numericTolerance\":{\"/*\":0.1}}").outcome());
        assertEquals("FAILED",compare("[1,1]","[1,2]","{\"unordered\":[\"\"]}").outcome());
        assertEquals("FAILED",compare("[1,2]","[2,1]","{}").outcome());
    }
    @Test void timeAndNumericTolerancesRespectBoundsAndDoNotCoerceText() {
        String options="{\"numericTolerance\":{\"/price\":0.01},\"timeToleranceMs\":{\"/at\":1000}}";
        assertEquals("PASSED",compare("{\"price\":10,\"at\":\"2025-01-01T00:00:00Z\"}","{\"price\":10.01,\"at\":\"2025-01-01T01:00:01+01:00\"}",options).outcome());
        assertEquals("FAILED",compare("{\"price\":10}","{\"price\":10.011}",options).outcome());
        assertEquals("FAILED",compare("{\"price\":10}","{\"price\":\"10\"}",options).outcome());
    }
    @Test void checksWorkWithoutAnExpectedSnapshotComparisonAndIgnoreDoesNotDisableExplicitChecks() {
        String actual="{\"items\":[{\"code\":\"A1\"},{\"code\":\"B2\"}],\"notes\":\"hello world\"}";
        String options="""
            {"compareSnapshot":false,"checks":[
              {"path":"/items","op":"hasSize","value":2},
              {"path":"/items/*/code","op":"matches","value":"[A-Z][0-9]","match":"all"},
              {"path":"/items/*/code","op":"equals","value":"B2","match":"any"},
              {"path":"/notes","op":"contains","value":"world"}]}
            """;
        assertEquals("PASSED",compare("null",actual,options).outcome());
        assertEquals("FAILED",compare("{\"id\":1}","{\"id\":2}","{\"ignore\":[\"/id\"],\"checks\":[{\"path\":\"/id\",\"op\":\"equals\",\"value\":1}]}").outcome());
        assertEquals("FAILED",compare("null","{\"items\":[]}",options).outcome());
    }
    @Test void validatesOptionsBeforeExecutionAndNeverPassesAnIncompleteComparison() {
        for(String invalid:List.of("{\"compareSnapshot\":false}","{\"typo\":true}","{\"ignore\":[\"$.id\"]}","{\"numericTolerance\":{\"/id\":-1}}","{\"checks\":[{\"path\":\"\",\"op\":\"hasSize\",\"value\":1.5}]}"))assertThrows(RuntimeException.class,()->CaseAssertions.validate(CaseJson.object(invalid)));
        assertThrows(IllegalArgumentException.class,()->CaseJson.object("{\"ignore\":[],\"ignore\":[\"\"]}"));
        assertThrows(IllegalArgumentException.class,()->CaseJson.object("{} {}"));
        var list=Collections.nCopies(201,1);
        assertEquals("INCONCLUSIVE",CaseAssertions.compare(list,list,Map.of("unordered",List.of(""))).outcome());
        Thread.currentThread().interrupt();try{assertEquals("INCONCLUSIVE",CaseAssertions.compare(1,1,Map.of()).outcome());}finally{Thread.interrupted();}
    }
    @Test void extremeNumericScaleIsInconclusiveAndLongTimeRangesDoNotOverflow() {
        assertEquals("INCONCLUSIVE",CaseAssertions.compare(new BigDecimal("1e10000000"),1,Map.of()).outcome());
        assertEquals("PASSED",compare("\"1000-01-01T00:00:00Z\"","\"3000-01-01T00:00:00Z\"","{\"timeToleranceMs\":{\"\":1e20}}").outcome());
    }
    @Test void regexBacktrackingIsBoundedWithoutBackgroundThreads() {
        long start=System.nanoTime();
        var report=CaseAssertions.compare(null,"a".repeat(20000)+"!",Map.of("compareSnapshot",false,"checks",List.of(Map.of("path","","op","matches","value","(a+)+"))));
        assertEquals("INCONCLUSIVE",report.outcome());assertTrue((System.nanoTime()-start)/1_000_000<2000);
    }
}
