package hu.baader.repl.protocol;

import java.util.*;

/** Independent bounded SQL log, shared by recordings, CASE assertions and MCP. */
public record SqlSnapshot(boolean enabled, boolean available, long revision, long total, long dropped,
        long pending, int threshold, List<SqlObservation> events) {
    public static final int MAX_EVENTS=1000, MAX_WIRE=1_500_000;
    public SqlSnapshot {
        events=List.copyOf(events);
        if(revision<0 || total<events.size() || !enabled && !events.isEmpty() || dropped<0 || pending<0 || threshold<2 || threshold>1000 || events.size()>MAX_EVENTS)
            throw new IllegalArgumentException("Invalid SQL snapshot");
        Set<Long> ids=new HashSet<>();
        if(events.stream().anyMatch(e -> !ids.add(e.id()))) throw new IllegalArgumentException("Duplicate SQL observation");
    }
    public static SqlSnapshot disabled() { return new SqlSnapshot(false,false,0,0,0,0,5,List.of()); }
    public boolean partial() { return !enabled || !available || dropped>0 || pending>0 || events.stream().anyMatch(e -> e.kind().equals("SQL") && e.sql().startsWith("<SQL ")); }
    public long count() { return events.stream().filter(e -> e.kind().equals("SQL")).count(); }
    public long nanos() { return events.stream().filter(e -> e.kind().equals("SQL")).mapToLong(SqlObservation::durationNanos).sum(); }
    public long connectionNanos() { return events.stream().filter(e -> e.kind().equals("CONNECTION")).mapToLong(SqlObservation::durationNanos).sum(); }
    public record Group(long root, String fingerprint, List<SqlObservation> events) {
        public Group { events=List.copyOf(events); }
        public long nanos() { return events.stream().mapToLong(SqlObservation::durationNanos).sum(); }
        public SqlObservation sample() { return events.get(0); }
    }
    public List<Group> groups() {
        Map<String,List<SqlObservation>> groups=new LinkedHashMap<>();
        events.stream().filter(e -> e.kind().equals("SQL")).forEach(e -> groups.computeIfAbsent(e.root()+"\n"+e.fingerprint(),k -> new ArrayList<>()).add(e));
        return groups.values().stream().map(es -> new Group(es.get(0).root(),es.get(0).fingerprint(),es))
                .sorted(Comparator.comparingInt((Group g) -> g.events().size()).reversed()).toList();
    }
    public List<Group> findings() {
        return groups().stream().filter(g -> g.events().size()>=threshold && g.sample().sql().toLowerCase(Locale.ROOT).startsWith("select ")).toList();
    }
    public int maxRepetitions() {
        Map<String,Integer> counts=new HashMap<>();
        events.stream().filter(e -> e.kind().equals("SQL")).forEach(e -> counts.merge(e.root()+"\n"+e.datasource()+"\n"+e.sql(),1,Integer::sum));
        return counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    }
    public SqlSnapshot subtree(Set<Long> parents) {
        return new SqlSnapshot(enabled,available,revision,total,dropped,pending,threshold,events.stream().filter(e -> parents.contains(e.parent())).toList());
    }
    public String encode() {
        String s=String.join("\t","sql-log-v1",""+enabled,""+available,""+revision,""+total,""+dropped,""+pending,""+threshold)
                +"\n"+String.join("\n",events.stream().map(SqlObservation::encode).toList());
        if(s.length()>MAX_WIRE) throw new IllegalArgumentException("SQL log too large");
        return s;
    }
    public static SqlSnapshot decode(String wire) {
        if(wire==null || wire.length()>MAX_WIRE) throw new IllegalArgumentException("SQL log too large");
        String[] lines=wire.split("\n",-1), p=lines[0].split("\t",-1);
        if(p.length!=8 || !p[0].equals("sql-log-v1") || lines.length>MAX_EVENTS+2
                || !Set.of("true","false").contains(p[1]) || !Set.of("true","false").contains(p[2])) throw new IllegalArgumentException("Invalid SQL log");
        List<SqlObservation> events=new ArrayList<>();
        for(int i=1;i<lines.length;i++) if(!lines[i].isEmpty()) events.add(SqlObservation.decode(lines[i]));
        return new SqlSnapshot(Boolean.parseBoolean(p[1]),Boolean.parseBoolean(p[2]),Long.parseLong(p[3]),Long.parseLong(p[4]),Long.parseLong(p[5]),Long.parseLong(p[6]),Integer.parseInt(p[7]),events);
    }
}
