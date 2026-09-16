package com.baader.devrt;

import java.lang.reflect.*;
import java.util.*;

/** A session-owned navigation stack. Pages keep the actual values, never executable expressions. */
final class ObjectInspector {
    private record Node(String label, Object value) {}
    private record Child(String label, Object value, String error) {}
    private final List<Node> stack = new ArrayList<>();
    private final List<Child> children = new ArrayList<>();
    private String revision = "";
    static final int PAGE_SIZE = 50, MAX_OFFSET = 9950;

    Map<String, Object> start(Object value, String label) {
        clear(); stack.add(new Node(text(label, 128), value)); return page(0);
    }
    Object current() {
        if (stack.isEmpty()) throw new IllegalStateException("Open a value in Inspector first");
        return stack.get(stack.size()-1).value();
    }
    Map<String, Object> push(String pageRevision, int index) {
        if (!revision.equals(pageRevision) || index < 0 || index >= children.size())
            throw new IllegalArgumentException("Inspector page changed; select the value again");
        Child child = children.get(index);
        if (!child.error().isEmpty()) throw new IllegalArgumentException(child.error());
        if (stack.size() >= 32) throw new IllegalStateException("Inspector depth limit reached (32); go back first");
        stack.add(new Node(child.label(), child.value())); return page(0);
    }
    Map<String, Object> back() {
        if (stack.size() > 1) stack.remove(stack.size()-1);
        return page(0);
    }
    Map<String,Object> restorePath(Object root, String label, List<String> path) {
        if (path.size() > 31) throw new IllegalArgumentException("Inspector bookmark depth limit exceeded");
        start(root, label);
        for (String wanted : path) {
            Child match = null; boolean more = true;
            for (int offset = 0; more && offset <= MAX_OFFSET; offset += PAGE_SIZE) {
                Map<String,Object> page = page(offset); more = Boolean.TRUE.equals(page.get("has-more"));
                for (Child child : children) if (child.label().equals(wanted)) {
                    if (match != null) throw new IllegalArgumentException("Bookmark path is ambiguous; open the value manually");
                    match = child;
                }
            }
            if (more) throw new IllegalArgumentException("Bookmark search exceeds 10000 children; open the value manually");
            if (match == null || !match.error().isEmpty()) throw new IllegalArgumentException("Bookmark path is no longer available: " + wanted);
            stack.add(new Node(match.label(), match.value()));
        }
        return page(0);
    }
    Map<String, Object> page(int offset) {
        if (offset < 0 || offset > MAX_OFFSET) throw new IllegalArgumentException("Inspector offset must be between 0 and 9950");
        Object value = current(); children.clear(); revision = UUID.randomUUID().toString();
        Object original=value;
        HibernateAccess.View orm=HibernateAccess.view(value);
        if(orm!=null)value=orm.contents();
        boolean more = false;
        if (value != null && !scalar(value) && (orm==null || !orm.opaque())) {
            if (value.getClass().isArray()) {
                int size = Array.getLength(value);
                for (int i=offset; i<Math.min(size, offset+PAGE_SIZE); i++) add("["+i+"]", Array.get(value,i));
                more = size > offset+PAGE_SIZE;
            } else if (value instanceof List<?> list) {
                int size = list.size();
                for (int i=offset; i<Math.min(size, offset+PAGE_SIZE); i++) add("["+i+"]", list.get(i));
                more = size > offset+PAGE_SIZE;
            } else if (value instanceof Map<?,?> map) {
                var iterator = map.entrySet().iterator();
                for (int i=0; i<offset+PAGE_SIZE && iterator.hasNext(); i++) {
                    var entry = iterator.next();
                    if (i>=offset) add(rawPreview(entry.getKey()), entry.getValue());
                }
                more = iterator.hasNext();
            } else if (value instanceof Collection<?> collection) {
                var iterator = collection.iterator();
                for (int i=0; i<offset+PAGE_SIZE && iterator.hasNext(); i++) {
                    Object child = iterator.next(); if (i>=offset) add("["+i+"]",child);
                }
                more = iterator.hasNext();
            } else {
                List<Field> fields = new ArrayList<>();
                for (Class<?> type=value.getClass(); type!=null && fields.size()<=10000; type=type.getSuperclass())
                    for (Field field : type.getDeclaredFields())
                        if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic() && !field.getName().startsWith("$$_hibernate_")) fields.add(field);
                fields.sort(Comparator.comparing(f -> f.getDeclaringClass().getName()+"."+f.getName()));
                for (int i=offset; i<Math.min(fields.size(),offset+PAGE_SIZE); i++) {
                    Field field=fields.get(i);
                    String label=field.getDeclaringClass().getSimpleName()+"."+field.getName();
                    if(orm!=null&&orm.unfetched().contains(field.getName())) {
                        children.add(new Child(label,null,"Unfetched Hibernate attribute; not read"));continue;
                    }
                    try {
                        if (!field.trySetAccessible()) throw new IllegalAccessException("Field is not open to the agent");
                        add(label,field.get(value));
                    } catch (RuntimeException | IllegalAccessException failure) {
                        children.add(new Child(label,null,"Field inaccessible: "+field.getName()));
                    }
                }
                more=fields.size()>offset+PAGE_SIZE;
            }
        }
        List<String> rows=new ArrayList<>();
        for (int i=0;i<children.size();i++) {
            Child child=children.get(i);
            rows.add(i+"\t"+text(child.label(),256)+"\t"+text(typeName(child.value()),512)+"\t"+preview(child.value())+"\t"+text(child.error(),256));
        }
        Map<String,Object> result = new LinkedHashMap<>(Map.of("value",String.join("\n",rows),"revision",revision,"offset",offset,"has-more",more,
                "scan-limited",more && offset==MAX_OFFSET,"depth",stack.size(),"type",typeName(original),"preview",preview(original),
                "path",String.join(" / ",stack.stream().map(Node::label).toList())));
        result.putAll(ValuePresentation.present(original));
        if(orm!=null) {
            orm.metadata().forEach((key,text)->result.put("hibernate-"+key,text));
            result.put("hibernate-expanded",!orm.opaque());
            result.put("hibernate-note",orm.opaque()?"Not expanded; inspecting does not initialize a Hibernate proxy or collection":"Already loaded values only; inspecting does not initialize relationships");
        }
        result.put("bookmark-path", String.join(".", stack.stream().skip(1).map(n -> Base64.getUrlEncoder().withoutPadding().encodeToString(n.label().getBytes(java.nio.charset.StandardCharsets.UTF_8))).toList()));
        return result;
    }
    private void add(String label,Object value) { children.add(new Child(label,value,"")); }
    void clear() { stack.clear(); children.clear(); revision=""; }
    static String typeName(Object value) { return value==null ? "null" : value.getClass().getName(); }
    static String sourceType(Object value) {
        if (value==null) return "java.lang.Object";
        Class<?> type=value.getClass();
        if (type.isArray()) {
            Class<?> component=type.getComponentType();
            if (component.isPrimitive()) return type.getCanonicalName();
        }
        if (accessible(type)) return type.getCanonicalName();
        if (type.isArray()) return "java.lang.Object[]";
        if (value instanceof Map) return "java.util.Map";
        if (value instanceof List) return "java.util.List";
        if (value instanceof Set) return "java.util.Set";
        return "java.lang.Object";
    }
    private static boolean accessible(Class<?> type) {
        if(type.isArray()) return accessible(type.getComponentType());
        if(type.isPrimitive()) return true;
        if(type.getCanonicalName()==null) return false;
        for(Class<?> enclosing=type;enclosing!=null;enclosing=enclosing.getEnclosingClass())
            if(!Modifier.isPublic(enclosing.getModifiers())) return false;
        return true;
    }
    private static boolean scalar(Object value) {
        Class<?> type=value.getClass();
        return value instanceof String || value instanceof Enum<?> || value instanceof Class<?>
                || Set.of(Boolean.class,Character.class,Byte.class,Short.class,Integer.class,Long.class,
                Float.class,Double.class,java.math.BigDecimal.class,java.math.BigInteger.class).contains(type);
    }
    static String preview(Object value) {
        return text(rawPreview(value),256);
    }
    private static String rawPreview(Object value) {
        if (value==null) return "null";
        if (value instanceof Enum<?> item) return item.name();
        if (value instanceof Class<?> type) return type.getName();
        if (value.getClass()==java.math.BigInteger.class && ((java.math.BigInteger)value).bitLength()>1024) return "BigInteger ("+((java.math.BigInteger)value).bitLength()+" bits)";
        if (value.getClass()==java.math.BigDecimal.class && ((java.math.BigDecimal)value).unscaledValue().bitLength()>1024) return "BigDecimal (large value)";
        if (scalar(value)) return String.valueOf(value);
        return typeName(value)+"@"+Integer.toHexString(System.identityHashCode(value));
    }
    static String text(String value,int max) {
        if (value==null) return "";
        StringBuilder out=new StringBuilder();
        for (int i=0;i<Math.min(value.length(),max);i++) {
            char c=value.charAt(i);
            switch(c) {
                case '\\' -> out.append("\\\\"); case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r"); case '\t' -> out.append("\\t");
                default -> out.append(Character.isISOControl(c) ? '?' : c);
            }
        }
        if(value.length()>max) out.append("…");
        return out.toString();
    }
}
