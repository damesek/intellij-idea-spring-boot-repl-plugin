package com.baader.devrt;

import java.math.BigDecimal;
import java.util.*;

/** Compares JSON data only: no DTO construction, getters or recipe execution. */
final class SnapshotDiff {
    private static final Object MISSING = new Object();
    private static final int MAX_NODES = 1_000_000, MAX_DEPTH = 128;
    private final int offset, limit;
    private int visited, changes;
    private boolean limited;
    private final List<String> rows = new ArrayList<>();
    private SnapshotDiff(int offset, int limit) { this.offset=offset; this.limit=limit; }
    static Map<String,Object> compare(Object before, Object after, int offset, int limit) {
        if (offset < 0 || offset > 9900 || limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid diff page");
        SnapshotDiff diff = new SnapshotDiff(offset,limit); diff.visit("",before,after,0);
        return Map.of("value", String.join("\n",diff.rows), "has-more", diff.changes > offset+limit,
                "scan-limited",diff.limited,"changes-found",diff.changes,"visited",diff.visited);
    }
    private boolean stopped() { return limited || changes > offset+limit; }
    private void visit(String path, Object before, Object after, int depth) {
        if (stopped()) return;
        if (++visited > MAX_NODES || depth > MAX_DEPTH || Thread.currentThread().isInterrupted()) { limited=true; return; }
        if (before == after) return;
        if (before instanceof Map<?,?> left && after instanceof Map<?,?> right) {
            for (var entry : left.entrySet()) {
                visit(child(path,entry.getKey()),entry.getValue(),right.containsKey(entry.getKey()) ? right.get(entry.getKey()) : MISSING,depth+1);
                if (stopped()) return;
            }
            for (var entry : right.entrySet()) if (!left.containsKey(entry.getKey())) {
                visit(child(path,entry.getKey()),MISSING,entry.getValue(),depth+1); if (stopped()) return;
            }
        } else if (before instanceof List<?> left && after instanceof List<?> right) {
            for (int i=0; i<Math.max(left.size(),right.size()); i++) {
                visit(path+"/"+i,i<left.size()?left.get(i):MISSING,i<right.size()?right.get(i):MISSING,depth+1); if (stopped()) return;
            }
        } else if (!equalScalar(before,after)) {
            if (changes++ >= offset && rows.size() < limit)
                rows.add(escape(path.isEmpty()?"(root)":path,2048) + "\t" + (before==MISSING?"ADDED":after==MISSING?"REMOVED":"CHANGED")
                        + "\t" + preview(before) + "\t" + preview(after));
        }
    }
    private static boolean equalScalar(Object left, Object right) {
        if (left instanceof Number a && right instanceof Number b) return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString()))==0;
        return Objects.equals(left,right);
    }
    private static String child(String parent,Object key) { return parent+"/"+key.toString().replace("~","~0").replace("/","~1"); }
    private static String preview(Object value) {
        if (value==MISSING) return "<missing>";
        if (value==null) return "null";
        if (value instanceof Map<?,?> map) return "{object: "+map.size()+" fields}";
        if (value instanceof List<?> list) return "[array: "+list.size()+" elements]";
        if (value instanceof String text) return "\""+escape(text,256)+"\"";
        return escape(value.toString(),256);
    }
    private static String escape(String text,int length) {
        StringBuilder result=new StringBuilder();
        for (int i=0;i<Math.min(text.length(),length);i++) {
            char c=text.charAt(i);
            switch(c) {
                case '\\' -> result.append("\\\\"); case '"' -> result.append("\\\"");
                case '\n' -> result.append("\\n"); case '\r' -> result.append("\\r"); case '\t' -> result.append("\\t");
                default -> { if (Character.isISOControl(c)) result.append(String.format("\\u%04x",(int)c)); else result.append(c); }
            }
        }
        if(text.length()>length) result.append("… [preview]");
        return result.toString();
    }
}
