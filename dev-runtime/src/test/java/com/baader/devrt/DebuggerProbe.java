package hu.baader.repl.fixture;

import java.util.*;
import com.baader.devrt.*;
import hu.baader.repl.protocol.ReplProtocol;

/** DebuggerBridgeIntegrationTest stops in paused() and transfers its actual argument over JDI. */
public final class DebuggerProbe {
    static final String TICKET="00112233-4455-6677-8899-aabbccddeeff";
    public static void main(String[] args) throws Exception {
        Class.forName("com.baader.devrt.DebugBridge");
        try(ReplHandler handler=new ReplHandler()) {
            String session=handler.createSession();
            Object value=new ArrayList<>(List.of("debugged")); ReplBindings.put("debug-object",value);
            paused(session,ProcessHandle.current().pid(),value);
            var claimed=handler.handle("debug/claim",Map.of("session",session,"ticket",TICKET,"var","debugValue"));
            if(ReplProtocol.error(claimed)) throw new AssertionError(claimed);
            var evaluated=handler.handle("eval",Map.of("session",session,"code","debugValue == com.baader.devrt.ReplBindings.get(\"debug-object\")"));
            if(!"true".equals(evaluated.get("value"))) throw new AssertionError(evaluated);
            System.out.println("DEBUGGER_IDENTITY_OK");
        }
    }
    public static void paused(String session,long pid,Object value) { /* JDI breakpoint */ }
}
