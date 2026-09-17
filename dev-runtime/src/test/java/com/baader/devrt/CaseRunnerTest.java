package com.baader.devrt;

import hu.baader.repl.protocol.ReplProtocol;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.h2.jdbcx.JdbcDataSource;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class CaseRunnerTest {
    @TempDir Path home;String previous,session;ReplHandler handler;GenericApplicationContext context;JdbcTemplate jdbc;
    @BeforeEach void setup(){
        previous=System.getProperty("user.home");System.setProperty("user.home",home.toString());
        var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:"+UUID.randomUUID());jdbc=new JdbcTemplate(source);
        context=new GenericApplicationContext();context.registerBean("source",JdbcDataSource.class,()->source);
        context.registerBean("jdbc",JdbcTemplate.class,()->jdbc);context.registerBean("transactionManager",DataSourceTransactionManager.class,()->new DataSourceTransactionManager(source));context.refresh();
        source.setURL(source.getURL()+";DB_CLOSE_DELAY=-1");jdbc.execute("create table entries (id integer)");
        SpringContextHolder.set(context);handler=new ReplHandler();session=handler.createSession();
        SnapshotManager.save("input",1);SnapshotManager.save("expected",1);
    }
    @AfterEach void cleanup(){handler.close();SpringContextHolder.clear(context);context.close();Thread.interrupted();RuntimeEvents.clearAll();System.setProperty("user.home",previous);}
    Map<String,Object> request(String op,Map<String,String> fields){var message=new HashMap<>(fields);message.put("session",session);return handler.handle(op,message);}
    Map<String,Object> ok(String op,Map<String,String> fields){var result=request(op,fields);assertFalse(ReplProtocol.error(result),result.toString());return result;}
    Map<String,String> definition(){return new HashMap<>(Map.of("name","case","input","input","expected","expected","type","java.lang.Integer","code","1","parameters-json","[{\"id\":\"a\",\"input\":\"input\",\"expected\":\"expected\"},{\"id\":\"b\",\"input\":\"input\",\"expected\":\"expected\"}]"));}
    @Test void rejectedCaseEditPreservesTheLastResultAndSavedDefinition() {
        var saved = definition();
        ok("case/save", saved);
        var before = ok("case/run", Map.of("name", "case"));
        saved.put("assertions-json", "{\"typo\":true}");
        assertTrue(ReplProtocol.error(request("case/save", saved)));
        assertEquals(before.get("run-id"), ok("case/result", Map.of("name", "case")).get("run-id"));
        assertFalse(ok("case/load", Map.of("name", "case")).toString().contains("typo"));
        saved.remove("assertions-json");
        ok("case/save", saved);
        assertTrue(ReplProtocol.error(request("case/result", Map.of("name", "case"))));
    }
    @Test void eachParameterHasItsOwnRollbackBoundaryAndResultsAreSessionScoped(){
        var saved=definition();saved.put("code","var jdbc = ctx.getBean(org.springframework.jdbc.core.JdbcTemplate.class); jdbc.update(\"insert into entries values (?)\", input);");saved.put("result-expression","jdbc.queryForObject(\"select count(*) from entries\", Integer.class)");
        ok("case/save",saved);var result=ok("case/run",Map.of("name","case","execution-mode","ROLLBACK"));
        assertEquals("PASSED",result.get("outcome"),result.toString());assertEquals(2,result.get("rows-total"));assertEquals(0,jdbc.queryForObject("select count(*) from entries",Integer.class));
        assertTrue(ok("case/result",Map.of("name","case","row","1")).get("value").toString().contains("\"transaction-rolled-back\":true"));
        String other=handler.createSession();assertTrue(ReplProtocol.error(handler.handle("case/result",Map.of("session",other,"name","case"))));
        ok("session/reset",Map.of());assertTrue(ReplProtocol.error(request("case/result",Map.of("name","case"))));
    }
    @Test void validationPreventsPartialBatchExecutionAndDisabledCasesNeverExecute(){
        var saved=definition();saved.put("disabled","true");saved.put("code","throw new IllegalStateException(\"must not run\");");saved.put("tags","regression, billing");ok("case/save",saved);
        assertEquals("SKIPPED",ok("case/run",Map.of("name","case")).get("outcome"));assertTrue(ok("case/list",Map.of()).get("value").toString().contains("regression, billing"));
        saved.put("disabled","false");saved.put("code","ctx.getBean(org.springframework.jdbc.core.JdbcTemplate.class).update(\"insert into entries values (1)\")");ok("case/save",saved);
        assertTrue(ReplProtocol.error(request("case/run-batch",Map.of("names","case\nmissing"))));assertEquals(0,jdbc.queryForObject("select count(*) from entries",Integer.class));
        saved.put("assertions-json","{\"typo\":true}");assertTrue(ReplProtocol.error(request("case/save",saved)));
        saved.remove("assertions-json");saved.put("parameters-json","[{\"id\":\"a\",\"input\":\"missing\",\"expected\":\"expected\"}]");assertTrue(ReplProtocol.error(request("case/save",saved)));
    }
    @Test void setupCleanupFailuresCannotPassAsExpectedExceptionsAndAssertionFailuresStillCleanUp(){
        var saved=definition();saved.put("parameters-json","");saved.put("setup","int factor=2;");saved.put("code","input * factor");saved.put("teardown","ctx.getBean(org.springframework.jdbc.core.JdbcTemplate.class).update(\"insert into entries values (99)\");");
        ok("case/save",saved);assertEquals("FAILED",ok("case/run",Map.of("name","case")).get("outcome"));assertEquals(1,jdbc.queryForObject("select count(*) from entries",Integer.class));
        saved.put("expected-exception","java.lang.IllegalStateException");saved.put("expected-message","setup failed");saved.put("setup","throw new IllegalStateException(\"setup failed\");");
        ok("case/save",saved);assertEquals("ERROR",ok("case/run",Map.of("name","case")).get("outcome"));
        saved.put("setup","");saved.put("code","unknownSymbol");ok("case/save",saved);assertEquals("ERROR",ok("case/run",Map.of("name","case")).get("outcome"));
        saved.put("expected-exception","");saved.put("code","1");saved.put("teardown","throw new IllegalStateException(\"cleanup failed\");");ok("case/save",saved);
        var failed=ok("case/run",Map.of("name","case"));assertEquals("ERROR",failed.get("outcome"));assertTrue(failed.get("detail").toString().contains("cleanup failed"));
    }
    @Test void interruptAndDeadlinePreventLaterRowsAndCases() throws Exception {
        var entered=new CountDownLatch(1);ReplBindings.put("rows-entered",entered);
        var saved=definition();saved.put("code","((java.util.concurrent.CountDownLatch)com.baader.devrt.ReplBindings.get(\"rows-entered\")).countDown(); Thread.sleep(60000); 1");ok("case/save",saved);
        var result=new CompletableFuture<Map<String,Object>>();handler.submit(session,()->result.complete(request("case/run",Map.of("name","case"))));
        assertTrue(entered.await(10,TimeUnit.SECONDS));handler.interrupt(session);
        var stopped=result.get(10,TimeUnit.SECONDS);assertEquals("CANCELLED",stopped.get("outcome"));assertEquals(1,stopped.get("rows-completed"));
        assertEquals("2",ok("eval",Map.of("code","1+1")).get("value"));
        var timed=request("case/run-batch",Map.of("names","case","timeout-ms","500"));assertEquals("CANCELLED",timed.get("outcome"));
    }
    public static class SlowInput {
        static volatile CountDownLatch entered, release;
        public SlowInput(){if(entered!=null){entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException failure){Thread.currentThread().interrupt();}}}
        public int getValue(){return 1;}
        public void setValue(int value){}
    }
    @Test void interruptionDuringInputMaterializationPreventsSetupAndCode() throws Exception {
        SnapshotManager.save("slow",new SlowInput());
        var saved=definition();saved.put("input","slow");saved.put("parameters-json","");saved.put("type","");
        saved.put("setup","ctx.getBean(org.springframework.jdbc.core.JdbcTemplate.class).update(\"insert into entries values (1)\");");ok("case/save",saved);
        SlowInput.entered=new CountDownLatch(1);SlowInput.release=new CountDownLatch(1);
        var result=new CompletableFuture<Map<String,Object>>();handler.submit(session,()->result.complete(request("case/run",Map.of("name","case"))));
        try {
            assertTrue(SlowInput.entered.await(5,TimeUnit.SECONDS));handler.interrupt(session);SlowInput.release.countDown();
            assertEquals("CANCELLED",result.get(5,TimeUnit.SECONDS).get("outcome"));assertEquals(0,jdbc.queryForObject("select count(*) from entries",Integer.class));
        } finally {SlowInput.release.countDown();SlowInput.entered=null;}
    }
    @Test void richAssertionsAndDurationLimitsApplyToTheResultExpression(){
        var saved=definition();saved.put("parameters-json","");saved.put("code","");saved.put("result-expression","java.util.Map.of(\"id\",42,\"name\",\"hello\")");
        saved.put("assertions-json","{\"compareSnapshot\":false,\"checks\":[{\"path\":\"/name\",\"op\":\"contains\",\"value\":\"ell\"}]}");ok("case/save",saved);
        assertEquals("PASSED",ok("case/run",Map.of("name","case")).get("outcome"));
        saved.put("code","Thread.sleep(30);");saved.put("max-duration-ms","1");ok("case/save",saved);assertEquals("FAILED",ok("case/run",Map.of("name","case")).get("outcome"));
    }
}
