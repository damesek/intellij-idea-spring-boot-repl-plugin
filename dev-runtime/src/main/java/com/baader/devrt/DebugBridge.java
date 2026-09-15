package com.baader.devrt;

/** Called by IDEA's Java evaluator on a suspended application thread; never waits on nREPL. */
public final class DebugBridge {
    private DebugBridge() {}
    public static boolean checkTarget(String session,long expectedPid) {
        if(ProcessHandle.current().pid()!=expectedPid) throw new IllegalArgumentException("Debugger and REPL target different JVMs");
        RuntimeEvents.requireSession(session);
        return true;
    }
    public static String capture(String session,String ticket,long expectedPid,Object value) {
        checkTarget(session,expectedPid);
        RuntimeEvents.captureDebug(session,ticket,value);
        return "Value captured. Resume the application to import it into the REPL.";
    }
}
