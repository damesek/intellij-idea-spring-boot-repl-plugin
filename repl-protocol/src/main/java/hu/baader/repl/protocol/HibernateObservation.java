package hu.baader.repl.protocol;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** ORM evidence contains metadata, never entity instances, identifiers or property values. */
public record HibernateObservation(long id, long parentOrm, long parent, long root, long thread,
        long startedAt, long durationNanos, String session, String kind, String entity, String role,
        String detail, String sourceClass, String sourceMethod, String sourceFile, int sourceLine,
        String error, boolean responsePhase) {
    public static final Set<String> KINDS=Set.of("QUERY","LAZY_ENTITY","LAZY_ATTRIBUTE","COLLECTION_INIT",
            "ENTITY_LOAD","ENTITY_INSERT","ENTITY_UPDATE","ENTITY_DELETE","FLUSH","AUTO_FLUSH",
            "DIRTY_CHECK","CACHE_HIT","CACHE_MISS","CACHE_PUT","QUERY_CACHE_HIT","QUERY_CACHE_MISS","QUERY_CACHE_PUT","TRANSACTION","TRANSACTION_BEGIN","TRANSACTION_COMMIT","TRANSACTION_ROLLBACK");
    public HibernateObservation {
        if(id<1 || parentOrm<0 || parentOrm>=id || parent<0 || root<0 || thread<0 || startedAt<0 || durationNanos<0 || !KINDS.contains(kind))
            throw new IllegalArgumentException("Invalid Hibernate observation");
        for(String s:List.of(session,entity,role,detail,sourceClass,sourceMethod,sourceFile,error))
            if(s.length()>4096) throw new IllegalArgumentException("Hibernate metadata too large");
    }
    public boolean lazy() { return Set.of("LAZY_ENTITY","LAZY_ATTRIBUTE","COLLECTION_INIT").contains(kind); }
    public String encode() {
        return String.join("\t","orm-v1",""+id,""+parentOrm,""+parent,""+root,""+thread,""+startedAt,""+durationNanos,
                b(session),kind,b(entity),b(role),b(detail),b(sourceClass),b(sourceMethod),b(sourceFile),""+sourceLine,b(error),""+responsePhase);
    }
    public static HibernateObservation decode(String s) {
        if(s.length()>60000) throw new IllegalArgumentException("Hibernate observation too large");
        String[] p=s.split("\t",-1);
        if(p.length!=19 || !p[0].equals("orm-v1") || !Set.of("true","false").contains(p[18])) throw new IllegalArgumentException("Invalid Hibernate observation");
        return new HibernateObservation(n(p[1]),n(p[2]),n(p[3]),n(p[4]),n(p[5]),n(p[6]),n(p[7]),t(p[8]),p[9],t(p[10]),t(p[11]),t(p[12]),t(p[13]),t(p[14]),t(p[15]),Integer.parseInt(p[16]),t(p[17]),Boolean.parseBoolean(p[18]));
    }
    private static long n(String s) { return Long.parseLong(s); }
    private static String b(String s) { return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)); }
    private static String t(String s) { return new String(Base64.getDecoder().decode(s),StandardCharsets.UTF_8); }
}
