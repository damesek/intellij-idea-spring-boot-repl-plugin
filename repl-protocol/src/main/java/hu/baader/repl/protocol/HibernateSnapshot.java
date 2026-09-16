package hu.baader.repl.protocol;

import java.util.*;

/** Independent per-recording/per-CASE evidence; never a difference of global Hibernate statistics. */
public record HibernateSnapshot(boolean enabled, boolean available, String version, long revision,
        long dropped, long pending, List<HibernateObservation> events) {
    public static final int MAX_EVENTS=1000, MAX_WIRE=1_500_000;
    public HibernateSnapshot {
        events=List.copyOf(events);
        if(!version.matches("[A-Za-z0-9._+\\-]{0,256}") || revision<0 || dropped<0 || pending<0 || events.size()>MAX_EVENTS || !enabled&&!events.isEmpty())
            throw new IllegalArgumentException("Invalid Hibernate snapshot");
        Map<Long,HibernateObservation> ids=new HashMap<>();
        for(var e:events) if(ids.put(e.id(),e)!=null) throw new IllegalArgumentException("Duplicate Hibernate event");
        for(var e:events) if(e.parentOrm()!=0 && ids.containsKey(e.parentOrm())) {
            var ancestor=e;int depth=0;
            while(ancestor!=null){if(++depth>64)throw new IllegalArgumentException("Hibernate ancestry too deep");ancestor=ids.get(ancestor.parentOrm());}
            var p=ids.get(e.parentOrm());
            if(e.root()!=p.root() || e.thread()!=p.thread() || !e.session().equals(p.session())) throw new IllegalArgumentException("Invalid Hibernate ancestry");
        }
    }
    public static HibernateSnapshot disabled() { return new HibernateSnapshot(false,false,"",0,0,0,List.of()); }
    public boolean partial() { return !enabled || !available || dropped>0 || pending>0; }
    public long count(String kind) { return events.stream().filter(e->e.kind().equals(kind)).count(); }
    public long lazyCount() { return events.stream().filter(HibernateObservation::lazy).count(); }
    public long responseLazyCount() { return events.stream().filter(e->e.lazy()&&e.responsePhase()).count(); }
    public long flushCount() { return events.stream().filter(e->e.kind().equals("FLUSH") || e.kind().equals("AUTO_FLUSH")&&e.detail().contains("required=true")).count(); }
    public List<SqlObservation> sqlFor(long id,SqlSnapshot sql) { return sqlByOrm(sql).getOrDefault(id,List.of()); }
    /** Build the ancestor index once for a full findings/MCP page, rather than for every event. */
    public Map<Long,List<SqlObservation>> sqlByOrm(SqlSnapshot sql) {
        Map<Long,HibernateObservation> index=new HashMap<>();events.forEach(e->index.put(e.id(),e));
        Map<Long,List<SqlObservation>> result=new HashMap<>();
        for(var s:sql.events()) {
            long parent=s.orm();int depth=0;
            while(parent>0&&depth++<=MAX_EVENTS) {
                var e=index.get(parent);if(e==null)break;
                if(e.root()!=s.root()||e.thread()!=s.thread())break;
                result.computeIfAbsent(parent,k->new ArrayList<>()).add(s);parent=e.parentOrm();
            }
        }
        result.replaceAll((k,v)->List.copyOf(v));return Map.copyOf(result);
    }
    public record Finding(String kind,long root,String entity,String role,List<Long> events,List<Long> sqlIds,String explanation) {
        public Finding { events=List.copyOf(events);sqlIds=List.copyOf(sqlIds); }
    }
    public List<Finding> findings(SqlSnapshot sql,int threshold) {
        if(threshold<2)throw new IllegalArgumentException("Repetition threshold must be at least two");
        var correlated=sqlByOrm(sql);
        Map<String,List<HibernateObservation>> groups=new LinkedHashMap<>();
        Map<Long,List<Long>> selects=new HashMap<>();
        for(var e:events)if(e.lazy()) {
            var ids=correlated.getOrDefault(e.id(),List.of()).stream().filter(s->s.kind().equals("SQL")&&s.sql().startsWith("select ")).map(SqlObservation::id).toList();
            selects.put(e.id(),ids);
            if(!ids.isEmpty())groups.computeIfAbsent(e.root()+"\n"+e.kind()+"\n"+e.entity()+"\n"+e.role()+"\n"+e.sourceClass()+":"+e.sourceLine(),k->new ArrayList<>()).add(e);
        }
        List<Finding> result=new ArrayList<>();
        for(var group:groups.values())if(group.size()>=threshold) {
            var e=group.get(0);
            result.add(new Finding("suspected-n-plus-one",e.root(),e.entity(),e.role(),group.stream().map(HibernateObservation::id).toList(),
                    group.stream().flatMap(x->selects.get(x.id()).stream()).distinct().toList(),"Repeated lazy initialization correlated with SELECT executions; association may be unknown or shared."));
        }
        for(var e:events)if(e.lazy()&&e.responsePhase())result.add(new Finding("lazy-during-response",e.root(),e.entity(),e.role(),List.of(e.id()),
                correlated.getOrDefault(e.id(),List.of()).stream().filter(s->s.kind().equals("SQL")).map(SqlObservation::id).toList(),"Lazy initialization occurred during Spring MVC return-value handling or later response rendering."));
        return List.copyOf(result);
    }
    public HibernateSnapshot subtree(Set<Long> parents) { return new HibernateSnapshot(enabled,available,version,revision,dropped,pending,events.stream().filter(e->parents.contains(e.parent())).toList()); }
    public String encode() {
        String s=String.join("\t","orm-log-v1",""+enabled,""+available,version,""+revision,""+dropped,""+pending)+"\n"+String.join("\n",events.stream().map(HibernateObservation::encode).toList());
        if(s.length()>MAX_WIRE) throw new IllegalArgumentException("Hibernate log too large"); return s;
    }
    public static HibernateSnapshot decode(String s) {
        if(s==null || s.length()>MAX_WIRE) throw new IllegalArgumentException("Hibernate log too large");
        String[] lines=s.split("\n",-1),p=lines[0].split("\t",-1);
        if(p.length!=7 || !p[0].equals("orm-log-v1") || lines.length>MAX_EVENTS+2 || !Set.of("true","false").contains(p[1]) || !Set.of("true","false").contains(p[2])) throw new IllegalArgumentException("Invalid Hibernate log");
        List<HibernateObservation> events=new ArrayList<>();
        for(int i=1;i<lines.length;i++) if(!lines[i].isEmpty()) events.add(HibernateObservation.decode(lines[i]));
        return new HibernateSnapshot(Boolean.parseBoolean(p[1]),Boolean.parseBoolean(p[2]),p[3],Long.parseLong(p[4]),Long.parseLong(p[5]),Long.parseLong(p[6]),events);
    }
}
