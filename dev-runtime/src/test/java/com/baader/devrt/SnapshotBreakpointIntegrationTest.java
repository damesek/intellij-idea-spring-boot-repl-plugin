package com.baader.devrt;

import com.sun.jdi.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.EventRequest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotBreakpointIntegrationTest {
    @TempDir Path home;
    @Test void aRealJavaLineBreakpointFreezesALocalAndContinuesTheApplication() throws Exception {
        var connector=Bootstrap.virtualMachineManager().defaultConnector(); var options=connector.defaultArguments();
        options.get("main").setValue("hu.baader.repl.fixture.SnapshotBreakpointProbe");
        String endpoint=Base64.getUrlEncoder().withoutPadding().encodeToString(home.resolve("endpoint.properties").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        options.get("options").setValue("-cp \""+System.getProperty("java.class.path")+"\" \"-Duser.home="+home+"\" \"-javaagent:"+System.getProperty("sb.repl.agentJar")+"=port=0,endpoint64="+endpoint+"\"");
        VirtualMachine vm=connector.launch(options); Process process=vm.process();
        var stdout=CompletableFuture.supplyAsync(()->read(process.getInputStream())); var stderr=CompletableFuture.supplyAsync(()->read(process.getErrorStream()));
        boolean captured=false;
        try {
            var prepare=vm.eventRequestManager().createClassPrepareRequest(); prepare.addClassFilter("hu.baader.repl.fixture.SnapshotBreakpointProbe"); prepare.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD); prepare.enable();
            vm.resume(); long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
            while (!captured && process.isAlive() && System.nanoTime()<deadline) {
                EventSet events=vm.eventQueue().remove(1000); if(events==null)continue;
                for(Event event:events) {
                    if(event instanceof ClassPrepareEvent loaded) {
                        var locations=loaded.referenceType().methodsByName("work").get(0).allLineLocations();
                        var breakpoint=vm.eventRequestManager().createBreakpointRequest(locations.get(1));
                        breakpoint.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD); breakpoint.enable(); prepare.disable();
                    }
                    if(event instanceof BreakpointEvent hit) {
                        var local=hit.thread().frame(0).visibleVariableByName("input"); assertNotNull(local);
                        Value input=hit.thread().frame(0).getValue(local);
                        ClassType bridge=(ClassType)vm.classesByName("com.baader.devrt.SnapshotBreakpoint").get(0);
                        String id=UUID.randomUUID().toString();
                        Method ready=bridge.concreteMethodByName("ready","(Ljava/lang/String;I)Z");
                        assertTrue(((BooleanValue)bridge.invokeMethod(hit.thread(),ready,List.of(vm.mirrorOf(id),vm.mirrorOf(1)),ClassType.INVOKE_SINGLE_THREADED)).value());
                        Method capture=bridge.concreteMethodByName("capture","(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ILjava/lang/Object;)Ljava/lang/String;");
                        String receipt=((StringReference)bridge.invokeMethod(hit.thread(),capture,List.of(vm.mirrorOf(id),vm.mirrorOf("line-input"),vm.mirrorOf(""),vm.mirrorOf(1),input),ClassType.INVOKE_SINGLE_THREADED)).value();
                        assertTrue(receipt.contains("Saved DATA"),receipt); captured=true; hit.request().disable();
                    }
                }
                events.resume();
            }
            assertTrue(captured,"Snapshot line was not reached"); assertTrue(process.waitFor(15,TimeUnit.SECONDS),"Application did not continue");
            String output=stdout.get(5,TimeUnit.SECONDS)+stderr.get(5,TimeUnit.SECONDS);
            assertEquals(0,process.exitValue(),output); assertTrue(output.contains("SNAPSHOT_LINE_AND_RESUME_OK"),output);
        } finally { try{vm.dispose();}catch(VMDisconnectedException ignored){} process.destroyForcibly(); }
    }
    private static String read(java.io.InputStream input) { try(input){return new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}catch(java.io.IOException failure){return failure.toString();} }
}
