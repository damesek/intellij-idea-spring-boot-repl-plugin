package com.baader.devrt;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/** Portable JSON assertions. This exact source is included in JUnit exports; no agent or Spring dependency. */
public final class CaseAssertions {
    private static final Object MISSING = new Object();
    private static final Set<String> OPTIONS = Set.of("compareSnapshot", "include", "ignore", "unordered", "numericTolerance", "timeToleranceMs", "checks");
    private static final Set<String> OPS = Set.of("equals", "contains", "hasSize", "matches", "exists", "notNull", "isNull");
    public record Report(String outcome, String detail, List<String> failures) {}
    private static final class Budget extends RuntimeException {}
    private int nodes;
    private boolean checking;
    private final Map<String, Object> options;
    private final List<String> failures = new ArrayList<>();
    private CaseAssertions(Map<String,Object> options) { this.options = options; }

    public static void validate(Map<String,Object> options) {
        if (!OPTIONS.containsAll(options.keySet())) throw new IllegalArgumentException("Unknown assertion option");
        if (options.containsKey("compareSnapshot") && !(options.get("compareSnapshot") instanceof Boolean)) throw new IllegalArgumentException("compareSnapshot must be boolean");
        for (String key : List.of("include", "ignore", "unordered")) {
            Object value = options.getOrDefault(key, List.of());
            if (!(value instanceof List<?> paths) || paths.size() > 100) throw new IllegalArgumentException(key + " must contain at most 100 paths");
            for (Object path : paths) path(path);
        }
        for (String key : List.of("numericTolerance", "timeToleranceMs")) {
            if (!(options.getOrDefault(key, Map.of()) instanceof Map<?,?> values) || values.size() > 100) throw new IllegalArgumentException(key + " must be a path-to-number object");
            for (var entry : values.entrySet()) { path(entry.getKey()); decimal(entry.getValue()); }
        }
        if (!(options.getOrDefault("checks", List.of()) instanceof List<?> checks) || checks.size() > 100) throw new IllegalArgumentException("At most 100 checks are supported");
        for (Object item : checks) {
            if (!(item instanceof Map<?,?> check) || !Set.of("path", "op", "value", "match").containsAll(check.keySet())) throw new IllegalArgumentException("Invalid check");
            path(check.get("path"));
            if (!OPS.contains(check.get("op"))) throw new IllegalArgumentException("Unsupported check operator");
            if (!List.of("all", "any").contains(check.containsKey("match") ? check.get("match") : "all")) throw new IllegalArgumentException("match must be all or any");
            String op = check.get("op").toString();
            if (!Set.of("exists", "notNull", "isNull").contains(op) && !check.containsKey("value")) throw new IllegalArgumentException("Check value is required");
            if (op.equals("hasSize")) decimal(check.get("value")).intValueExact();
            if (op.equals("matches")) {
                if (!(check.get("value") instanceof String regex) || regex.length() > 256) throw new IllegalArgumentException("Regex must be a string of at most 256 characters");
                Pattern.compile(regex);
            }
        }
        if (Boolean.FALSE.equals(options.get("compareSnapshot")) && checks.isEmpty()) throw new IllegalArgumentException("Add checks when snapshot comparison is disabled");
    }
    public static Report compare(Object expected, Object actual, Map<String,Object> options) {
        validate(options);
        CaseAssertions engine = new CaseAssertions(options);
        try {
            if (!Boolean.FALSE.equals(options.get("compareSnapshot"))) {
                List<?> includes = (List<?>) options.getOrDefault("include", List.of());
                if (includes.isEmpty()) engine.visit("", expected, actual, 0, true);
                else for (Object item : includes) {
                    String selected = item.toString();
                    Map<String,Object> left = engine.select(expected, selected), right = engine.select(actual, selected);
                    Set<String> paths = new LinkedHashSet<>(left.keySet()); paths.addAll(right.keySet());
                    if (paths.isEmpty()) engine.fail(selected + ": included path is missing");
                    for (String p : paths) engine.visit(p, left.getOrDefault(p,MISSING), right.getOrDefault(p,MISSING),0,true);
                }
            }
            engine.checking = true;
            for (Object item : (List<?>) options.getOrDefault("checks", List.of())) {
                Map<?,?> check = (Map<?,?>) item;
                Map<String,Object> selected = engine.select(actual, check.get("path").toString());
                boolean any = "any".equals(check.get("match")), passed = !selected.isEmpty();
                if (any) passed = false;
                for (var entry : selected.entrySet()) {
                    boolean match = engine.check(entry.getKey(), entry.getValue(), check);
                    passed = any ? passed || match : passed && match;
                }
                if (!passed) engine.fail(check.get("path") + ": " + check.get("op") + " (" + (any?"any":"all") + ") failed");
            }
            return new Report(engine.failures.isEmpty()?"PASSED":"FAILED", engine.failures.isEmpty()?"All assertions passed":"Assertions failed", List.copyOf(engine.failures));
        } catch (Budget limited) {
            return new Report(engine.failures.isEmpty()?"INCONCLUSIVE":"FAILED", "Assertion budget reached; comparison incomplete", List.copyOf(engine.failures));
        }
    }
    private void tick(int depth) {
        if (++nodes > 200_000 || depth > 100 || Thread.currentThread().isInterrupted()) throw new Budget();
    }
    private void fail(String message) { if (failures.size()<100) failures.add(message.substring(0, Math.min(512,message.length())).replace('\n',' ').replace('\r',' ').replace('\t',' ')); }
    private boolean visit(String p, Object a, Object b, int depth, boolean report) {
        tick(depth);
        if (!checking && matches("ignore",p,true)) return true;
        boolean same = true;
        if (a == MISSING || b == MISSING) same = false;
        else if (a instanceof Map<?,?> left && b instanceof Map<?,?> right) {
            Set<Object> keys = new LinkedHashSet<>(left.keySet()); keys.addAll(right.keySet());
            for (Object key : keys) same &= visit(child(p,key),left.containsKey(key)?left.get(key):MISSING,right.containsKey(key)?right.get(key):MISSING,depth+1,report);
            return same;
        } else if (a instanceof List<?> left && b instanceof List<?> right) {
            if (matches("unordered",p,false)) {
                if (left.size()!=right.size()) same = false;
                else {
                    if (left.size()>200) throw new Budget();
                    boolean[][] edges = new boolean[left.size()][right.size()];
                    for(int i=0;i<left.size();i++) for(int j=0;j<right.size();j++) edges[i][j]=visit(p+"/"+i,left.get(i),right.get(j),depth+1,false);
                    int[] matched = new int[right.size()]; Arrays.fill(matched,-1);
                    for(int i=0;i<left.size();i++) if(!augment(i,edges,matched,new boolean[right.size()])) {same=false;break;}
                }
            } else {
                for(int i=0;i<Math.max(left.size(),right.size());i++) same &= visit(p+"/"+i,i<left.size()?left.get(i):MISSING,i<right.size()?right.get(i):MISSING,depth+1,report);
                return same;
            }
        } else if (a instanceof Number && b instanceof Number) {
            BigDecimal tolerance = tolerance("numericTolerance",p);
            same = numeric((Number)a).subtract(numeric((Number)b)).abs().compareTo(tolerance==null?BigDecimal.ZERO:tolerance)<=0;
        } else if (a instanceof String x && b instanceof String y && tolerance("timeToleranceMs",p)!=null) {
            try { same = milliseconds(java.time.Duration.between(Instant.parse(x),Instant.parse(y)).abs()).compareTo(tolerance("timeToleranceMs",p))<=0; }
            catch (RuntimeException invalidDate) { same = false; }
        } else same = Objects.equals(a,b);
        if (!same && report) fail((p.isEmpty()?"(root)":p)+": CHANGED expected="+preview(a)+", actual="+preview(b));
        return same;
    }
    private boolean augment(int i, boolean[][] edges, int[] matched, boolean[] seen) {
        tick(0);
        for(int j=0;j<matched.length;j++) if(edges[i][j]&&!seen[j]) {seen[j]=true;if(matched[j]<0||augment(matched[j],edges,matched,seen)) {matched[j]=i;return true;}}
        return false;
    }
    private boolean check(String p, Object value, Map<?,?> check) {
        tick(0); Object expected=check.get("value");
        return switch(check.get("op").toString()) {
            case "exists" -> true;
            case "notNull" -> value!=null;
            case "isNull" -> value==null;
            case "equals" -> visit(p,expected,value,0,false);
            case "hasSize" -> Objects.equals(expected instanceof Number n ? new BigDecimal(n.toString()).intValueExact():null,
                    value instanceof List<?> l?l.size():value instanceof Map<?,?> m?m.size():value instanceof String s?s.length():null);
            case "contains" -> {
                if(value instanceof String s && expected instanceof String part) yield s.contains(part);
                boolean found=false;
                if(value instanceof List<?> list) for(Object entry:list) if(visit(p,expected,entry,0,false)) {found=true;break;}
                yield found;
            }
            case "matches" -> value instanceof String s && regex(expected.toString(),s);
            default -> false;
        };
    }
    private static boolean regex(String pattern,String value) {
        try { return Pattern.compile(pattern).matcher(new TimedText(value,System.nanoTime()+100_000_000)).matches(); }
        catch(StackOverflowError limit) { throw new Budget(); }
    }
    private record TimedText(String value,long deadline) implements CharSequence {
        public int length(){return value.length();}
        public char charAt(int index){if(Thread.currentThread().isInterrupted()||System.nanoTime()>deadline)throw new Budget();return value.charAt(index);}
        public CharSequence subSequence(int start,int end){return new TimedText(value.substring(start,end),deadline);}
        public String toString(){return value;}
    }
    private boolean matches(String key,String p,boolean subtree) {
        for(Object pattern:(List<?>)options.getOrDefault(key,List.of())) if(match(pattern.toString(),p,subtree)) return true;
        return false;
    }
    private BigDecimal tolerance(String key,String p) {
        Map<?,?> tolerances=(Map<?,?>)options.getOrDefault(key,Map.of());
        if(tolerances.containsKey(p)) return decimal(tolerances.get(p));
        for(var e:tolerances.entrySet()) if(match(e.getKey().toString(),p,false)) return decimal(e.getValue());
        return null;
    }
    private static boolean match(String pattern,String p,boolean subtree) {
        String[] left=segments(pattern),right=segments(p);
        if(left.length>right.length||!subtree&&left.length!=right.length)return false;
        for(int i=0;i<left.length;i++) if(!left[i].equals("*")&&!left[i].equals(right[i]))return false;
        return true;
    }
    private Map<String,Object> select(Object root,String path) {
        Map<String,Object> result=new LinkedHashMap<>(); select(root,segments(path),0,"",result);return result;
    }
    private void select(Object value,String[] path,int index,String p,Map<String,Object> result) {
        tick(index);
        if(index==path.length){result.put(p,value);return;}
        String key=path[index];
        if(value instanceof Map<?,?> map) {
            for(var e:map.entrySet()) if(key.equals("*")||key.equals(e.getKey().toString())) select(e.getValue(),path,index+1,child(p,e.getKey()),result);
        } else if(value instanceof List<?> list) for(int i=0;i<list.size();i++) if(key.equals("*")||key.equals(String.valueOf(i))) select(list.get(i),path,index+1,p+"/"+i,result);
    }
    private static String[] segments(String path){return path.isEmpty()?new String[0]:Arrays.stream(path.substring(1).split("/",-1)).map(s->s.replace("~1","/").replace("~0","~")).toArray(String[]::new);}
    private static String child(String p,Object key){return p+"/"+key.toString().replace("~","~0").replace("/","~1");}
    private static void path(Object p){if(!(p instanceof String s)||s.length()>1024||!s.isEmpty()&&!s.startsWith("/")||s.matches(".*~(?:[^01]|$).*")) throw new IllegalArgumentException("Use JSON Pointer paths (empty = root, /items/*/id supports one-level wildcards)");}
    private static BigDecimal milliseconds(java.time.Duration duration){return BigDecimal.valueOf(duration.getSeconds()).multiply(BigDecimal.valueOf(1000)).add(BigDecimal.valueOf(duration.getNano(),6));}
    private static BigDecimal numeric(Number value){BigDecimal n=new BigDecimal(value.toString());if(n.precision()>10000||Math.abs((long)n.scale())>10000)throw new Budget();return n;}
    private static BigDecimal decimal(Object value){if(!(value instanceof Number))throw new IllegalArgumentException("Tolerance/size must be a non-negative number");BigDecimal n=new BigDecimal(value.toString());if(n.signum()<0||n.precision()>100||Math.abs((long)n.scale())>1000)throw new IllegalArgumentException("Invalid tolerance/size");return n;}
    private static String preview(Object value){if(value==MISSING)return "<missing>";if(value instanceof Map<?,?> m)return "object("+m.size()+")";if(value instanceof List<?> l)return "list("+l.size()+")";String s=Objects.toString(value);return s.substring(0,Math.min(s.length(),160));}
}
