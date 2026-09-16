package hu.baader.repl.protocol;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** JDBC client-side evidence. SQL literals are removed; parameter values are never retained. */
public record SqlObservation(long id, long parent, long root, long thread, long startedAt, long durationNanos,
        String kind, String operation, String sql, String datasource, String sourceClass, String sourceMethod,
        String sourceFile, int sourceLine, String error, int batchSize, long orm) {
    public SqlObservation(long id,long parent,long root,long thread,long startedAt,long durationNanos,String kind,String operation,String sql,String datasource,String sourceClass,String sourceMethod,String sourceFile,int sourceLine,String error,int batchSize) {
        this(id,parent,root,thread,startedAt,durationNanos,kind,operation,sql,datasource,sourceClass,sourceMethod,sourceFile,sourceLine,error,batchSize,0);
    }
    public SqlObservation {
        if (id < 1 || parent < 0 || root < 0 || thread < 0 || startedAt < 0 || durationNanos < 0 || batchSize < 0 || orm < 0
                || !Set.of("SQL", "CONNECTION").contains(kind)) throw new IllegalArgumentException("Invalid JDBC observation");
        if(!Set.of("execute","executeQuery","executeUpdate","executeLargeUpdate","executeBatch","executeLargeBatch","getConnection").contains(operation))
            throw new IllegalArgumentException("Invalid JDBC operation");
        for (String s : List.of(operation,sql,datasource,sourceClass,sourceMethod,sourceFile,error))
            if (s.length() > 4096) throw new IllegalArgumentException("JDBC metadata too large");
    }
    public String fingerprint() { return datasource + "\n" + sql + "\n" + sourceClass + "." + sourceMethod + ":" + sourceLine; }
    public String encode() {
        return String.join("\t", orm==0?"sql-v1":"sql-v2", ""+id,""+parent,""+root,""+thread,""+startedAt,""+durationNanos,kind,
                b(operation),b(sql),b(datasource),b(sourceClass),b(sourceMethod),b(sourceFile),""+sourceLine,b(error),""+batchSize)+(orm==0?"":"\t"+orm);
    }
    public static SqlObservation decode(String s) {
        if(s.length()>40000) throw new IllegalArgumentException("JDBC observation too large");
        String[] p=s.split("\t",-1);
        if(!(p.length==17 && p[0].equals("sql-v1")) && !(p.length==18 && p[0].equals("sql-v2"))) throw new IllegalArgumentException("Invalid JDBC observation");
        return new SqlObservation(n(p[1]),n(p[2]),n(p[3]),n(p[4]),n(p[5]),n(p[6]),p[7],t(p[8]),t(p[9]),t(p[10]),t(p[11]),t(p[12]),t(p[13]),Integer.parseInt(p[14]),t(p[15]),Integer.parseInt(p[16]),p.length==18?n(p[17]):0);
    }
    private static long n(String s) { return Long.parseLong(s); }
    private static String b(String s) { return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)); }
    private static String t(String s) { return new String(Base64.getDecoder().decode(s),StandardCharsets.UTF_8); }
}
