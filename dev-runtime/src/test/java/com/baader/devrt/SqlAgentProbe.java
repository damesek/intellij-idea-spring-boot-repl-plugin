package hu.baader.repl.fixture;

import com.baader.devrt.*;
import hu.baader.repl.protocol.*;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.web.bind.annotation.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import java.util.*;
import java.util.concurrent.*;

/** Real packaged agent + Spring MVC + pool/proxy + H2; no simulated SQL events. */
public class SqlAgentProbe {
    @RestController public static class Controller {
        final Service service; public Controller(Service service) { this.service=service; }
        @GetMapping("/orders") public int orders() { return service.load(5); }
        @GetMapping("/joined") public int joined() { return service.load(1); }
    }
    public static class Service {
        final JdbcTemplate jdbc; public Service(JdbcTemplate jdbc) { this.jdbc=jdbc; }
        public int load(int times) { for(int i=0;i<times;i++) jdbc.queryForObject("select ?",Integer.class,42); return 42; }
        public void fail() { jdbc.execute("select missing from no_such_table"); }
        public void batch() { jdbc.batchUpdate("insert into entries values(1)","insert into entries values(2)"); }
    }
    static void check(boolean condition,Object evidence) { if(!condition)throw new AssertionError(evidence); }
    static Map<String,Object> ok(ReplHandler handler,String session,String op,Map<String,String> args) {
        Map<String,String> fields=new HashMap<>(args);fields.put("session",session);
        Map<String,Object> result=handler.handle(op,fields);check(!ReplProtocol.error(result),result);return result;
    }
    public static void main(String[] args) throws Exception {
        JdbcDataSource ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:agent-sql;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc=new JdbcTemplate(new TransactionAwareDataSourceProxy(ds));
        jdbc.execute("create table entries (id int)");
        Service service=new Service(jdbc);
        var mvc=MockMvcBuilders.standaloneSetup(new Controller(service)).build();
        try(var context=new org.springframework.context.support.GenericApplicationContext();var handler=new ReplHandler()) {
            context.registerBean("service",Service.class,()->service);context.refresh();SpringContextHolder.set(context);
            String session=handler.createSession();
            String classes=Controller.class.getName()+"\n"+Service.class.getName();
            ok(handler,session,"trace/record",Map.of("classes",classes,"sql","true"));
            ExecutorService executor=Executors.newFixedThreadPool(2);
            try {
                List<Future<?>> tasks=new ArrayList<>();
                for(int i=0;i<2;i++) tasks.add(executor.submit(()->{try {check(mvc.perform(MockMvcRequestBuilders.get("/orders")).andReturn().getResponse().getContentAsString().equals("42"),"MVC result changed");}catch(Exception e){throw new RuntimeException(e);}}));
                for(var task:tasks)task.get(20,TimeUnit.SECONDS);
            } finally {executor.shutdownNow();}
            var history=ok(handler,session,"trace/history",Map.of());
            SqlSnapshot sql=SqlSnapshot.decode(history.get("sql").toString());
            check(sql.available(),sql);check(sql.count()==10,sql);check(sql.findings().size()==2,sql);check(sql.maxRepetitions()==5,sql);check(!sql.partial(),sql);
            List<RecordedCall> calls=history.get("value").toString().lines().map(RecordedCall::decode).toList();
            check(calls.stream().filter(c->c.className().equals("http.Request")).count()==2,calls);
            check(sql.events().stream().allMatch(e->calls.stream().anyMatch(c->c.id()==e.parent()&&c.root()==e.root()&&c.threadId()==e.thread())),sql);
            check(sql.events().stream().filter(e->e.kind().equals("SQL")).allMatch(e->e.sql().equals("select ?")&&e.sourceLine()>0),sql);
            check(sql.events().stream().anyMatch(e->e.kind().equals("CONNECTION")),sql);
            check(!sql.encode().contains("42"+"secret"),sql);
            ok(handler,session,"trace/stop",Map.of());
            service.load(3);check(SqlSnapshot.decode(ok(handler,session,"trace/history",Map.of()).get("sql").toString()).count()==10,"Captured outside stopped flow");
            for(int cycle=0;cycle<12;cycle++) {
                ok(handler,session,"trace/record",Map.of("classes",classes,"sql","true"));
                service.load(2);service.batch();
                SqlSnapshot restarted=SqlSnapshot.decode(ok(handler,session,"trace/stop",Map.of()).get("sql").toString());
                check(restarted.count()==3,"Stop cleanup lost a new recording: "+restarted);
            }
            jdbc.update("delete from entries");
            ok(handler,session,"trace/record",Map.of("classes",classes,"sql","true"));
            mvc.perform(MockMvcRequestBuilders.get("/joined"));
            try{service.fail();throw new AssertionError("SQL error swallowed");}catch(org.springframework.dao.DataAccessException expected){}
            service.batch();
            SqlSnapshot after=SqlSnapshot.decode(ok(handler,session,"trace/stop",Map.of()).get("sql").toString());
            check(after.count()==3,after);check(after.findings().isEmpty(),after);check(after.events().stream().anyMatch(e->!e.error().isEmpty()),after);
            check(jdbc.queryForObject("select count(*) from entries",Integer.class)==2,"Observer changed batch");
            SnapshotManager.save("sql-input",1);SnapshotManager.save("sql-expected",42);
            Map<String,String> definition=new HashMap<>(Map.of("name","sql-case","input","sql-input","expected","sql-expected","type","int",
                "code","ctx.getBean(hu.baader.repl.fixture.SqlAgentProbe.Service.class).load(5)","max-sql-count","5","max-sql-repetitions","5"));
            ok(handler,session,"case/save",definition);
            var passed=ok(handler,session,"case/run",Map.of("name","sql-case"));check("PASSED".equals(passed.get("outcome")),passed);check(passed.get("sql-count").toString().equals("5"),passed);
            definition.put("max-sql-repetitions","4");ok(handler,session,"case/save",definition);
            var failed=ok(handler,session,"case/run",Map.of("name","sql-case"));check("FAILED".equals(failed.get("outcome")),failed);
            check(failed.get("sql-max-repetitions").toString().equals("5"),failed);
            definition.put("setup","ctx.getBean(hu.baader.repl.fixture.SqlAgentProbe.Service.class).load(3);");
            definition.put("teardown",definition.get("setup"));definition.put("max-sql-repetitions","5");
            ok(handler,session,"case/save",definition);check("PASSED".equals(ok(handler,session,"case/run",Map.of("name","sql-case")).get("outcome")),"Setup/teardown were counted");
            ok(handler,session,"trace/record",Map.of("classes",classes,"sql","true"));
            service.load(502);
            var limited=SqlSnapshot.decode(ok(handler,session,"trace/stop",Map.of()).get("sql").toString());
            check(limited.partial() && limited.dropped()>0 && limited.events().size()==SqlSnapshot.MAX_EVENTS,limited);
            SpringContextHolder.clear(context);
        }
        SqlJpaProbe.verify();
        System.out.println("SQL_MVC_JDBC_CASE_OK");
    }
}
