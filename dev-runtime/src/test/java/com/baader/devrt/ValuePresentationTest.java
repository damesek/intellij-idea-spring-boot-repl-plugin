package com.baader.devrt;

import hu.baader.repl.protocol.ValueTree;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ValuePresentationTest {
    static class Dto {
        final String name = "Árvíz\n東京";
        final List<Integer> scores = List.of(3, 8);
        public String getSecret() { throw new AssertionError("Getter called"); }
        @Override public String toString() { throw new AssertionError("toString called"); }
    }
    static class LazyList extends AbstractList<Object> {
        final String state = "not loaded";
        @Override public Object get(int index) { throw new AssertionError("Lazy get called"); }
        @Override public int size() { throw new AssertionError("Lazy size called"); }
        @Override public String toString() { throw new AssertionError("Lazy toString called"); }
    }
    private ValueTree view(Object value) { return ValueTree.decode((String)ValuePresentation.present(value).get("view-data")); }
    private ValueTree field(ValueTree tree, String name) { return tree.children().stream().filter(n -> n.label().equals(name)).findFirst().orElseThrow(); }
    @Test void fieldsAreFormattedWithoutGettersOrToStringAndJsonRemainsRaw() {
        ValueTree dto = view(new Dto());
        assertEquals("Árvíz\n東京", field(dto, "name").text());
        assertEquals(List.of("3", "8"), field(dto, "scores").children().stream().map(ValueTree::text).toList());
        String json="{\"name\":\"Árvíz\",\"items\":[1,null,true]}";
        assertEquals(json, view(json).text()); assertEquals("STRING", view(json).kind());
    }
    @Test void customAndLazyCollectionsAreNotIteratedAutomatically() {
        ValueTree tree = view(new LazyList());
        assertEquals("OBJECT", tree.kind()); assertEquals("not loaded", field(tree, "state").text());
        assertEquals("OBJECT",view(Collections.unmodifiableList(new LazyList())).kind());
        assertEquals("OBJECT",view(Collections.synchronizedList(new LazyList())).kind());
    }
    @Test void cyclesNullsAndRepeatedReferencesHaveFiniteExplicitRepresentations() {
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("self", value); value.put("nothing", null);
        ValueTree tree = view(value);
        assertEquals("REFERENCE", field(tree, "self").kind()); assertEquals("result", field(tree, "self").text());
        assertEquals("NULL", field(tree, "nothing").kind());
    }
    @Test void hugeAndDeepValuesAreMarkedAsPartialAndRemainBounded() {
        var wide = ValuePresentation.present(new int[1_000_000]);
        ValueTree tree=ValueTree.decode((String)wide.get("view-data"));
        assertEquals(51, tree.children().size()); assertEquals("LIMIT", tree.children().get(50).kind()); assertEquals(true, wide.get("view-limited"));
        var text=ValuePresentation.present("x".repeat(1_000_000));
        assertTrue(((String)text.get("view-data")).length()<200_000); assertEquals(true,text.get("view-limited"));
        Object nested=1; for(int i=0;i<50;i++) nested=List.of(nested);
        assertEquals(true,ValuePresentation.present(nested).get("view-limited"));
    }
    @Test void jacksonNodesUseLogicalJsonPropertiesAndPreserveExactNumbers() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        ValueTree tree=view(mapper.readTree("{\"id\":123456789012345678901234567890,\"data\":[\"hello\",null,true]}"));
        assertEquals("123456789012345678901234567890",field(tree,"id").text());
        assertEquals("ARRAY",field(tree,"data").kind());
        assertEquals("hello",field(tree,"data").children().get(0).text());
    }
    @Test void evalReturnsPresentationWithoutReplacingOrReevaluatingTheActualObject() {
        try(JShellSession shell=new JShellSession(null)) {
            var result=shell.eval("var items = new java.util.ArrayList<String>(); items.add(\"one\"); items");
            assertEquals("",result.error());
            assertEquals("ARRAY",ValueTree.decode((String)result.presentation().get("view-data")).kind());
            assertSame(shell.value(result.handle(),null),shell.value(null,"items"));
            assertEquals("1",shell.eval("items.size()").values().get(0));
        }
    }
    @Test void wireEncodingEscapesRowsAndRejectsMalformedOrOversizedTrees() {
        ValueTree original=new ValueTree("\troot\n", "OBJECT", "Type", "", List.of(ValueTree.leaf("<html>\n", "STRING", "String", "東京\\\r\t")));
        assertEquals(original,ValueTree.decode(original.encode()));
        for(String input:List.of("", "v1\n1\tNULL\t\t\t\n", "v1\n0\tBOGUS\t\t\t\n", "v1\n0\tNULL\t\t\t\n0\tNULL\t\t\t\n"))
            assertThrows(IllegalArgumentException.class,()->ValueTree.decode(input));
        assertThrows(IllegalArgumentException.class,()->ValueTree.decode("x".repeat(ValueTree.MAX_WIRE+1)));
    }
}
