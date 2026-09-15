package com.baader.devrt;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotDiffTest {
    @TempDir Path home;
    String oldHome;
    @BeforeEach void setup() { oldHome=System.getProperty("user.home");System.setProperty("user.home",home.toString());SpringContextHolder.set(null);SnapshotManager.clearLive(); }
    @AfterEach void cleanup() { SnapshotManager.clearLive();System.setProperty("user.home",oldHome); }
    @Test void nestedFieldsArraysMissingAndNullAreDistinguished() {
        SnapshotManager.importJson("before","{\"a/b~c\":{\"value\":1},\"items\":[1,2],\"removed\":null}");
        SnapshotManager.importJson("after","{\"a/b~c\":{\"value\":2},\"items\":[1,3,4],\"added\":null}");
        var result=SnapshotManager.diff("before","after",0,100);String rows=result.get("value").toString();
        assertTrue(rows.contains("/a~1b~0c/value\tCHANGED\t1\t2"),rows);
        assertTrue(rows.contains("/items/1\tCHANGED\t2\t3"),rows);
        assertTrue(rows.contains("/items/2\tADDED\t<missing>\t4"),rows);
        assertTrue(rows.contains("/removed\tREMOVED\tnull\t<missing>"),rows);
        assertTrue(rows.contains("/added\tADDED\t<missing>\tnull"),rows);
        assertEquals(5,result.get("changes-found")); assertEquals(false,result.get("scan-limited"));
    }
    @Test void paginationHasNoDuplicatesAndPreviewsAreBounded() {
        SnapshotManager.save("before",Collections.nCopies(230,"x".repeat(10000)));
        SnapshotManager.save("after",Collections.nCopies(230,"y\t\n".repeat(10000)));
        Set<String> paths=new HashSet<>();
        for(int offset:new int[]{0,100,200}) {
            var result=SnapshotManager.diff("before","after",offset,100);
            for(String row:result.get("value").toString().split("\n")) {
                var fields=row.split("\t");assertEquals(4,fields.length);assertTrue(paths.add(fields[0]));assertTrue(row.length()<2000);
            }
            assertEquals(offset<200,result.get("has-more"));
        }
        assertEquals(230,paths.size());
    }
    @Test void comparesPayloadNotEnvelopeOrderMetadataOrNumericFormatting() {
        SnapshotManager.importJson("before","{\"b\":1.00000000000000000001,\"a\":1}");
        SnapshotManager.importJson("after","{\"a\":1.0,\"b\":1.00000000000000000001}");
        assertEquals("",SnapshotManager.diff("before","after",0,100).get("value"));
        // Save exact decimals directly; plain inline imports use the application's normal untyped mapping.
        SnapshotManager.save("preciseA",new java.math.BigDecimal("1.00000000000000000001"));
        SnapshotManager.save("preciseB",new java.math.BigDecimal("1.00000000000000000002"));
        assertEquals(1,SnapshotManager.diff("preciseA","preciseB",0,100).get("changes-found"));
    }
    @Test void recipesLiveValuesAndInvalidPagesAreRejected() {
        SnapshotManager.saveRecipe("recipe","throw new RuntimeException();");SnapshotManager.save("data",42);SnapshotManager.pin("live",42);
        assertThrows(IllegalArgumentException.class,()->SnapshotManager.diff("recipe","data",0,100));
        assertThrows(IllegalStateException.class,()->SnapshotManager.diff("live","data",0,100));
        assertThrows(IllegalArgumentException.class,()->SnapshotManager.diff("data","data",-1,100));
    }
    @Test void budgetExhaustionIsReportedRatherThanClaimingEquality() {
        var result=SnapshotDiff.compare(Collections.nCopies(1_000_001,42),Collections.nCopies(1_000_001,42),0,100);
        assertEquals(true,result.get("scan-limited"));assertEquals("",result.get("value"));
        Object before=1,after=2;
        for(int i=0;i<130;i++) { before=List.of(before);after=List.of(after); }
        assertEquals(true,SnapshotDiff.compare(before,after,0,100).get("scan-limited"));
    }
}
