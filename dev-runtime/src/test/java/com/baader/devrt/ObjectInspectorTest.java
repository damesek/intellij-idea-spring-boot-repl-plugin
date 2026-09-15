package com.baader.devrt;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ObjectInspectorTest {
    private static final class Bean {
        Object child=List.of("hello"); Object missing=null; Bean cycle=this;
        public Object getChild() { throw new AssertionError("Inspector called a getter"); }
        @Override public String toString() { throw new AssertionError("Inspector called toString"); }
    }
    @Test void navigationReadsFieldsWithoutExecutingUserCodeAndPreservesIdentity() {
        Bean bean=new Bean(); ObjectInspector inspector=new ObjectInspector();
        var root=inspector.start(bean,"input");
        assertSame(bean,inspector.current());
        String[] rows=root.get("value").toString().split("\n");
        String child=Arrays.stream(rows).filter(row -> row.contains("Bean.child\t")).findFirst().orElseThrow();
        var nested=inspector.push(root.get("revision").toString(),Integer.parseInt(child.split("\t")[0]));
        assertSame(bean.child,inspector.current());
        inspector.push(nested.get("revision").toString(),0); assertEquals("hello",inspector.current());
        inspector.back(); assertSame(bean.child,inspector.current());
        inspector.back(); assertSame(bean,inspector.current());
        inspector.clear(); assertThrows(IllegalStateException.class,inspector::current);
    }
    @Test void stalePagesCannotSelectDifferentValuesAndNullRemainsAValue() {
        List<Object> items=new ArrayList<>(); items.add(null); items.add("old");
        var inspector=new ObjectInspector(); var page=inspector.start(items,"items");
        items.set(1,"new");
        inspector.push(page.get("revision").toString(),1); assertEquals("old",inspector.current());
        var refreshed=inspector.back();
        assertThrows(IllegalArgumentException.class,() -> inspector.push(page.get("revision").toString(),1));
        inspector.push(refreshed.get("revision").toString(),0); assertNull(inspector.current());
    }
    @Test void arraysMapsCollectionsAndLargePagesAreBounded() {
        var inspector=new ObjectInspector();
        var page=inspector.start(new int[10001],"array");
        assertEquals(50,page.get("value").toString().lines().count()); assertEquals(true,page.get("has-more"));
        assertEquals(true,inspector.page(9950).get("scan-limited"));
        assertThrows(IllegalArgumentException.class,() -> inspector.page(10000));
        Map<String,Object> map=new LinkedHashMap<>(); map.put("line\n\t",null);
        page=inspector.start(map,"map");
        assertEquals(1,page.get("value").toString().lines().count());
        assertTrue(page.get("value").toString().contains("line\\n\\t"));
        inspector.push(page.get("revision").toString(),0); assertNull(inspector.current());
        page=inspector.start(new LinkedHashSet<>(List.of(1,2)),"set"); assertEquals(2,page.get("value").toString().lines().count());
    }
    @Test void cyclesDepthAndPublicBindingTypesAreHandled() {
        var inspector=new ObjectInspector(); Bean bean=new Bean(); var page=inspector.start(bean,"root");
        for(int i=1;i<32;i++) {
            String row=page.get("value").toString().lines().filter(r -> r.contains("Bean.cycle\t")).findFirst().orElseThrow();
            page=inspector.push(page.get("revision").toString(),Integer.parseInt(row.split("\t")[0]));
        }
        var finalPage=page;
        String row=page.get("value").toString().lines().filter(r -> r.contains("Bean.cycle\t")).findFirst().orElseThrow();
        assertThrows(IllegalStateException.class,() -> inspector.push(finalPage.get("revision").toString(),Integer.parseInt(row.split("\t")[0])));
        assertEquals("java.lang.Object",ObjectInspector.sourceType(bean));
        assertEquals("java.lang.Object[]",ObjectInspector.sourceType(new Bean[1]));
        assertEquals("int[]",ObjectInspector.sourceType(new int[1]));
        assertEquals("java.util.List",ObjectInspector.sourceType(List.of(1)));
    }
    @Test void scalarPreviewNeverCallsCustomNumberOrEnumToString() {
        Number number=new Number() {
            public int intValue(){return 1;} public long longValue(){return 1;} public float floatValue(){return 1;} public double doubleValue(){return 1;}
            public String toString(){throw new AssertionError("custom toString");}
        };
        assertTrue(ObjectInspector.preview(number).contains("@"));
        assertTrue(ObjectInspector.preview("x".repeat(1_000_000)).length()<300);
    }
    @org.junit.jupiter.api.Test void bookmarkRestoresAPathAndRejectsMissingOrAmbiguousLabels() {
        ObjectInspector inspector=new ObjectInspector();
        var value=java.util.Map.of("items",java.util.List.of(java.util.Map.of("amount",42)));
        var page=inspector.restorePath(value,"input",java.util.List.of("items","[0]","amount"));
        org.junit.jupiter.api.Assertions.assertEquals(42,inspector.current());
        org.junit.jupiter.api.Assertions.assertTrue(page.get("bookmark-path").toString().contains("."));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,()->inspector.restorePath(value,"input",java.util.List.of("missing")));
        var map=new java.util.LinkedHashMap<Object,Object>();map.put(1,"number");map.put("1","text");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,()->inspector.restorePath(map,"input",java.util.List.of("1")));
    }
}
