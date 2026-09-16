package com.baader.devrt;

import hu.baader.repl.protocol.ValueTree;
import java.lang.reflect.*;
import java.util.*;

/** Automatic previews read instance fields; custom collections, getters and toString are not invoked. */
final class ValuePresentation {
    static final int MAX_NODES = 500, MAX_DEPTH = 6, MAX_CHILDREN = 50, MAX_TEXT = 131_072;
    private static final Set<String> COLLECTIONS = Set.of("java.util.ArrayList", "java.util.LinkedList", "java.util.Vector", "java.util.Stack",
            "java.util.Arrays$ArrayList", "java.util.HashMap", "java.util.LinkedHashMap", "java.util.TreeMap", "java.util.Hashtable",
            "java.util.IdentityHashMap", "java.util.WeakHashMap", "java.util.EnumMap", "java.util.HashSet", "java.util.LinkedHashSet",
            "java.util.TreeSet", "java.util.RegularEnumSet", "java.util.JumboEnumSet", "java.util.ArrayDeque", "java.util.PriorityQueue",
            "java.util.concurrent.ConcurrentHashMap", "java.util.concurrent.ConcurrentSkipListMap", "java.util.concurrent.CopyOnWriteArrayList",
            "java.util.concurrent.ConcurrentLinkedQueue", "java.util.concurrent.ConcurrentLinkedDeque");
    private final IdentityHashMap<Object,String> seen = new IdentityHashMap<>();
    private int nodes, characters;
    private boolean limited;
    static Map<String,Object> present(Object value) {
        ValuePresentation renderer = new ValuePresentation();
        try {
            ValueTree tree = renderer.node("result", value, 0, "result");
            return Map.of("view-data", tree.encode(), "view-limited", renderer.limited);
        } catch (RuntimeException | LinkageError failure) {
            // A failed preview must not turn an already successful application call into an eval failure.
            ValueTree fallback = ValueTree.leaf("result", "ERROR", "", "Preview unavailable: " + failure.getClass().getSimpleName());
            return Map.of("view-data", fallback.encode(), "view-limited", true);
        }
    }
    private ValueTree limit(String label, String type, String message) {
        limited = true; return ValueTree.leaf(label, "LIMIT", type, message);
    }
    private String text(String value, int max) {
        int length = Math.min(value.length(), Math.min(max, Math.max(0, MAX_TEXT - characters)));
        characters += length;
        if (length < value.length()) { limited = true; return value.substring(0, length) + "…"; }
        return value;
    }
    private ValueTree node(String label, Object value, int depth, String path) {
        return node(label,value,depth,path,null);
    }
    private ValueTree node(String label, Object value, int depth, String path,HibernateAccess.View ormContents) {
        String type = ObjectInspector.typeName(value);
        if (++nodes > MAX_NODES || characters >= MAX_TEXT) return limit(label, type, "Preview budget reached");
        if (value == null) return ValueTree.leaf(label, "NULL", "null", "null");
        Class<?> clazz = value.getClass();
        if (value instanceof String string) return ValueTree.leaf(label, "STRING", type, text(string, depth == 0 ? MAX_TEXT : 4096));
        if (clazz == Boolean.class) return ValueTree.leaf(label, "BOOLEAN", type, value.toString());
        if (Set.of(Byte.class, Short.class, Integer.class, Long.class, Float.class, Double.class,
                java.math.BigInteger.class, java.math.BigDecimal.class).contains(clazz))
            return number(label, type, value);
        if (value instanceof Character || value instanceof Enum<?> || value instanceof Class<?>)
            return ValueTree.leaf(label, "STRING", type, ObjectInspector.preview(value));
        HibernateAccess.View orm=ormContents!=null?null:HibernateAccess.view(value);
        if(orm!=null) {
            List<ValueTree> fields=new ArrayList<>();
            orm.metadata().forEach((key,text)->fields.add(ValueTree.leaf("hibernate."+key,"STRING","",text(text,4096))));
            if(orm.opaque())fields.add(limit("contents",type,"Not expanded; Hibernate relationships are not initialized by inspection"));
            else fields.add(node("contents",orm.contents(),depth+1,path+".contents",orm));
            return new ValueTree(label,"OBJECT",type,"",fields);
        }
        String earlier = seen.putIfAbsent(value, path);
        if (earlier != null) return ValueTree.leaf(label, "REFERENCE", type, earlier);
        if (depth >= MAX_DEPTH) return limit(label, type, "Depth limit; open this value in Inspector → Fields");
        List<ValueTree> children = new ArrayList<>();
        String kind = "OBJECT";
        try {
            // Only standard Jackson nodes: user subclasses are displayed via fields below.
            String json = clazz.getName();
            if (json.startsWith("com.fasterxml.jackson.databind.node.")) {
                switch (clazz.getSimpleName()) {
                    case "NullNode", "MissingNode": return ValueTree.leaf(label, "NULL", type, "null");
                    case "TextNode": return ValueTree.leaf(label, "STRING", type, text((String)clazz.getMethod("textValue").invoke(value), depth == 0 ? MAX_TEXT : 4096));
                    case "BooleanNode": return ValueTree.leaf(label, "BOOLEAN", type, clazz.getMethod("booleanValue").invoke(value).toString());
                    case "IntNode", "LongNode", "ShortNode", "FloatNode", "DoubleNode", "BigIntegerNode", "DecimalNode":
                        return number(label, type, clazz.getMethod("numberValue").invoke(value));
                    case "ObjectNode": {
                        Iterator<?> entries = (Iterator<?>)clazz.getMethod("fields").invoke(value);
                        while (entries.hasNext() && room(children)) {
                            Map.Entry<?,?> entry = (Map.Entry<?,?>)entries.next();
                            String key = text((String)entry.getKey(), 256);
                            children.add(node(key, entry.getValue(), depth + 1, path + "." + key));
                        }
                        if (entries.hasNext()) children.add(limit("…", "", "More fields; preview limit reached"));
                        return new ValueTree(label, kind, type, "", children);
                    }
                    case "ArrayNode": {
                        Iterator<?> items = (Iterator<?>)clazz.getMethod("elements").invoke(value);
                        appendItems(items, children, depth, path);
                        return new ValueTree(label, "ARRAY", type, "", children);
                    }
                }
            }
            // Delegating wrappers may wrap a lazy/custom collection, even when the wrapper itself is from the JDK.
            boolean standardCollection = clazz.getClassLoader() == null && (COLLECTIONS.contains(clazz.getName())
                    || clazz.getName().startsWith("java.util.ImmutableCollections$") || clazz.getName().startsWith("java.util.Collections$Empty")
                    || clazz.getName().startsWith("java.util.Collections$Singleton"));
            if (clazz.isArray()) {
                kind = "ARRAY"; int size = Array.getLength(value);
                for (int i=0; i<size && room(children); i++) children.add(node("["+i+"]", Array.get(value, i), depth+1, path+"["+i+"]"));
                if (size > children.size()) children.add(limit("…", "", "More elements; preview limit reached"));
            } else if (standardCollection && value instanceof Map<?,?> map) {
                Iterator<? extends Map.Entry<?,?>> entries = map.entrySet().iterator();
                while (entries.hasNext() && room(children)) {
                    Map.Entry<?,?> entry = entries.next();
                    String key = entry.getKey() instanceof String string ? text(string, 256) : ObjectInspector.preview(entry.getKey());
                    children.add(node(key, entry.getValue(), depth+1, path+"."+key));
                }
                if (entries.hasNext()) children.add(limit("…", "", "More entries; preview limit reached"));
            } else if (standardCollection && value instanceof Collection<?> collection) {
                kind = "ARRAY"; appendItems(collection.iterator(), children, depth, path);
            } else {
                List<Field> fields = new ArrayList<>();
                for (Class<?> parent=clazz; parent!=null && fields.size()<1000; parent=parent.getSuperclass()) {
                    for (Field field : parent.getDeclaredFields()) {
                        if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic() && !field.getName().startsWith("$$_hibernate_")) fields.add(field);
                        if (fields.size() >= 1000) break;
                    }
                }
                fields.sort(Comparator.comparing(f -> f.getDeclaringClass().getName()+"."+f.getName()));
                for (Field field : fields) {
                    if (!room(children)) break;
                    String key = field.getName();
                    if (fields.stream().filter(f -> f.getName().equals(field.getName())).count()>1) key=field.getDeclaringClass().getSimpleName()+"."+key;
                    if(ormContents!=null&&ormContents.unfetched().contains(field.getName()))children.add(limit(key,field.getType().getTypeName(),"Unfetched Hibernate attribute; not read"));
                    else if (!field.trySetAccessible()) children.add(ValueTree.leaf(key, "ERROR", field.getType().getTypeName(), "Field is not open to the agent"));
                    else children.add(node(key, field.get(value), depth+1, path+"."+key));
                }
                if (fields.size()>children.size()) children.add(limit("…", "", "More fields; preview limit reached"));
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            children.add(ValueTree.leaf("unavailable", "ERROR", "", "Cannot read value: " + failure.getClass().getSimpleName()));
        }
        return new ValueTree(label, kind, type, "", children);
    }
    private boolean room(List<ValueTree> children) { return children.size()<MAX_CHILDREN && nodes<MAX_NODES && characters<MAX_TEXT; }
    private ValueTree number(String label, String type, Object value) {
        String preview = ObjectInspector.preview(value);
        if (preview.startsWith("BigInteger (") || preview.startsWith("BigDecimal (")) limited = true;
        return ValueTree.leaf(label, preview.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?") ? "NUMBER" : "STRING", type, preview);
    }
    private void appendItems(Iterator<?> items, List<ValueTree> children, int depth, String path) {
        while (items.hasNext() && room(children)) {
            int index=children.size(); children.add(node("["+index+"]", items.next(), depth+1, path+"["+index+"]"));
        }
        if (items.hasNext()) children.add(limit("…", "", "More elements; preview limit reached"));
    }
}
