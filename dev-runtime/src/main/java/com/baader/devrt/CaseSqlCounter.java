package com.baader.devrt;

import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;

/** Portable JUnit helper: wrap every Spring-managed DataSource before application beans use it. */
public final class CaseSqlCounter {
    private CaseSqlCounter() {}
    private static final ThreadLocal<Measurement> CURRENT=new ThreadLocal<>();
    private static final ThreadLocal<Integer> DEPTH=ThreadLocal.withInitial(() -> 0);
    public static final class Measurement implements AutoCloseable {
        private final Measurement previous=CURRENT.get();
        private final Map<String,Integer> repetitions=new HashMap<>();
        private int count; private boolean partial;
        public Measurement() { CURRENT.set(this); }
        private void query(String key) { count++; if(repetitions.size()>=1000 && !repetitions.containsKey(key)) partial=true; else repetitions.merge(key,1,Integer::sum); }
        public void assertLimits(long maxCount,long maxRepetitions) {
            if(partial) throw new AssertionError("SQL observation limit reached; cannot establish the repetition upper bound");
            int repeat=repetitions.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            if(maxCount>=0 && count>maxCount || maxRepetitions>=0 && repeat>maxRepetitions)
                throw new AssertionError("SQL budget exceeded: count="+count+", max repetition="+repeat);
        }
        public void close() { if(previous==null) CURRENT.remove(); else CURRENT.set(previous); }
    }
    public static DataSource wrap(DataSource datasource) {
        String identity=datasource.getClass().getName()+"@"+System.identityHashCode(datasource);
        return (DataSource)Proxy.newProxyInstance(DataSource.class.getClassLoader(),datasource instanceof AutoCloseable ? new Class<?>[]{DataSource.class,AutoCloseable.class} : new Class<?>[]{DataSource.class},(proxy,method,args) -> {
            Object value=invoke(datasource,method,args);
            return method.getName().equals("getConnection") && value instanceof Connection c ? connection(c,identity) : value;
        });
    }
    private static Connection connection(Connection connection,String datasource) {
        return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args) -> {
            Object value=invoke(connection,method,args);
            if(value instanceof Statement s && Set.of("prepareStatement","prepareCall","createStatement").contains(method.getName()))
                return statement(s,args!=null && args.length>0 && args[0] instanceof String sql ? sql : null,datasource);
            return value;
        });
    }
    private static Statement statement(Statement statement,String prepared,String datasource) {
        Class<?> api=statement instanceof CallableStatement ? CallableStatement.class : statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
        return (Statement)Proxy.newProxyInstance(api.getClassLoader(),new Class<?>[]{api},(proxy,method,args) -> {
            if(!Set.of("execute","executeQuery","executeUpdate","executeLargeUpdate","executeBatch","executeLargeBatch").contains(method.getName())) return invoke(statement,method,args);
            int depth=DEPTH.get();DEPTH.set(depth+1);
            try {
                Measurement capture=CURRENT.get();
                if(depth==0 && capture!=null) {
                    String sql=args!=null && args.length>0 && args[0] instanceof String text ? text : prepared;
                    String key=datasource+"\n"+hu.baader.repl.protocol.SqlText.normalize(sql);
                    if(method.getName().contains("Batch")) key="batch:"+key;
                    capture.query(key);
                }
                return invoke(statement,method,args);
            } finally { if(depth==0) DEPTH.remove(); else DEPTH.set(depth); }
        });
    }
    private static Object invoke(Object target,Method method,Object[] args) throws Throwable {
        try { return method.invoke(target,args); } catch(InvocationTargetException error) { throw error.getCause(); }
    }
}
