package com.baader.devrt;

import com.sun.jdi.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.EventRequest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class DebuggerBridgeIntegrationTest {
    @TempDir Path temp;
    @Test void pausedApplicationValueIsTransferredAndClaimedAfterResume() throws Exception {
        Assumptions.assumeFalse(Boolean.getBoolean("sb.repl.tests.noSockets"),"JDI launching requires a loopback socket; run in CI or a normal local build");
        var connector=Bootstrap.virtualMachineManager().defaultConnector();
        var options=connector.defaultArguments();
        options.get("main").setValue("hu.baader.repl.fixture.DebuggerProbe");
        String encoded=Base64.getUrlEncoder().withoutPadding().encodeToString(temp.resolve("endpoint.properties").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        options.get("options").setValue("-cp \""+System.getProperty("java.class.path")+"\" \"-javaagent:"+System.getProperty("sb.repl.agentJar")+"=port=0,endpoint64="+encoded+"\"");
        VirtualMachine vm=connector.launch(options);Process process=vm.process();
        var stdout=CompletableFuture.supplyAsync(()->read(process.getInputStream()));
        var stderr=CompletableFuture.supplyAsync(()->read(process.getErrorStream()));
        boolean captured=false;
        try {
            // A method-entry request also slows the compiler during JShell startup.
            // Install a breakpoint only in the probe after its class is prepared.
            var prepare=vm.eventRequestManager().createClassPrepareRequest();
            prepare.addClassFilter("hu.baader.repl.fixture.DebuggerProbe");
            prepare.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);prepare.enable();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            while(!captured && process.isAlive() && System.nanoTime()<deadline) {
                EventSet events=vm.eventQueue().remove(1000);if(events==null)continue;
                for(Event event:events) {
                    if(event instanceof ClassPrepareEvent loaded) {
                        var breakpoint=vm.eventRequestManager().createBreakpointRequest(
                                loaded.referenceType().methodsByName("paused").get(0).location());
                        breakpoint.setSuspendPolicy(EventRequest.SUSPEND_ALL);breakpoint.enable();prepare.disable();
                    }
                    if(event instanceof BreakpointEvent entered) {
                        List<Value> arguments=entered.thread().frame(0).getArgumentValues();
                        ClassType bridge=(ClassType)vm.classesByName("com.baader.devrt.DebugBridge").get(0);
                        Method capture=bridge.concreteMethodByName("capture","(Ljava/lang/String;Ljava/lang/String;JLjava/lang/Object;)Ljava/lang/String;");
                        Value receipt=bridge.invokeMethod(entered.thread(),capture,List.of(arguments.get(0),vm.mirrorOf("00112233-4455-6677-8899-aabbccddeeff"),arguments.get(1),arguments.get(2)),ClassType.INVOKE_SINGLE_THREADED);
                        assertTrue(((StringReference)receipt).value().contains("captured"));captured=true;
                        entered.request().disable();
                    }
                }
                events.resume();
            }
            assertTrue(captured,"Breakpoint was not reached");
            assertTrue(process.waitFor(15,TimeUnit.SECONDS),"Debuggee did not finish after Resume");
            String output=stdout.get(5,TimeUnit.SECONDS)+stderr.get(5,TimeUnit.SECONDS);
            assertEquals(0,process.exitValue(),output);assertTrue(output.contains("DEBUGGER_IDENTITY_OK"),output);
        } finally {try{vm.dispose();}catch(VMDisconnectedException ignored){}process.destroyForcibly();}
    }
    private static String read(java.io.InputStream input) {
        try(input){return new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
        catch(java.io.IOException failure){return failure.toString();}
    }
}
