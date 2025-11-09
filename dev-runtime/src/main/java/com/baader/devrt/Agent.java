package com.baader.devrt;

import java.lang.instrument.Instrumentation;

public class Agent {
    public static void premain(String agentArgs, Instrumentation inst) {
        AgentRuntime.setInstrumentation(inst);
        start(agentArgs);
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        AgentRuntime.setInstrumentation(inst);
        start(agentArgs);
    }

    private static void start(String agentArgs) {
        int port = 5557;
        try {
            if (agentArgs != null) {
                for (String part : agentArgs.split(",")) {
                    String[] kv = part.split("=", 2);
                    if (kv.length == 2 && kv[0].trim().equals("port")) {
                        port = Integer.parseInt(kv[1].trim());
                    }
                }
            }
        } catch (Throwable ignored) {}

        final int finalPort = port;
        Thread t = new Thread(() -> {
            try {
                MiniNreplServer server = new MiniNreplServer(finalPort);
                server.start();
                System.out.println("[dev-runtime] started on port " + finalPort);
                // Kick off auto-bind attempts in background
                AutoBinder.scheduleAutoBind();
            } catch (Throwable t1) {
                t1.printStackTrace();
            }
        }, "dev-runtime-server");
        t.setDaemon(true);
        t.start();
    }
}
