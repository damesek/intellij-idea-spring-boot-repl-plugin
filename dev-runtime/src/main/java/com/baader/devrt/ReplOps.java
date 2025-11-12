package com.baader.devrt;

public final class ReplOps {
    public static final String EVAL = "eval";
    public static final String IMPORTS_GET = "imports/get";
    public static final String IMPORTS_ADD = "imports/add";
    public static final String SESSION_RESET = "session/reset";
    public static final String SNAPSHOT_SAVE = "snapshot/save";
    public static final String SNAPSHOT_GET  = "snapshot/get";
    public static final String SNAPSHOT_LIST = "snapshot/list";
    public static final String SNAPSHOT_DELETE = "snapshot/delete";
    
    // Legacy ops for compatibility
    public static final String JAVA_EVAL = "java-eval";
    public static final String BIND_SPRING = "bind-spring";
    public static final String CLASS_RELOAD = "class-reload";

    private ReplOps() {}
}
