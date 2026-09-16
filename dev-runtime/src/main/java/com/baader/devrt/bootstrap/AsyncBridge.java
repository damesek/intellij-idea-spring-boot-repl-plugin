package com.baader.devrt.bootstrap;

/** The only class injected into bootstrap. It has no application, Spring, Byte Buddy or agent dependencies. */
public final class AsyncBridge {
    public static volatile boolean enabled;
    public static volatile java.util.function.Function<Object[],Object> handler;
    private AsyncBridge(){}
    public static Object event(int kind,Object task,Object extra){
        var callback=handler;if(!enabled||callback==null)return null;
        try{return callback.apply(new Object[]{kind,task,extra});}catch(Throwable ignored){return null;}
    }
}
