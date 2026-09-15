package com.baader.devrt;

import hu.baader.repl.protocol.ReplProtocol;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotCasesTest {
    @TempDir Path home; String previousHome,session; ReplHandler handler;
    @BeforeEach void setup() {
        previousHome=System.getProperty("user.home");System.setProperty("user.home",home.toString());
        handler=new ReplHandler();session=handler.createSession();
        SnapshotManager.save("input",new ArrayList<Integer>());SnapshotManager.save("expected",1);
    }
    @AfterEach void cleanup(){handler.close();RuntimeEvents.clearAll();System.setProperty("user.home",previousHome);}
    Map<String,Object> request(String op,Map<String,String> fields){Map<String,String> message=new HashMap<>(fields);message.put("session",session);return handler.handle(op,message);}
    Map<String,Object> ok(String op,Map<String,String> fields){var result=request(op,fields);assertFalse(ReplProtocol.error(result),result.toString());return result;}
    void save(String name,String code){ok("case/save",Map.of("name",name,"input","input","expected","expected","variable","input","code",code));}
    @Test void savingAndLoadingNeverRunCodeAndCasesReloadTheirInput() {
        String code="input.add(1); input.size()";save("size",code);
        assertEquals(code,ok("case/load",Map.of("name","size")).get("code"));
        assertTrue(ok("snapshot/list",Map.of()).get("value").toString().contains("\tCASE"));
        assertEquals(0,((List<?>)SnapshotManager.load("input")).size());
        for(int i=0;i<2;i++) assertEquals("PASSED",ok("case/run",Map.of("name","size")).get("outcome"));
        assertEquals(0,((List<?>)SnapshotManager.load("input")).size());
        assertTrue(ReplProtocol.error(request("eval",Map.of("code","input"))));
    }
    @Test void comparisonReportsFailuresAndActualValuesCanBeInspected() {
        save("wrong","42");var result=ok("case/run",Map.of("name","wrong"));
        assertEquals("FAILED",result.get("outcome"));assertTrue(result.get("value").toString().contains("CHANGED"));
        var view=ok("inspector/start",Map.of("event",result.get("event").toString()));assertEquals("42",view.get("preview"));
        ok("inspector/bind",Map.of("var","actual"));assertEquals("43",ok("eval",Map.of("code","actual+1")).get("value"));
        ok("snapshot/save",Map.of("name","actual","inspected","true"));assertEquals((Object)42,SnapshotManager.load("actual"));
    }
    @Test void dirtyWorkbookDoesNotAffectCasesAndCompilationErrorsAreExplicit() {
        ok("eval",Map.of("code","int workbookOnly=1;"));save("isolated","workbookOnly");
        assertEquals("ERROR",ok("case/run",Map.of("name","isolated")).get("outcome"));
        save("void","Thread.sleep(0);");assertEquals("ERROR",ok("case/run",Map.of("name","void")).get("outcome"));
        assertEquals("1",ok("eval",Map.of("code","workbookOnly")).get("value"));
    }
    @Test void caseCannotOverwriteItsInputOrRunARecipeAsData() {
        assertTrue(ReplProtocol.error(request("case/save",Map.of("name","input","input","input","expected","expected","code","1"))));
        assertEquals(0,((List<?>)SnapshotManager.load("input")).size());
        SnapshotManager.saveRecipe("recipe","System.exit(99);");
        assertTrue(ReplProtocol.error(request("case/save",Map.of("name","bad","input","recipe","expected","expected","code","1"))));
    }
    @Test void savedCasesSurviveSessionReplacementAndNullResultsMatch() {
        SnapshotManager.save("expected",null);save("null-case","(Object)null");
        handler.closeSession(session);session=handler.createSession();
        assertEquals("PASSED",ok("case/run",Map.of("name","null-case")).get("outcome"));
    }
    @Test void interruptStopsACaseWithoutLosingTheWorkbook() throws Exception {
        var entered=new CountDownLatch(1);ReplBindings.put("case-entered",entered);
        save("blocked","((java.util.concurrent.CountDownLatch)com.baader.devrt.ReplBindings.get(\"case-entered\")).countDown(); Thread.sleep(60000); 1");
        var result=new CompletableFuture<Map<String,Object>>();
        handler.submit(session,()->result.complete(request("case/run",Map.of("name","blocked"))));
        try {
            assertTrue(entered.await(10,TimeUnit.SECONDS));ok("interrupt",Map.of());
            assertEquals("CANCELLED",result.get(10,TimeUnit.SECONDS).get("outcome"));
            assertEquals("42",ok("eval",Map.of("code","41+1")).get("value"));
        } finally {handler.interrupt(session);}
    }
    @Test void debuggerTransferUsesAnExistingObjectAndResetExpiresTickets() {
        String ticket=UUID.randomUUID().toString();Object input=List.of("x");
        DebugBridge.capture(session,ticket,ProcessHandle.current().pid(),input);
        ok("debug/claim",Map.of("ticket",ticket,"var","debugInput"));
        assertEquals("1",ok("eval",Map.of("code","debugInput.size()")).get("value"));
        DebugBridge.capture(session,ticket,ProcessHandle.current().pid(),input);ok("session/reset",Map.of());
        assertTrue(ReplProtocol.error(request("debug/claim",Map.of("ticket",ticket))));
        assertTrue(ReplProtocol.error(request("inspector/page",Map.of())));
    }
}
