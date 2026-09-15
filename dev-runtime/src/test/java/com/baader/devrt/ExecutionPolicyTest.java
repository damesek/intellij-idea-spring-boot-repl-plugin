package com.baader.devrt;

import org.junit.jupiter.api.*;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionPolicyTest {
    GenericApplicationContext context;
    JdbcTemplate jdbc;
    @BeforeEach void setup() {
        JdbcDataSource source = new JdbcDataSource(); source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source); jdbc.execute("create table entries (id integer)");
        context = new GenericApplicationContext();
        context.registerBean("transactionManager", DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(source));
        context.registerBean("jdbc", JdbcTemplate.class, () -> jdbc); context.refresh();
    }
    @AfterEach void close() { context.close(); }
    private Map<String,Object> evaluate(String mode, String code) {
        try (JShellSession shell = new JShellSession(context)) {
            return ExecutionPolicy.from(Map.of("execution-mode", mode)).execute(context, shell::interrupt, code, () -> {
                JShellSession.EvalResult value = shell.eval(code);
                return new LinkedHashMap<>(Map.of("error", value.error(), "values", value.values()));
            });
        }
    }
    @Test void rollsBackSuccessfulStatementsButLivePersists() {
        String code = "ctx.getBean(org.springframework.jdbc.core.JdbcTemplate.class).update(\"insert into entries values (1)\");";
        assertEquals("", evaluate("ROLLBACK", code).get("error"));
        assertEquals(0, jdbc.queryForObject("select count(*) from entries", Integer.class));
        assertEquals("", evaluate("LIVE", code).get("error"));
        assertEquals(1, jdbc.queryForObject("select count(*) from entries", Integer.class));
    }
    @Test void rollsBackWhenALaterSnippetThrows() {
        var result = evaluate("ROLLBACK", "ctx.getBean(org.springframework.jdbc.core.JdbcTemplate.class).update(\"insert into entries values (1)\"); throw new IllegalStateException(\"boom\");");
        assertTrue(result.get("error").toString().contains("boom"));
        assertEquals(0, jdbc.queryForObject("select count(*) from entries", Integer.class));
        assertEquals(true, result.get("transaction-rolled-back"));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }
    @Test void readOnlyHintIsAppliedAndStillRollsBack() {
        var result = evaluate("READ_ONLY", "org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly()");
        assertEquals(List.of("true"), result.get("values"));
        assertEquals(true, result.get("transaction-rolled-back"));
        assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
    }
    @Test void rejectsMissingOrAmbiguousManagerBeforeUserCode() {
        context.registerBean("otherManager", DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(jdbc.getDataSource()));
        boolean[] ran = {false};
        assertThrows(IllegalArgumentException.class, () -> ExecutionPolicy.from(Map.of("execution-mode", "ROLLBACK"))
                .execute(context, () -> {}, "", () -> { ran[0] = true; return Map.of(); }));
        assertFalse(ran[0]);
        var selected = ExecutionPolicy.from(Map.of("execution-mode", "ROLLBACK", "transaction-manager", "otherManager"));
        assertEquals("otherManager", selected.execute(context, () -> {}, "", Map::of).get("transaction-manager"));
        assertThrows(IllegalArgumentException.class, () -> selected.execute(null, () -> {}, "", Map::of));
    }
    @Test void interruptionRollsBackAndDoesNotInterruptTheNextEvaluation() {
        try (JShellSession shell = new JShellSession(context)) {
            String code = "ctx.getBean(org.springframework.jdbc.core.JdbcTemplate.class).update(\"insert into entries values (2)\"); Thread.sleep(10000);";
            var result = ExecutionPolicy.from(Map.of("execution-mode", "ROLLBACK", "timeout-ms", "1000"))
                    .execute(context, shell::interrupt, code, () -> { shell.eval(code); return Map.of(); });
            assertEquals(true, result.get("execution-timed-out"));
            assertEquals(0, jdbc.queryForObject("select count(*) from entries", Integer.class));
            assertEquals(List.of("2"), shell.eval("1+1").values());
        }
    }
    @Test void configuredRuntimePolicyAlsoWrapsCaseExecution(@org.junit.jupiter.api.io.TempDir java.nio.file.Path home) {
        String previousHome=System.getProperty("user.home"); System.setProperty("user.home",home.toString());
        SpringContextHolder.set(context);
        try(ReplHandler handler=new ReplHandler()) {
            String session=handler.createSession();
            var configured=handler.handle("execution/configure",Map.of("session",session,"execution-mode","ROLLBACK","transaction-manager","transactionManager"));
            assertFalse(hu.baader.repl.protocol.ReplProtocol.error(configured),configured.toString());
            String code="ctx.getBean(org.springframework.jdbc.core.JdbcTemplate.class).update(\"insert into entries values (1)\")";
            var result=handler.handle("eval",Map.of("session",session,"code",code));
            assertEquals(true,result.get("transaction-rolled-back"),result.toString());
            assertEquals(0,jdbc.queryForObject("select count(*) from entries",Integer.class));
            SnapshotManager.save("input",1); SnapshotManager.save("expected",1);
            SnapshotManager.saveCase("db-case",SnapshotCases.definition(Map.of("input","input","expected","expected","code",code)));
            var tested=handler.handle("case/run",Map.of("session",session,"name","db-case"));
            assertEquals("PASSED",tested.get("outcome"),tested.toString());
            assertEquals(true,tested.get("transaction-rolled-back"));
            assertEquals(0,jdbc.queryForObject("select count(*) from entries",Integer.class));
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        } finally { SpringContextHolder.clear(context); SnapshotManager.clearLive(); System.setProperty("user.home",previousHome); }
    }
}
